package com.magmaguy.betterstructures.util;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.api.BuildPasteCompleteEvent;
import com.magmaguy.betterstructures.api.BuildPlaceEvent;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.runtime.ChunkKeys;
import com.magmaguy.betterstructures.runtime.ChunkLoadLimiter;
import com.magmaguy.betterstructures.runtime.FlowSettings;
import com.magmaguy.betterstructures.runtime.GenerationGovernor;
import com.magmaguy.betterstructures.runtime.RuntimeSettings;
import com.magmaguy.betterstructures.runtime.ThreadTickBudget;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * チャンクの事前生成（{@code /bs pregenerate}）。中心から外へ、1周（リング）ずつチャンクを読み込んで生成する。
 *
 * <p>流量: 読み込みはすべて {@link ChunkLoadLimiter} を通す（走査・貼り付け・ダンジョンと同じ上限を共有する）。
 * 同時に飛ばす数は {@code chunkLoadLimit × percentageOfTickUsedForPregeneration ÷ percentageOfTickUsedForPasting}
 * （最低 1、{@code chunkLoadLimit} とワールドごとの上限が頭打ち。本番の 0.1 と 0.2 なら半分）。
 * 呼び出しは「飛ばす」だけで待たない。重い仕事は読み込み側（サーバーのチャンク処理）が行う。
 *
 * <p>一時停止（実行中の読み込みは止められないので、新しく飛ばすのを止める。待ち行列は捨てずに再開で続ける）:
 * <ul>
 *   <li>tick の遅れ検出: グローバルリージョンのタイマーが予約した時刻より {@code lagThresholdMillis} 以上遅れて動いた</li>
 *   <li>{@link GenerationGovernor} の待ち行列（走査・貼り付け・ダンジョン）の深さがしきい値を超えた</li>
 *   <li>TPS が {@code pregenerationTPSPauseThreshold} を下回った（Paper 系のみ。Folia は全体の TPS が無いので使わない）</li>
 * </ul>
 *
 * <p>構造物の走査: 事前生成が生成したチャンクで構造物を出すのは、通常の新チャンクと同じ
 * {@code NewChunkLoadEvent} の流れ（このクラスは走査しない）。{@link ChunkLoadLimiter#isPluginRequested} が
 * 立つ読み込みは走査を起こさない決まりなので、事前生成の読み込みだけは {@link #isPregenerationRequested} で
 * 走査の対象に戻す必要がある（U1 側の判定で使う）。
 *
 * <p>スレッド: 駆動はグローバルリージョンの1tick周期のタイマー {@link #pump()} 1本。読み込みの完了は
 * 所有リージョンのスレッドで届き、数えるだけ（アトミック）。待たない・ロックしない。
 * 停止・取り消しはタスクの取り消し（{@link PlatformScheduler#cancelAllTasks()} のライフサイクル世代を含む）と
 * {@link #shutdown()} の両方で漏れなく止まる。
 */
public class ChunkPregenerator implements Listener {
    private static final Set<ChunkPregenerator> activePregenerators = ConcurrentHashMap.newKeySet();

    /** Governor の待ち行列（走査・貼り付け・ダンジョンの合計）がこれを超えたら新しい読み込みを止める。 */
    static final int BACKLOG_PAUSE = 64;
    /** 止めた後、待ち行列がこれ以下に減ったら再開する（止めたり動かしたりの往復を避ける）。 */
    static final int BACKLOG_RESUME = 16;
    /** 遅れを検出したら、遅れの無い状態がこの tick 数続くまで止める。 */
    static final int LAG_COOLDOWN_TICKS = 20;
    /** 読み込みイベントの数え漏れを避けるため、完了したチャンクをこの tick 数だけ追跡に残す。 */
    private static final int TRACK_GRACE_TICKS = 40;
    private static final int TPS_SAMPLE_TICKS = 40;
    private static final int MAX_ATTEMPTS = 3;

    @Getter
    private final World world;
    private final Location center;
    private final String shape;
    private final boolean setWorldBorder;
    private final double tickUsage;
    private final int maxRadiusBlocks;
    private final int maxRadiusChunks;

    // --- pump のスレッド（グローバルリージョン）だけが触る ---
    private int centerChunkX;
    private int centerChunkZ;
    private int nextRadius = 0;
    private volatile int currentRadius = 0;
    private long[] layer = new long[0];
    private int layerIndex = 0;
    private long currentKey;
    private long tick = 0;
    private long expectedPumpNanos;
    private final ArrayDeque<long[]> expiring = new ArrayDeque<>();
    private final Pacer pacer = new Pacer();
    private volatile Pacer.Reason pauseReason = Pacer.Reason.NONE;
    private volatile double lastTps = Double.NaN;
    private boolean calculatedExpected = false;

    // --- 読み込み完了・イベントのスレッド（各リージョン）からも触る ---
    private final Set<Long> tracked = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Long> completedKeys = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> retryKeys = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, Integer> attempts = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong completedChunks = new AtomicLong();
    private final AtomicLong completedLast30s = new AtomicLong();
    private final AtomicLong failedChunks = new AtomicLong();
    private final AtomicLong newlyGeneratedChunks = new AtomicLong();
    private final AtomicLong newChunksLast30s = new AtomicLong();
    // 置いた数は貼り付けのために積まれた構造物、貼り付けた数は貼り付けが終わったもの。
    private final AtomicLong structuresPlacedTotal = new AtomicLong();
    private final AtomicLong structuresPastedTotal = new AtomicLong();
    private final AtomicLong structuresPastedLast30s = new AtomicLong();
    private volatile Integer expectedChunksTotal = null;

    private volatile PlatformScheduler.TaskHandle pumpTask;
    private volatile PlatformScheduler.TaskHandle statsTask;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile boolean isCancelled = false;

    public static Set<ChunkPregenerator> getActivePregenerators() {
        return Set.copyOf(activePregenerators);
    }

    /** 停止時に呼ぶ。スケジューラを使わず（プラグインが止まる途中でも）その場で全部止める。 */
    public static void shutdown() {
        for (ChunkPregenerator pregenerator : Set.copyOf(activePregenerators)) {
            pregenerator.cancel();
        }
        activePregenerators.clear();
    }

    /**
     * 事前生成がいま読み込ませている（完了直後を含む）チャンクか。{@code NewChunkLoadEvent} の
     * 走査の抑止（{@link ChunkLoadLimiter#isPluginRequested} が立つ読み込みは走査しない）から、
     * 事前生成の読み込みだけを外すために使う。どのスレッドからでも呼べる。
     */
    public static boolean isPregenerationRequested(World world, int chunkX, int chunkZ) {
        if (activePregenerators.isEmpty()) return false;
        long key = ChunkKeys.key(chunkX, chunkZ);
        for (ChunkPregenerator pregenerator : activePregenerators) {
            if (pregenerator.world.equals(world) && pregenerator.tracked.contains(key)) return true;
        }
        return false;
    }

    public ChunkPregenerator(World world, Location center, String shape, int maxRadiusBlocks, int maxRadiusChunks, boolean setWorldBorder) {
        this.world = world;
        this.center = center;
        this.shape = shape;
        this.maxRadiusBlocks = maxRadiusBlocks;
        this.maxRadiusChunks = maxRadiusChunks;
        this.setWorldBorder = setWorldBorder;
        this.tickUsage = DefaultConfig.getPercentageOfTickUsedForPregeneration();

        // 実際に出た構造物を数えるため、イベントを受ける
        Bukkit.getPluginManager().registerEvents(this, MetadataHandler.PLUGIN);
    }

    public void start() {
        centerChunkX = center.getBlockX() >> 4;
        centerChunkZ = center.getBlockZ() >> 4;

        if (!"SQUARE".equalsIgnoreCase(shape) && !"CIRCLE".equalsIgnoreCase(shape)) {
            Logger.warn("事前生成の形が不正です: " + shape + "（SQUARE か CIRCLE）");
            cleanup();
            return;
        }

        Logger.info("チャンクの事前生成を開始します。形: " + shape + "、中心チャンク: (" + centerChunkX + ", " + centerChunkZ
                + ")、半径: " + maxRadiusBlocks + " ブロック (" + maxRadiusChunks + " チャンク)");

        activePregenerators.add(this);
        expectedPumpNanos = ThreadTickBudget.expectedRunNanos(1L);
        // 1tick 周期の駆動と、30秒ごとの統計。どちらもライフサイクル世代に紐づき、停止で取り消される。
        pumpTask = PlatformScheduler.runGlobalTimer(this::pump, 1L, 1L);
        statsTask = PlatformScheduler.runGlobalTimer(this::reportStats, 600L, 600L);
    }

    // ---------------------------------------------------------------- 駆動

    private void pump() {
        if (finished.get()) return;
        if (isCancelled) {
            finishCancelled();
            return;
        }
        tick++;
        FlowSettings settings = RuntimeSettings.get();

        // tick の遅れ: 予約した時刻と実際に動いた時刻の差。ThreadTickBudget の遅れ検出と同じ物差し。
        long lagNanos = ThreadTickBudget.current().observeLag(expectedPumpNanos);
        expectedPumpNanos = ThreadTickBudget.expectedRunNanos(1L);

        if (tick % TPS_SAMPLE_TICKS == 1) lastTps = readTps();
        int backlog = governorBacklog();

        Pacer.Reason reason = pacer.evaluate(tick, lagNanos, settings.lagThresholdNanos(), backlog, lastTps,
                DefaultConfig.getPregenerationTPSPauseThreshold(), DefaultConfig.getPregenerationTPSResumeThreshold());
        reportPauseTransition(reason, backlog, lagNanos);
        pauseReason = reason;

        releaseTracked();

        if (reason == Pacer.Reason.NONE) {
            dispatch(inFlightCap(settings, tickUsage));
        }

        if (isDrained()) onComplete();
    }

    private void reportPauseTransition(Pacer.Reason reason, int backlog, long lagNanos) {
        Pacer.Reason before = pauseReason;
        if (before == reason) return;
        if (before == Pacer.Reason.NONE) {
            Logger.warn("チャンクの事前生成を一時停止します（" + describe(reason) + "。生成待ち " + backlog
                    + "、遅れ " + (lagNanos / 1_000_000L) + "ms、TPS " + formatTps(lastTps) + "）");
        } else if (reason == Pacer.Reason.NONE) {
            Logger.info("チャンクの事前生成を再開します（TPS " + formatTps(lastTps) + "、生成待ち " + backlog + "）");
        }
    }

    private static String describe(Pacer.Reason reason) {
        return switch (reason) {
            case LAG -> "tick の遅れを検出";
            case BACKLOG -> "構造物の生成待ちが多い";
            case TPS -> "TPS がしきい値を下回った";
            case NONE -> "なし";
        };
    }

    private void dispatch(int cap) {
        ChunkLoadLimiter limiter = ChunkLoadLimiter.instance();
        while (inFlight.get() < cap && advance()) {
            long key = currentKey;
            tracked.add(key);
            inFlight.incrementAndGet();
            try {
                limiter.load(world, ChunkKeys.x(key), ChunkKeys.z(key), true)
                        .whenComplete((chunk, error) -> onLoaded(key, chunk != null, error));
            } catch (Throwable t) {
                onLoaded(key, false, t);
            }
        }
    }

    /** 次に読むチャンクを {@link #currentKey} に入れる。再試行を先に、次にリングを内側から。無ければ false。 */
    private boolean advance() {
        Long retry = retryKeys.poll();
        if (retry != null) {
            currentKey = retry;
            return true;
        }
        while (layerIndex >= layer.length) {
            if (nextRadius > maxRadiusChunks) return false;
            layer = ringKeys(shape, centerChunkX, centerChunkZ, nextRadius);
            layerIndex = 0;
            currentRadius = nextRadius;
            nextRadius++;
        }
        currentKey = layer[layerIndex++];
        return true;
    }

    private boolean isDrained() {
        return inFlight.get() == 0 && retryKeys.isEmpty() && layerIndex >= layer.length && nextRadius > maxRadiusChunks;
    }

    /** 読み込みの完了（所有リージョンのスレッド）。数えるだけで、待たず、ワールドに触れない。 */
    private void onLoaded(long key, boolean loaded, Throwable error) {
        inFlight.decrementAndGet();
        completedKeys.add(key);
        if (finished.get()) return;
        Throwable cause = unwrap(error);
        if (cause instanceof CancellationException) {
            return; // 停止による取りやめ。止める処理は別の経路が行う。
        }
        if (cause == null && loaded) {
            attempts.remove(key);
            completedChunks.incrementAndGet();
            completedLast30s.incrementAndGet();
            return;
        }
        int tries = attempts.merge(key, 1, Integer::sum);
        String reason = cause != null ? String.valueOf(cause.getMessage()) : "読み込みを断られました";
        if (tries < MAX_ATTEMPTS) {
            retryKeys.add(key);
            return;
        }
        attempts.remove(key);
        failedChunks.incrementAndGet();
        Logger.warn("チャンク (" + ChunkKeys.x(key) + ", " + ChunkKeys.z(key) + ") の生成に失敗しました（" + tries + " 回試行）: " + reason);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while (t != null && t.getCause() != null
                && (t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)) {
            t = t.getCause();
        }
        return t;
    }

    /** 完了済みのチャンクを、猶予（読み込みイベントが遅れて届く分）の後に追跡から外す。 */
    private void releaseTracked() {
        Long key;
        while ((key = completedKeys.poll()) != null) {
            expiring.add(new long[]{key, tick + TRACK_GRACE_TICKS});
        }
        while (!expiring.isEmpty() && expiring.peekFirst()[1] <= tick) {
            long done = expiring.pollFirst()[0];
            // 再試行で再び飛んでいるものは外さない
            if (!retryKeys.contains(done)) tracked.remove(done);
        }
    }

    // ---------------------------------------------------------------- 終了

    private void onComplete() {
        if (!finished.compareAndSet(false, true)) return;
        long failed = failedChunks.get();
        cleanup();
        Logger.info("チャンクの事前生成が完了しました。処理 " + completedChunks.get() + " チャンク、うち新規生成 "
                + newlyGeneratedChunks.get() + " チャンク、半径 " + maxRadiusBlocks + " ブロック (" + currentRadius + " チャンク)");
        if (failed > 0) {
            Logger.warn("生成に失敗したチャンクが " + failed + " 件あります。ワールドボーダーは変更しません。");
            return;
        }
        if (setWorldBorder) {
            setWorldBorder();
        }
    }

    private void finishCancelled() {
        if (!finished.compareAndSet(false, true)) return;
        cleanup();
        Logger.info("チャンクの事前生成を取り消しました。処理 " + completedChunks.get() + " チャンク、うち新規生成 "
                + newlyGeneratedChunks.get() + " チャンク、到達半径 " + (currentRadius * 16) + " ブロック (" + currentRadius + " チャンク)");
    }

    /** どのスレッドからでも呼べる。タスクの取り消し・追跡の解放・リスナーの解除だけを行う。 */
    private void cleanup() {
        PlatformScheduler.TaskHandle pump = pumpTask;
        pumpTask = null;
        if (pump != null) pump.cancel();
        PlatformScheduler.TaskHandle stats = statsTask;
        statsTask = null;
        if (stats != null) stats.cancel();
        tracked.clear();
        retryKeys.clear();
        attempts.clear();
        HandlerList.unregisterAll(this);
        activePregenerators.remove(this);
    }

    /**
     * 事前生成を取り消す。どのスレッドからでも呼べる（グローバルリージョンへ回さず、その場で止める）。
     * 飛ばした読み込みは止められないが、完了しても何も起こさない。
     */
    public void cancel() {
        if (finished.get()) return;
        isCancelled = true;
        finishCancelled();
    }

    // ---------------------------------------------------------------- イベント

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBuildPlace(BuildPlaceEvent event) {
        // 事前生成しているワールドの構造物だけを数える
        if (event.getFitAnything().getLocation().getWorld().equals(world)) {
            structuresPlacedTotal.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBuildPasteComplete(BuildPasteCompleteEvent event) {
        if (world.equals(event.getLowestCorner().getWorld())) {
            structuresPastedTotal.incrementAndGet();
            structuresPastedLast30s.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.getWorld().equals(world)) return;
        // 事前生成が読み込ませたチャンクの、新規生成だけを数える
        if (event.isNewChunk() && tracked.contains(ChunkKeys.key(event.getChunk().getX(), event.getChunk().getZ()))) {
            newlyGeneratedChunks.incrementAndGet();
            newChunksLast30s.incrementAndGet();
        }
    }

    // ---------------------------------------------------------------- 統計

    private void reportStats() {
        if (finished.get()) return;
        long processed = completedChunks.get();
        if (!calculatedExpected) {
            calculatedExpected = true;
            expectedChunksTotal = calculateExpectedChunksTotal();
        }
        Integer expected = expectedChunksTotal;
        long completed30 = completedLast30s.getAndSet(0);
        long newChunks30 = newChunksLast30s.getAndSet(0);
        long pasted30 = structuresPastedLast30s.getAndSet(0);
        String estimatedTimeLeft = expected == null ? null : calculateEstimatedTimeLeft(processed, expected, completed30);
        Pacer.Reason reason = pauseReason;

        Logger.info("=== 事前生成の状況 ===");
        if (reason != Pacer.Reason.NONE) {
            Logger.info("状態: 一時停止中（" + describe(reason) + "）");
        }
        Logger.info("処理したチャンク: " + processed + " / " + expected + "（読み込み中 " + inFlight.get() + "、失敗 " + failedChunks.get() + "）");
        Logger.info("新規に生成したチャンク（合計）: " + newlyGeneratedChunks.get());
        Logger.info("新規に生成したチャンク（直近30秒）: " + newChunks30);
        Logger.info("現在の TPS: " + formatTps(lastTps));
        Logger.info("構造物の生成待ち（走査・貼り付け・ダンジョン）: " + governorBacklog());
        Logger.info("設定:");
        Logger.info("  - 形: " + shape);
        Logger.info("  - 1tick の使用割合: " + String.format("%.1f", tickUsage * 100) + "%");
        Logger.info("  - 目標の半径: " + maxRadiusBlocks + " ブロック (" + maxRadiusChunks + " チャンク)");
        Logger.info("  - 現在の半径: " + currentRadius + " / " + maxRadiusChunks + " (" + (currentRadius * 16) + " / " + maxRadiusBlocks + " ブロック)");
        Logger.info("構造物を貼り付けた数（合計）: " + structuresPastedTotal.get());
        Logger.info("構造物を貼り付けた数（直近30秒）: " + pasted30);
        Logger.info("貼り付け待ちの構造物: " + Math.max(0, structuresPlacedTotal.get() - structuresPastedTotal.get()));
        if (estimatedTimeLeft != null) {
            Logger.info("残り時間の目安: " + estimatedTimeLeft);
        }
        Logger.info("=====================");
    }

    private String calculateEstimatedTimeLeft(long chunksProcessed, long expectedTotal, long chunksLast30s) {
        if (chunksLast30s == 0 || expectedTotal == 0) return null;
        long remaining = expectedTotal - chunksProcessed;
        if (remaining <= 0) return "まもなく完了";
        double perSecond = chunksLast30s / 30.0;
        return formatTime(Math.round(remaining / perSecond));
    }

    private static String formatTime(long seconds) {
        if (seconds < 60) return seconds + " 秒";
        if (seconds < 3600) {
            long minutes = seconds / 60;
            long rest = seconds % 60;
            return rest == 0 ? minutes + " 分" : minutes + " 分 " + rest + " 秒";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return minutes == 0 ? hours + " 時間" : hours + " 時間 " + minutes + " 分";
    }

    private static String formatTps(double tps) {
        return Double.isNaN(tps) ? "不明" : String.format("%.2f", tps);
    }

    private int calculateExpectedChunksTotal() {
        if ("SQUARE".equalsIgnoreCase(shape)) {
            long n = 2L * maxRadiusChunks + 1;
            return (int) Math.min(Integer.MAX_VALUE, n * n);
        } else if ("CIRCLE".equalsIgnoreCase(shape)) {
            long lim = (long) (maxRadiusChunks + 1) * (maxRadiusChunks + 1);
            long total = 0;
            for (int dx = -maxRadiusChunks; dx <= maxRadiusChunks; dx++) {
                long rem = lim - 1 - (long) dx * dx;
                total += 2L * floorSqrt(rem) + 1;
            }
            return (int) Math.min(Integer.MAX_VALUE, total);
        }
        return 0;
    }

    // ---------------------------------------------------------------- 流量・TPS

    /** Governor の待ち行列の深さ（走査・貼り付け・ダンジョンの待ちの合計）。 */
    private static int governorBacklog() {
        GenerationGovernor governor = GenerationGovernor.instance();
        return governor.waiting(GenerationGovernor.Lane.SCAN)
                + governor.waiting(GenerationGovernor.Lane.PASTE)
                + governor.waiting(GenerationGovernor.Lane.WFC);
    }

    /**
     * 同時に飛ばす読み込みの数。{@code chunkLoadLimit × 事前生成の割合 ÷ 貼り付けの割合} を
     * 1 以上、サーバー全体とワールドごとの上限以下に収める。
     */
    static int inFlightCap(FlowSettings settings, double pregenerationFraction) {
        int hardLimit = Math.max(1, Math.min(settings.chunkLoadLimit(), settings.chunkLoadLimitPerWorld()));
        double fraction = Double.isNaN(pregenerationFraction) ? settings.pasteTickFraction() : pregenerationFraction;
        long scaled = Math.round(settings.chunkLoadLimit() * fraction / settings.pasteTickFraction());
        return (int) Math.max(1L, Math.min(hardLimit, scaled));
    }

    /**
     * サーバーの TPS。Paper 系は公開 API の {@code Server#getTPS()}（直近1分）を型付きで呼ぶ。
     * Folia はサーバー全体の TPS が無く（リージョンごとに tick する）、戻り値の意味を確かめられていないので
     * 呼ばず、遅れ検出だけで判断する（NaN を返す）。取れない時も NaN。
     */
    static double readTps() {
        if (PlatformScheduler.isFolia()) return Double.NaN;
        try {
            double[] tps = Bukkit.getServer().getTPS();
            if (tps != null && tps.length > 0) {
                return Math.min(20.0, Math.max(0.0, tps[0]));
            }
        } catch (Throwable ignored) {
            // TPS を取れない環境。遅れ検出と待ち行列の深さで判断する。
        }
        return Double.NaN;
    }

    /** 一時停止の判定（状態を持つだけで、Bukkit に触れない。試験できる）。 */
    static final class Pacer {
        enum Reason {NONE, LAG, BACKLOG, TPS}

        private boolean backlogPaused;
        private boolean tpsPaused;
        private long lagUntilTick = Long.MIN_VALUE;

        /**
         * @param tick           何回目の駆動か
         * @param lagNanos       予約した時刻からの遅れ
         * @param backlog        Governor の待ち行列の深さ
         * @param tps            TPS。取れない時は NaN（TPS では判断しない）
         */
        Reason evaluate(long tick, long lagNanos, long lagThresholdNanos, int backlog, double tps,
                        double tpsPauseThreshold, double tpsResumeThreshold) {
            if (lagNanos > lagThresholdNanos) lagUntilTick = tick + LAG_COOLDOWN_TICKS;

            if (backlogPaused) {
                if (backlog <= BACKLOG_RESUME) backlogPaused = false;
            } else if (backlog > BACKLOG_PAUSE) {
                backlogPaused = true;
            }

            if (Double.isNaN(tps)) {
                tpsPaused = false;
            } else if (tpsPaused) {
                if (tps >= tpsResumeThreshold) tpsPaused = false;
            } else if (tps < tpsPauseThreshold) {
                tpsPaused = true;
            }

            if (tick < lagUntilTick) return Reason.LAG;
            if (tpsPaused) return Reason.TPS;
            if (backlogPaused) return Reason.BACKLOG;
            return Reason.NONE;
        }
    }

    // ---------------------------------------------------------------- リングの列挙

    /** 中心から半径 {@code radius} のリング（その半径にだけ入るチャンク）の鍵。形が不正なら空。 */
    static long[] ringKeys(String shape, int centerX, int centerZ, int radius) {
        LongBuffer out = new LongBuffer();
        if ("SQUARE".equalsIgnoreCase(shape)) {
            if (radius == 0) {
                out.add(ChunkKeys.key(centerX, centerZ));
                return out.toArray();
            }
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                out.add(ChunkKeys.key(x, centerZ - radius));
                out.add(ChunkKeys.key(x, centerZ + radius));
            }
            for (int z = centerZ - radius + 1; z < centerZ + radius; z++) {
                out.add(ChunkKeys.key(centerX - radius, z));
                out.add(ChunkKeys.key(centerX + radius, z));
            }
        } else if ("CIRCLE".equalsIgnoreCase(shape)) {
            // 全体を毎回走査せずリングを直接たどる。半径 r のリングは r^2 <= dx^2 + dz^2 < (r+1)^2。
            // dx を固定すると、メンバーは minDz <= |dz| <= maxDz の連続した帯になる。
            long upperSquared = (long) (radius + 1) * (radius + 1) - 1;
            long radiusSquared = (long) radius * radius;
            for (int dx = -radius; dx <= radius; dx++) {
                long dxSquared = (long) dx * dx;
                int maxDz = floorSqrt(upperSquared - dxSquared);
                long minDzSquared = radiusSquared - dxSquared;
                int minDz = minDzSquared <= 0 ? 0 : ceilSqrt(minDzSquared);
                for (int dz = minDz; dz <= maxDz; dz++) {
                    out.add(ChunkKeys.key(centerX + dx, centerZ + dz));
                    if (dz != 0) out.add(ChunkKeys.key(centerX + dx, centerZ - dz));
                }
            }
        }
        return out.toArray();
    }

    private static final class LongBuffer {
        private long[] data = new long[16];
        private int size;

        void add(long value) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = value;
        }

        long[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    private static int floorSqrt(long value) {
        if (value < 0) return -1;
        int result = (int) Math.sqrt(value);
        while ((long) (result + 1) * (result + 1) <= value) result++;
        while ((long) result * result > value) result--;
        return result;
    }

    private static int ceilSqrt(long value) {
        int floor = floorSqrt(value);
        return (long) floor * floor == value ? floor : floor + 1;
    }

    private void setWorldBorder() {
        try {
            // ワールドボーダーの大きさは直径なので、半径の2倍
            double borderSize = maxRadiusBlocks * 2.0;

            world.getWorldBorder().setCenter(center.getX(), center.getZ());
            world.getWorldBorder().setSize(borderSize);

            Logger.info("ワールドボーダーを設定しました。大きさ: " + borderSize + " ブロック（半径 " + maxRadiusBlocks + "）、中心: ("
                    + center.getX() + ", " + center.getZ() + ")");
        } catch (Exception e) {
            Logger.warn("ワールドボーダーの設定に失敗しました: " + e.getMessage());
        }
    }
}
