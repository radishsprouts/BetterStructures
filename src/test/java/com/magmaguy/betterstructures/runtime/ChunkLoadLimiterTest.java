package com.magmaguy.betterstructures.runtime;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * チャンク読み込みの同時実行数の上限と、所有リージョンでの完了の受け取り。
 * 読み込みの手段と所有リージョンへの回し先を差し替えて確かめる（サーバーは起動しない）。
 */
final class ChunkLoadLimiterTest {
    private final World worldA = world();
    private final World worldB = world();

    /** 読み込みの依頼の記録。完了は試験が手で行う。 */
    private final List<String> requested = new ArrayList<>();
    private final Map<String, CompletableFuture<Chunk>> pending = new HashMap<>();
    private boolean owned = true;
    private final List<Runnable> regionQueue = new ArrayList<>();

    private static World world() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        return world;
    }

    private ChunkLoadLimiter limiter(int global, int perWorld) {
        FlowSettings settings = new FlowSettings(4, 2, 1, global, perWorld, 0.2, 0.5, 0.25, 0.5, 100.0, 4, false, 60);
        return new ChunkLoadLimiter(
                (world, x, z, generate) -> {
                    String id = name(world, x, z);
                    requested.add(id);
                    CompletableFuture<Chunk> future = new CompletableFuture<>();
                    pending.put(id, future);
                    return future;
                },
                new ChunkLoadLimiter.RegionRunner() {
                    @Override
                    public boolean isOwned(World world, int chunkX, int chunkZ) {
                        return owned;
                    }

                    @Override
                    public void run(World world, int chunkX, int chunkZ, Runnable task) {
                        regionQueue.add(task);
                    }
                },
                () -> settings);
    }

    private String name(World world, int x, int z) {
        return (world == worldA ? "A" : "B") + ":" + x + "," + z;
    }

    private void finishLoad(World world, int x, int z) {
        pending.get(name(world, x, z)).complete(mock(Chunk.class));
    }

    @Test
    void 全体の上限を超えた依頼は待ち空きができたら始まる() {
        ChunkLoadLimiter limiter = limiter(2, 8);
        for (int x = 0; x < 4; x++) limiter.load(worldA, x, 0, true);

        assertEquals(List.of("A:0,0", "A:1,0"), requested);
        assertEquals(2, limiter.activeCount());
        assertEquals(2, limiter.queuedCount());

        finishLoad(worldA, 0, 0);
        assertEquals(List.of("A:0,0", "A:1,0", "A:2,0"), requested, "空いた分だけ古い順に始まる");
        finishLoad(worldA, 1, 0);
        finishLoad(worldA, 2, 0);
        finishLoad(worldA, 3, 0);
        assertEquals(0, limiter.activeCount());
        assertEquals(0, limiter.queuedCount());
    }

    @Test
    void ワールドごとの上限が全体の上限とは別に効く() {
        ChunkLoadLimiter limiter = limiter(8, 1);
        limiter.load(worldA, 0, 0, true);
        limiter.load(worldA, 1, 0, true);
        limiter.load(worldB, 0, 0, true);

        assertEquals(List.of("A:0,0", "B:0,0"), requested, "A の2件目は待ち、B は始まる");
        finishLoad(worldA, 0, 0);
        assertEquals(List.of("A:0,0", "B:0,0", "A:1,0"), requested);
    }

    @Test
    void 同じチャンクの依頼は1回の読み込みを共有する() {
        ChunkLoadLimiter limiter = limiter(8, 8);
        CompletableFuture<Chunk> first = limiter.load(worldA, 3, 4, true);
        CompletableFuture<Chunk> second = limiter.load(worldA, 3, 4, true);

        assertSame(first, second);
        assertEquals(1, requested.size());
    }

    @Test
    void 所有リージョンで完了した読み込みはそのまま通知する() {
        ChunkLoadLimiter limiter = limiter(8, 8);
        CompletableFuture<Chunk> future = limiter.load(worldA, 0, 0, true);
        assertFalse(future.isDone());

        finishLoad(worldA, 0, 0);
        assertTrue(future.isDone());
        assertTrue(regionQueue.isEmpty());
    }

    @Test
    void 所有していないスレッドで完了したら所有リージョンへ回してから通知する() {
        owned = false;
        ChunkLoadLimiter limiter = limiter(8, 8);
        CompletableFuture<Chunk> future = limiter.load(worldA, 0, 0, true);

        finishLoad(worldA, 0, 0);
        assertFalse(future.isDone(), "所有リージョンに回るまでは完了しない");
        assertEquals(1, regionQueue.size());

        regionQueue.get(0).run();
        assertTrue(future.isDone());
    }

    @Test
    void 回し直しを待つ間も枠は先に戻り次の読み込みが始まる() {
        owned = false;
        ChunkLoadLimiter limiter = limiter(1, 8);
        limiter.load(worldA, 0, 0, true);
        limiter.load(worldA, 1, 0, true);

        finishLoad(worldA, 0, 0);
        assertEquals(List.of("A:0,0", "A:1,0"), requested);
    }

    @Test
    void 依頼中のチャンクはisPluginRequestedで分かり完了後は外れる() {
        ChunkLoadLimiter limiter = limiter(8, 8);
        assertFalse(limiter.isPluginRequested(worldA, 5, 5));
        limiter.load(worldA, 5, 5, false);

        assertTrue(limiter.isPluginRequested(worldA, 5, 5));
        assertFalse(limiter.isPluginRequested(worldA, 5, 6));
        assertFalse(limiter.isPluginRequested(worldB, 5, 5));

        finishLoad(worldA, 5, 5);
        assertFalse(limiter.isPluginRequested(worldA, 5, 5));
    }

    @Test
    void 読み込みの失敗は例外で通知し枠を戻す() {
        ChunkLoadLimiter limiter = limiter(1, 8);
        CompletableFuture<Chunk> failing = limiter.load(worldA, 0, 0, true);
        limiter.load(worldA, 1, 0, true);

        pending.get("A:0,0").completeExceptionally(new IllegalStateException("読み込み失敗"));
        assertTrue(failing.isCompletedExceptionally());
        assertEquals(List.of("A:0,0", "A:1,0"), requested, "失敗でも枠が戻って次が始まる");
        assertEquals(1, limiter.activeCount());
    }

    @Test
    void 読み込みの手段が同期で例外を投げても通知して枠を戻す() {
        FlowSettings settings = FlowSettings.defaults();
        ChunkLoadLimiter limiter = new ChunkLoadLimiter(
                (world, x, z, generate) -> {
                    throw new IllegalStateException("ワールドが消えた");
                },
                new ChunkLoadLimiter.RegionRunner() {
                    @Override
                    public boolean isOwned(World world, int chunkX, int chunkZ) {
                        return true;
                    }

                    @Override
                    public void run(World world, int chunkX, int chunkZ, Runnable task) {
                        task.run();
                    }
                },
                () -> settings);

        CompletableFuture<Chunk> future = limiter.load(worldA, 0, 0, true);
        assertTrue(future.isCompletedExceptionally());
        assertEquals(0, limiter.activeCount());
    }
}
