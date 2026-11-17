package com.magmaguy.betterstructures.scan;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

/** {@link ChunkSnapshot} を {@link ChunkData} として見せる。スナップショットは所有リージョンで取る。 */
public final class SnapshotChunkData implements ChunkData {
    private final ChunkSnapshot snapshot;
    private final int minY;
    private final int maxY;

    public SnapshotChunkData(ChunkSnapshot snapshot, int minY, int maxY) {
        this.snapshot = snapshot;
        this.minY = minY;
        this.maxY = maxY;
    }

    @Override
    public Material type(int localX, int y, int localZ) {
        return snapshot.getBlockType(localX, y, localZ);
    }

    @Override
    public boolean containsWater(int localX, int y, int localZ) {
        Material type = snapshot.getBlockType(localX, y, localZ);
        if (type == Material.WATER || type == Material.KELP || type == Material.KELP_PLANT
                || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        if (type.isAir() || type == Material.LAVA) return false;
        BlockData data = snapshot.getBlockData(localX, y, localZ);
        return data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    @Override
    public int highestY(int localX, int localZ) {
        return snapshot.getHighestBlockYAt(localX, localZ);
    }

    @Override
    public Biome biome(int localX, int y, int localZ) {
        int clamped = Math.max(minY, Math.min(maxY - 1, y));
        return snapshot.getBiome(localX, clamped, localZ);
    }
}
