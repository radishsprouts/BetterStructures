package com.magmaguy.betterstructures.runtime;

import com.magmaguy.betterstructures.util.PlatformScheduler;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@code World#getChunkAtAsync} の同時実行数を、サーバー全体（既定 8）とワールドごとに制限する。
 * 走査・貼り付け・WFC・事前生成のチャンク読み込みはすべてここを通す。
 *
 * <p>呼び出すスレッド: どのスレッドからでも {@link #load} を呼べる。待たない（上限に当たった依頼は
 * 内部の待ち行列に入り、空きができたら自動で始まる）。
 *
 * <p>完了の受け取り: 返す {@link CompletableFuture} は<b>そのチャンクの所有リージョンのスレッド</b>で完了する。
 * 読み込みの完了コールバックが所有リージョン以外のスレッドで呼ばれても（Paper/Folia のどちらの挙動でも）、
 * 所有していなければ {@code runRegion} で所有リージョンへ回してから完了させる。依存する処理
 * （{@code thenAccept} 等）はそのまま所有リージョンで動くので、リージョンの外のチャンクに同期で触らない。
 * 失敗（例外）は完了したスレッドのまま通知する。
 *
 * <p>同じチャンクの依頼が同時に複数あれば1回の読み込みを共有する。依頼中のチャンクは
 * {@link #isPluginRequested} で分かる（このプラグインが読み込ませたチャンクで構造物の走査を
 * 再び起こさないための判定。03章「再帰の抑止」）。
 */
public final class ChunkLoadLimiter {

    /** チャンクを非同期に読む手段。通常は {@code World#getChunkAtAsync(x, z, generate)}。 */
    @FunctionalInterface
    public interface ChunkSource {
        CompletableFuture<Chunk> load(World world, int chunkX, int chunkZ, boolean generate);
    }

    /** 所有リージョンへの回し先。通常は {@link PlatformScheduler}。 */
    public interface RegionRunner {
        boolean isOwned(World world, int chunkX, int chunkZ);

        void run(World world, int chunkX, int chunkZ, Runnable task);
    }

    private record Request(UUID worldId, long key, boolean generate) {
    }

    private static final class Pending {
        final World world;
        final int chunkX;
        final int chunkZ;
        final Request request;
        final CompletableFuture<Chunk> result = new CompletableFuture<>();

        Pending(World world, int chunkX, int chunkZ, Request request) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.request = request;
        }
    }

    private static volatile ChunkLoadLimiter instance;

    private final ChunkSource source;
    private final RegionRunner regions;
    private final Supplier<FlowSettings> settings;

    private final Object lock = new Object();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final Map<UUID, Integer> activePerWorld = new HashMap<>();
    private int active;
    private final Map<Request, Pending> inFlight = new ConcurrentHashMap<>();

    public ChunkLoadLimiter(ChunkSource source, RegionRunner regions, Supplier<FlowSettings> settings) {
        this.source = source;
        this.regions = regions;
        this.settings = settings;
    }

    /** プラグイン全体で共有する本体（Bukkit・PlatformScheduler を使う）。 */
    public static ChunkLoadLimiter instance() {
        ChunkLoadLimiter local = instance;
        if (local == null) {
            synchronized (ChunkLoadLimiter.class) {
                local = instance;
                if (local == null) {
                    local = new ChunkLoadLimiter(
                            (world, x, z, generate) -> world.getChunkAtAsync(x, z, generate),
                            new RegionRunner() {
                                @Override
                                public boolean isOwned(World world, int chunkX, int chunkZ) {
                                    return PlatformScheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ);
                                }

                                @Override
                                public void run(World world, int chunkX, int chunkZ, Runnable task) {
                                    PlatformScheduler.runRegion(world, chunkX, chunkZ, task);
                                }
                            },
                            RuntimeSettings::get);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 停止時に呼ぶ。共有の本体を手放す（待ち行列の依頼は例外で終える）。 */
    public static void shutdown() {
        ChunkLoadLimiter local = instance;
        instance = null;
        if (local != null) local.cancelQueued();
    }

    /**
     * チャンクを読み込む（上限の内側で）。
     *
     * @param generate 未生成なら生成するか。{@code false} は生成済みのチャンクを読むだけ
     *                 （積み残しの再読み込みはこちら）
     * @return 所有リージョンのスレッドで完了する future。待たずに {@code thenAccept} でつなぐこと
     */
    public CompletableFuture<Chunk> load(World world, int chunkX, int chunkZ, boolean generate) {
        Request request = new Request(world.getUID(), ChunkKeys.key(chunkX, chunkZ), generate);
        Pending created = new Pending(world, chunkX, chunkZ, request);
        Pending existing = inFlight.putIfAbsent(request, created);
        if (existing != null) return existing.result;
        synchronized (lock) {
            queue.add(created);
        }
        pump();
        return created.result;
    }

    /** このプラグインが読み込みを依頼中のチャンクか（読み込みイベントの再帰を避けるため）。 */
    public boolean isPluginRequested(World world, int chunkX, int chunkZ) {
        if (inFlight.isEmpty()) return false;
        long key = ChunkKeys.key(chunkX, chunkZ);
        UUID id = world.getUID();
        return inFlight.containsKey(new Request(id, key, true)) || inFlight.containsKey(new Request(id, key, false));
    }

    /** 実行中の読み込みの数。 */
    public int activeCount() {
        synchronized (lock) {
            return active;
        }
    }

    /** 待っている依頼の数。 */
    public int queuedCount() {
        synchronized (lock) {
            return queue.size();
        }
    }

    private void pump() {
        while (true) {
            Pending next = takeNext();
            if (next == null) return;
            dispatch(next);
        }
    }

    /** 上限に空きがあれば、先頭から数えて最初に始められる依頼を1件取り出す。 */
    private Pending takeNext() {
        FlowSettings s = settings.get();
        synchronized (lock) {
            if (active >= s.chunkLoadLimit()) return null;
            Iterator<Pending> it = queue.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                int perWorld = activePerWorld.getOrDefault(p.request.worldId(), 0);
                if (perWorld >= s.chunkLoadLimitPerWorld()) continue;
                it.remove();
                active++;
                activePerWorld.merge(p.request.worldId(), 1, Integer::sum);
                return p;
            }
            return null;
        }
    }

    private void dispatch(Pending p) {
        CompletableFuture<Chunk> future;
        try {
            future = source.load(p.world, p.chunkX, p.chunkZ, p.request.generate());
        } catch (Throwable t) {
            finish(p, null, t);
            return;
        }
        future.whenComplete((chunk, error) -> finish(p, chunk, error));
    }

    private void finish(Pending p, Chunk chunk, Throwable error) {
        synchronized (lock) {
            active--;
            activePerWorld.computeIfPresent(p.request.worldId(), (id, n) -> n <= 1 ? null : n - 1);
        }
        // 先に空きを回してから、利用者へ渡す（利用者の処理が重くても次の読み込みを止めない）。
        pump();
        if (error != null) {
            inFlight.remove(p.request);
            p.result.completeExceptionally(error);
            return;
        }
        Runnable complete = () -> {
            inFlight.remove(p.request);
            p.result.complete(chunk);
        };
        if (regions.isOwned(p.world, p.chunkX, p.chunkZ)) {
            complete.run();
        } else {
            regions.run(p.world, p.chunkX, p.chunkZ, complete);
        }
    }

    private void cancelQueued() {
        java.util.List<Pending> dropped;
        synchronized (lock) {
            dropped = new java.util.ArrayList<>(queue);
            queue.clear();
        }
        for (Pending p : dropped) {
            inFlight.remove(p.request);
            p.result.completeExceptionally(new java.util.concurrent.CancellationException("プラグインの停止で読み込みを取りやめました"));
        }
    }
}
