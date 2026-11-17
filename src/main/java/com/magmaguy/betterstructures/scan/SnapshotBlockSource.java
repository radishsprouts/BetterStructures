package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.runtime.ChunkKeys;
import org.bukkit.Material;
import org.bukkit.block.Biome;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * チャンクの鍵 → {@link ChunkData}（本番は ChunkSnapshot）の集まりを {@link BlockSource} として見せる。
 *
 * <p>どのスレッドから読んでもよい（ワールドには触れない）。リージョンのスレッドが
 * {@link #put} で足し、WorkerPool が採点で読む。採点は、足し終えたチャンクの範囲だけを読む。
 *
 * <p>範囲外の Y は {@code World} と同じく {@link Material#VOID_AIR}。取り込んでいないチャンクは
 * 空気として扱い、{@link #missedReads()} に数える（事前に必要なチャンクを足す設計なので、0 であるべき）。
 *
 * <p>列の最高 Y は {@code World#getHighestBlockYAt(x, z)}（MOTION_BLOCKING）と同じ値にする。
 * スナップショットの高さマップは非空気ブロックの最高（WORLD_SURFACE）で、草や花などの
 * 「動きを妨げない」ブロックの分だけ高いことがあるので、そこから下へ、動きを妨げるか流体を含む
 * ブロックが見つかるまで下りて合わせる（高さマップの種類に依らず同じ結果になる）。
 */
public final class SnapshotBlockSource implements BlockSource {
    private final int minY;
    private final int maxY;
    private final ConcurrentHashMap<Long, ChunkData> chunks = new ConcurrentHashMap<>();
    private volatile int missedReads;

    public SnapshotBlockSource(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
    }

    public void put(int chunkX, int chunkZ, ChunkData data) {
        chunks.put(ChunkKeys.key(chunkX, chunkZ), data);
    }

    public boolean has(int chunkX, int chunkZ) {
        return chunks.containsKey(ChunkKeys.key(chunkX, chunkZ));
    }

    /** 取り込んだチャンクの鍵（{@link ChunkKeys} 式）。 */
    public Set<Long> keys() {
        return Set.copyOf(chunks.keySet());
    }

    /** 取り込んでいないチャンクを読もうとした回数。 */
    public int missedReads() {
        return missedReads;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    private ChunkData chunk(int x, int z) {
        ChunkData data = chunks.get(ChunkKeys.key(x >> 4, z >> 4));
        if (data == null) missedReads++;
        return data;
    }

    @Override
    public Material getType(int x, int y, int z) {
        if (y < minY || y >= maxY) return Material.VOID_AIR;
        ChunkData data = chunk(x, z);
        return data == null ? Material.AIR : data.type(x & 15, y, z & 15);
    }

    @Override
    public int getHighestBlockY(int x, int z) {
        ChunkData data = chunk(x, z);
        if (data == null) return minY - 1;
        int lx = x & 15;
        int lz = z & 15;
        int y = Math.min(maxY - 1, data.highestY(lx, lz));
        for (; y >= minY; y--) {
            Material type = data.type(lx, y, lz);
            if (type.isAir()) continue;
            if (type.isSolid() || type == Material.WATER || type == Material.LAVA || data.containsWater(lx, y, lz)) {
                return y;
            }
        }
        return minY - 1;
    }

    @Override
    public Biome getBiome(int x, int y, int z) {
        ChunkData data = chunk(x, z);
        return data == null ? null : data.biome(x & 15, y, z & 15);
    }
}
