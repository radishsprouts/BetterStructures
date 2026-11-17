package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.BetterStructures;
import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.runtime.ChunkLoadLimiter;
import com.magmaguy.betterstructures.runtime.ThreadTickBudget;
import com.magmaguy.betterstructures.runtime.WorkerPool;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * {@link ScanPorts} の本番の実装。チャンクは {@link ChunkLoadLimiter} で上限の内側で読み、読み込みの完了で
 * すぐ hold して（千切れ防止）、所有リージョンで {@link ThreadTickBudget} の範囲だけスナップショットを取る。
 * 予算が無ければ 1 tick 後に同じリージョンでやり直す（捨てない）。
 */
final class BukkitScanPorts implements ScanPorts {
    private final World world;
    private final long reloadGeneration;
    private final PluginChunkHolds.HoldSet holds;

    BukkitScanPorts(World world, long reloadGeneration) {
        this.world = world;
        this.reloadGeneration = reloadGeneration;
        this.holds = new PluginChunkHolds.HoldSet(world);
    }

    @Override
    public String worldName() {
        return world.getName();
    }

    @Override
    public World.Environment environment() {
        return world.getEnvironment();
    }

    @Override
    public int minY() {
        return world.getMinHeight();
    }

    @Override
    public int maxY() {
        return world.getMaxHeight();
    }

    @Override
    public CompletableFuture<ChunkData> acquire(int chunkX, int chunkZ) {
        CompletableFuture<ChunkData> result = new CompletableFuture<>();
        try {
            ChunkLoadLimiter.instance().load(world, chunkX, chunkZ, true).whenComplete((chunk, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                    return;
                }
                // 通常は所有リージョンで完了するが、すでに完了していた場合は呼び出したスレッドで動くので、
                // 所有を確かめて、違えば所有リージョンへ回す。
                onRegion(chunkX, chunkZ, () -> {
                    // 読み込みの直後に hold する。スナップショットを予算待ちで遅らせている間に外れないように。
                    holds.hold(chunkX, chunkZ);
                    snapshot(chunkX, chunkZ, chunk, 0L, result);
                });
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private void snapshot(int chunkX, int chunkZ, Chunk chunk, long expectedRunNanos, CompletableFuture<ChunkData> result) {
        try {
            ThreadTickBudget budget = ThreadTickBudget.current();
            if (expectedRunNanos != 0L) budget.observeLag(expectedRunNanos);
            if (!budget.hasBudget()) {
                long expected = ThreadTickBudget.expectedRunNanos(1);
                PlatformScheduler.runRegionLater(world, chunkX, chunkZ,
                        () -> snapshot(chunkX, chunkZ, chunk, expected, result), 1L);
                return;
            }
            long started = budget.now();
            // 読み込み済み（hold している）ので、同期の読み込みにはならない。
            Chunk loaded = chunk.isLoaded() ? chunk : world.getChunkAt(chunkX, chunkZ);
            ChunkData data = new SnapshotChunkData(loaded.getChunkSnapshot(true, true, false),
                    world.getMinHeight(), world.getMaxHeight());
            budget.consumeSince(started);
            result.complete(data);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private void onRegion(int chunkX, int chunkZ, Runnable task) {
        if (PlatformScheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            task.run();
        } else {
            PlatformScheduler.runRegion(world, chunkX, chunkZ, task);
        }
    }

    @Override
    public void worker(Runnable task) {
        WorkerPool.instance().execute(task);
    }

    @Override
    public boolean stale() {
        return !PlatformScheduler.isCurrentReloadGeneration(reloadGeneration)
                || BetterStructures.isReloading()
                || MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled();
    }

    @Override
    public PluginChunkHolds.HoldSet holds() {
        return holds;
    }
}
