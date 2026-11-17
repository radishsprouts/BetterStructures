package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.runtime.GenerationGovernor;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 貼り付け1件の進行。チャンクを1つずつ、読み込み → 所有リージョンで段を進める → DONE → hold を外す、の順に処理する。
 *
 * <p>千切れ防止（04章）:
 * <ul>
 *   <li>G1・G2: チケットの持ち主は {@link PluginChunkHolds} だけ。走査から渡された hold は {@link PluginChunkHolds#transfer} で
 *       引き継ぎ、カウントが 0 になる瞬間を作らない。</li>
 *   <li>G3: チャンクの hold を外すのはそのチャンクが DONE の後だけ。予算切れは {@code runRegionLater(…, 1L)} で続ける。</li>
 *   <li>G4: 時間切れで捨てない。読み込みの完了はコールバックで待つ。例外で失敗した時だけ間隔つきで再試行し、
 *       上限を超えたら警告ログを出してセッションを終える。</li>
 *   <li>G5: 図面は依頼が持つものを最後まで使う。{@code /bs reload} の世代は見ない。</li>
 * </ul>
 */
final class PasteSession implements PasteEngine.PasteHandle {

    /** 読み込みの再試行の上限と、1回ごとに伸ばす間隔(tick)。 */
    static final int MAX_LOAD_ATTEMPTS = 5;
    static final long RETRY_BASE_TICKS = 20L;

    private static final AtomicBoolean ARENA_WARNED = new AtomicBoolean();

    /** チャンクの進み具合。段と、その段で次にやる操作の番号。 */
    static final class Progress {
        private volatile PasteStage stage = PasteStage.BLOCKS;
        private volatile int cursor;

        PasteStage stage() {
            return stage;
        }

        int cursor() {
            return cursor;
        }

        void step() {
            cursor++;
        }

        void advance() {
            stage = stage.next();
            cursor = 0;
        }

        void restore(PasteStage restoredStage, int restoredCursor) {
            this.stage = restoredStage;
            this.cursor = Math.max(0, restoredCursor);
        }
    }

    private final NativePasteEngine engine;
    private final PasteEnvironment env;
    private final PasteRequest request;
    private final String id;
    private final boolean resumed;
    private final PasteJournal.Entry restoredEntry;
    private final long startLifecycle;
    private final PluginChunkHolds.HoldSet holds;
    private final CompletableFuture<PasteResult> completion = new CompletableFuture<>();
    private final Map<Long, Progress> progress = new ConcurrentHashMap<>();
    private final Deque<Long> order = new ArrayDeque<>();

    private volatile PastePlan plan;
    private volatile GenerationGovernor.Ticket ticket;
    private volatile boolean cancelled;
    private volatile boolean suspended;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String arenaBoss;
    private volatile Location arenaLocation;
    private int loadAttempts;

    PasteSession(NativePasteEngine engine, PasteEnvironment env, PasteRequest request, String id,
                 PasteJournal.Entry restoredEntry) {
        this.engine = engine;
        this.env = env;
        this.request = request;
        this.id = id;
        this.resumed = restoredEntry != null;
        this.restoredEntry = restoredEntry;
        this.startLifecycle = env.lifecycle();
        this.holds = new PluginChunkHolds.HoldSet(request.world());
    }

    // ---- 参照 ------------------------------------------------------------------------------

    PasteEnvironment env() {
        return env;
    }

    PasteRequest request() {
        return request;
    }

    World world() {
        return request.world();
    }

    String id() {
        return id;
    }

    boolean resumed() {
        return resumed;
    }

    PastePlan plan() {
        return plan;
    }

    PluginChunkHolds.HoldSet holds() {
        return holds;
    }

    /** 取り消された・停止で中断された・終わった、のどれか。タスクはこれが true なら何もせず引き上げる。 */
    boolean isStopped() {
        if (!suspended && !finished.get() && !cancelled && env.lifecycle() != startLifecycle) {
            suspend();
        }
        return cancelled || suspended || finished.get();
    }

    boolean isSuspended() {
        return suspended;
    }

    // ---- PasteHandle -----------------------------------------------------------------------

    @Override
    public void cancel() {
        cancelled = true;
        finish(PasteResult.Outcome.CANCELLED);
    }

    @Override
    public boolean isDone() {
        return completion.isDone();
    }

    @Override
    public CompletableFuture<PasteResult> completion() {
        return completion;
    }

    // ---- 始まり ----------------------------------------------------------------------------

    /** 流量制御の枠が空いて始まる。重い準備は WorkerPool に回してすぐ返る。 */
    void begin(GenerationGovernor.Ticket governorTicket) {
        this.ticket = governorTicket;
        if (cancelled || suspended) {
            governorTicket.release();
            return;
        }
        if (!env.worldAlive(world())) {
            finish(PasteResult.Outcome.CANCELLED);
            return;
        }
        // 走査で取った hold を、先に新しい持ち主で hold してから古い分を外して引き継ぐ（G2）。
        if (request.heldChunks() != null) {
            PluginChunkHolds.transfer(request.heldChunks(), holds);
        }
        env.async().execute(this::prepare);
    }

    private void prepare() {
        try {
            if (isStopped()) return;
            PastePlan built = PastePlan.build(request);
            this.plan = built;
            for (Map.Entry<Long, PastePlan.ChunkOps> entry : built.chunks().entrySet()) {
                Progress p = new Progress();
                if (restoredEntry != null) {
                    PasteJournal.ChunkProgress saved = restoredEntry.chunks.get(Long.toString(entry.getKey()));
                    if (saved != null) p.restore(saved.stage(), saved.cursor);
                }
                progress.put(entry.getKey(), p);
                if (p.stage() != PasteStage.DONE) order.add(entry.getKey());
            }
            if (request.windowChunks() == 0) {
                for (long key : order) {
                    holds.hold((int) key, (int) (key >> 32));
                }
            }
            processNext();
        } catch (Throwable t) {
            StackTraceElement[] trace = t.getStackTrace();
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < Math.min(6, trace.length); i++) where.append("\n    at ").append(trace[i]);
            Logger.warn("貼り付けの準備に失敗しました (" + request.schematicName() + "): " + t + where);
            finish(PasteResult.Outcome.FAILED);
        }
    }

    // ---- チャンクごとの進行 ----------------------------------------------------------------

    private void processNext() {
        if (isStopped()) return;
        Long key;
        synchronized (order) {
            key = order.pollFirst();
        }
        if (key == null) {
            finish(PasteResult.Outcome.COMPLETED);
            return;
        }
        loadAttempts = 0;
        holdWindow(key);
        load(key);
    }

    /** 窓付きの時は、今のチャンクとその先 K 個を hold する。範囲全体を hold している時は何もしない。 */
    private void holdWindow(long key) {
        holds.hold((int) key, (int) (key >> 32));
        int window = request.windowChunks();
        if (window <= 0) return;
        synchronized (order) {
            Iterator<Long> ahead = order.iterator();
            for (int i = 0; i < window && ahead.hasNext(); i++) {
                long next = ahead.next();
                holds.hold((int) next, (int) (next >> 32));
            }
        }
    }

    private void load(long key) {
        if (isStopped()) return;
        if (!env.worldAlive(world())) {
            finish(PasteResult.Outcome.CANCELLED);
            return;
        }
        int cx = (int) key;
        int cz = (int) (key >> 32);
        CompletableFuture<?> future;
        try {
            future = env.chunks().load(world(), cx, cz);
        } catch (Throwable t) {
            onLoadFailed(key, t);
            return;
        }
        future.whenComplete((chunk, error) -> {
            if (error != null) {
                onLoadFailed(key, error);
            } else {
                onLoaded(key);
            }
        });
    }

    private void onLoaded(long key) {
        if (isStopped()) return;
        int cx = (int) key;
        int cz = (int) (key >> 32);
        PastePlan.ChunkOps ops = plan.chunks().get(key);
        ChunkPasteTask task = new ChunkPasteTask(this, ops, progress.get(key));
        env.regions().run(world(), cx, cz, task);
    }

    /** 読み込みの失敗だけを再試行する（間隔は伸ばす。上限を超えたら警告してこのセッションを終える）。 */
    private void onLoadFailed(long key, Throwable error) {
        if (isStopped()) return;
        loadAttempts++;
        if (loadAttempts > MAX_LOAD_ATTEMPTS) {
            Logger.warn("チャンク (" + (int) key + "," + (int) (key >> 32) + ") の読み込みが " + MAX_LOAD_ATTEMPTS
                    + " 回続けて失敗したため、貼り付け " + request.schematicName() + " を中断します: " + error);
            finish(PasteResult.Outcome.FAILED);
            return;
        }
        env.globalLater().run(() -> load(key), RETRY_BASE_TICKS * loadAttempts);
    }

    /** チャンクが DONE になった（所有リージョンのスレッドから）。ここで初めてそのチャンクの hold を外す（G3）。 */
    void chunkDone(int chunkX, int chunkZ) {
        holds.release(chunkX, chunkZ);
        processNext();
    }

    void recordArena(String bossFilename, Location location) {
        if (arenaBoss == null) {
            arenaBoss = bossFilename;
            arenaLocation = location;
        }
    }

    // ---- 終わり ----------------------------------------------------------------------------

    /** 停止（onDisable）で中断する。記録に残したまま hold と枠を返し、完了の通知は出さない。 */
    void suspend() {
        if (finished.get() || suspended) return;
        suspended = true;
        holds.releaseAll();
        GenerationGovernor.Ticket t = ticket;
        if (t != null) t.release();
        completion.complete(PasteResult.of(PasteResult.Outcome.CANCELLED));
    }

    private void finish(PasteResult.Outcome outcome) {
        if (suspended) return;
        if (!finished.compareAndSet(false, true)) return;
        try {
            holds.releaseAll();
            PluginChunkHolds.HoldSet caller = request.heldChunks();
            if (caller != null && outcome != PasteResult.Outcome.COMPLETED) {
                // 引き継ぎの前に終わった時に、呼び出し側の分が残らないようにする。
                caller.releaseAll();
            }
            GenerationGovernor.Ticket t = ticket;
            if (t != null) t.release();
        } finally {
            engine.sessionEnded(this);
        }

        PasteResult result = outcome == PasteResult.Outcome.COMPLETED ? completedResult() : PasteResult.of(outcome);
        if (outcome != PasteResult.Outcome.COMPLETED) {
            notifyFinished(result);
            return;
        }
        int cx = request.baseLocation().getBlockX() >> 4;
        int cz = request.baseLocation().getBlockZ() >> 4;
        Runnable completionTask = () -> {
            try {
                protectArena(result);
                if (request.announce()) announceCompletion();
            } catch (Throwable t) {
                Logger.warn("貼り付け完了の後処理でエラー: " + t);
            }
            notifyFinished(result);
        };
        if (env.regions().isOwned(world(), cx, cz)) {
            completionTask.run();
        } else {
            env.regions().run(world(), cx, cz, completionTask);
        }
    }

    private void notifyFinished(PasteResult result) {
        try {
            if (request.onFinished() != null) request.onFinished().accept(result);
        } catch (Throwable t) {
            Logger.warn("貼り付けの完了通知でエラー: " + t);
        } finally {
            completion.complete(result);
        }
    }

    private PasteResult completedResult() {
        Vector offset = request.schematicOffset() == null ? new Vector(0, 0, 0) : request.schematicOffset();
        Location min = request.baseLocation().clone().add(offset);
        Location max = min.clone().add(
                request.schematic().getWidth() - 1,
                request.schematic().getHeight() - 1,
                request.schematic().getLength() - 1);
        List<Location> containers = new ArrayList<>();
        for (Vector chestPos : request.schematic().getChestLocations()) {
            containers.add(min.clone().add(chestPos));
        }
        return new PasteResult(PasteResult.Outcome.COMPLETED, min, max, containers);
    }

    /** EliteMobs の闘技場の目印があった時、WorldGuard の領域で保護する（WorldGuard があるときだけ）。 */
    private void protectArena(PasteResult result) {
        if (arenaBoss == null || !DefaultConfig.isProtectEliteMobsRegions()) return;
        if (env.protection().isAvailable()) {
            env.protection().protectBossArena(result.minCorner(), result.maxCorner(), arenaBoss, arenaLocation);
        } else if (ARENA_WARNED.compareAndSet(false, true)) {
            Logger.warn("EliteMobs の闘技場を保護するには WorldGuard が必要です。保護は行いません。");
        }
    }

    private void announceCompletion() {
        if (!DefaultConfig.isNewBuildingWarn()) return;
        Location base = request.baseLocation();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("betterstructures.warn")) {
                player.spigot().sendMessage(SpigotMessage.commandHoverMessage(
                        "[BetterStructures] New building generated! Click to teleport. Do \"/betterstructures silent\" to stop getting warnings!",
                        "Click to teleport to " + base.getWorld().getName() + ", " + base.getBlockX() + ", " + base.getBlockY() + ", " + base.getBlockZ(),
                        "/betterstructures teleport " + base.getWorld().getName() + " " + base.getBlockX() + " " + base.getBlockY() + " " + base.getBlockZ()));
            }
        }
    }

    // ---- 記録 ------------------------------------------------------------------------------

    /** PasteJournal に載せる今の状態。進み具合が初期のままのチャンクは載せない。 */
    PasteJournal.Entry toEntry() {
        PasteJournal.Entry entry = new PasteJournal.Entry();
        entry.sessionId = id;
        entry.worldId = world().getUID().toString();
        entry.schematicName = request.schematicName();
        entry.rotationDegrees = request.rotationDegrees();
        entry.baseX = request.baseLocation().getBlockX();
        entry.baseY = request.baseLocation().getBlockY();
        entry.baseZ = request.baseLocation().getBlockZ();
        Vector offset = request.schematicOffset();
        entry.offsetX = offset == null ? 0 : offset.getBlockX();
        entry.offsetY = offset == null ? 0 : offset.getBlockY();
        entry.offsetZ = offset == null ? 0 : offset.getBlockZ();
        entry.fillPedestal = request.fillPedestal();
        entry.clearTrees = request.clearTrees();
        entry.announce = request.announce();
        entry.windowChunks = request.windowChunks();
        entry.bedrockReplacement = request.bedrockReplacement() == null ? null : request.bedrockReplacement().name();
        for (Map.Entry<Long, Progress> chunk : progress.entrySet()) {
            Progress p = chunk.getValue();
            PasteStage stage = p.stage();
            int cursor = p.cursor();
            if (stage == PasteStage.BLOCKS && cursor == 0) continue;
            entry.chunks.put(Long.toString(chunk.getKey()), new PasteJournal.ChunkProgress(stage, cursor));
        }
        return entry;
    }

    /** セッションID（再開で同じ ID を引き継ぐ）。 */
    static String newId() {
        return UUID.randomUUID().toString();
    }

    /** 試験用: 計画ができるまでは null。 */
    Progress progressOf(int chunkX, int chunkZ) {
        return progress.get(Chunk.getChunkKey(chunkX, chunkZ));
    }
}
