package com.magmaguy.betterstructures.runtime;

/**
 * チャンク座標の鍵。{@code org.bukkit.Chunk#getChunkKey(int, int)} と同じ式 {@code (x & 0xFFFFFFFF) | (z << 32)} に統一する。
 * 以前は {@code (x<<32)|z}（TerrainReads）と混在していた。Bukkit に依存しないので、テストでも使える。
 */
public final class ChunkKeys {
    private ChunkKeys() {
    }

    public static long key(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }

    public static int x(long key) {
        return (int) key;
    }

    public static int z(long key) {
        return (int) (key >> 32);
    }
}
