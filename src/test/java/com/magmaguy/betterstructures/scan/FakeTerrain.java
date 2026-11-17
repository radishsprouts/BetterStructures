package com.magmaguy.betterstructures.scan;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.SplittableRandom;

/**
 * 疑似乱数で作る地形の偽物。同じ種からは同じ地形になり、どのスレッドからも読める。
 * 地表（草・木・水・崖）、洞窟、ネザー（溶岩の海・島・天井）を含む。
 */
final class FakeTerrain implements BlockSource {
    static final int MIN_Y = -64;
    static final int MAX_Y = 320;

    private final long seed;
    private final World.Environment environment;
    private final int seaLevel;
    private final int roughness;

    FakeTerrain(long seed, World.Environment environment) {
        this.seed = seed;
        this.environment = environment;
        SplittableRandom parameters = new SplittableRandom(seed);
        this.seaLevel = 55 + parameters.nextInt(15);
        this.roughness = 1 + parameters.nextInt(12);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    private long hash(int x, int y, int z, int salt) {
        long h = seed * 31 + salt;
        h = mix(h ^ (x * 0x9E3779B97F4A7C15L));
        h = mix(h ^ (y * 0xC2B2AE3D27D4EB4FL));
        h = mix(h ^ (z * 0x165667B19E3779F9L));
        return h;
    }

    private int surfaceHeight(int x, int z) {
        // 粗い格子の値を補間して滑らかにし、細かい揺らぎと崖を足す。
        int cell = 8;
        int cx = Math.floorDiv(x, cell);
        int cz = Math.floorDiv(z, cell);
        double fx = (x - cx * cell) / (double) cell;
        double fz = (z - cz * cell) / (double) cell;
        double a = corner(cx, cz);
        double b = corner(cx + 1, cz);
        double c = corner(cx, cz + 1);
        double d = corner(cx + 1, cz + 1);
        double top = a + (b - a) * fx;
        double bottom = c + (d - c) * fx;
        double value = top + (bottom - top) * fz;
        int height = (int) Math.round(value);
        if ((hash(x, 0, z, 7) & 63) == 0) height += 6 + (int) (hash(x, 1, z, 8) & 7);
        return height;
    }

    private double corner(int cx, int cz) {
        long h = hash(cx, 0, cz, 3);
        return 64 + ((h >>> 20) % (2 * roughness + 1)) - roughness + ((h >>> 40) % 5 == 0 ? roughness * 2 : 0);
    }

    @Override
    public int minY() {
        return MIN_Y;
    }

    @Override
    public int maxY() {
        return MAX_Y;
    }

    @Override
    public Material getType(int x, int y, int z) {
        if (y < MIN_Y || y >= MAX_Y) return Material.VOID_AIR;
        return switch (environment) {
            case NETHER -> netherType(x, y, z);
            case THE_END -> endType(x, y, z);
            default -> overworldType(x, y, z);
        };
    }

    private Material overworldType(int x, int y, int z) {
        int height = surfaceHeight(x, z);
        if (y <= height) {
            if (y == MIN_Y) return Material.BEDROCK;
            if (y < height - 4 && y > 0 && (hash(x, y, z, 11) & 31) == 0) return Material.AIR;
            if (y < height - 4 && y > -40 && (hash(x >> 2, y >> 2, z >> 2, 12) & 15) == 0) return Material.AIR;
            if (y == height) return height < seaLevel ? Material.SAND : Material.GRASS_BLOCK;
            return y > height - 4 ? Material.DIRT : Material.STONE;
        }
        if (y <= seaLevel) return Material.WATER;
        if (y == height + 1) {
            long h = hash(x, 0, z, 13) & 31;
            if (h == 0) return Material.OAK_LOG;
            if (h == 1) return Material.TALL_GRASS;
            if (h == 2) return Material.SNOW;
        }
        if (y <= height + 4 && (hash(x, 0, z, 14) & 127) == 0) return y <= height + 2 ? Material.OAK_LOG : Material.OAK_LEAVES;
        // 空中の小島
        if (y > 120 && y < 126 && (hash(x >> 3, 0, z >> 3, 15) & 3) == 0) return Material.STONE;
        return Material.AIR;
    }

    private Material netherType(int x, int y, int z) {
        if (y <= 2 || y >= 127) return Material.BEDROCK;
        int ground = 20 + (int) (hash(x >> 3, 0, z >> 3, 21) % 14) + (surfaceHeight(x, z) % 9);
        int roof = 110 + (int) (hash(x >> 3, 1, z >> 3, 22) % 12);
        if (y >= roof) return Material.NETHERRACK;
        if (y <= ground) return Material.NETHERRACK;
        if (y <= 31) return Material.LAVA;
        // 島と柱
        long island = hash(x >> 4, 0, z >> 4, 23) & 7;
        if (island < 3) {
            int top = 32 + (int) (hash(x >> 4, 2, z >> 4, 24) % 40);
            if (y <= top) return (hash(x, y, z, 25) & 15) == 0 ? Material.AIR : Material.NETHERRACK;
            if (y == top + 1 && (hash(x, 0, z, 26) & 7) == 0) return Material.CRIMSON_ROOTS;
        }
        if ((hash(x >> 2, y >> 2, z >> 2, 27) & 31) == 0) return Material.NETHERRACK;
        return Material.AIR;
    }

    private Material endType(int x, int y, int z) {
        long island = hash(x >> 5, 0, z >> 5, 31) & 3;
        if (island == 0) {
            int height = 50 + surfaceHeight(x, z) % 10;
            if (y <= height && y >= height - 30) return Material.END_STONE;
        }
        return Material.AIR;
    }

    @Override
    public int getHighestBlockY(int x, int z) {
        for (int y = MAX_Y - 1; y >= MIN_Y; y--) {
            Material type = getType(x, y, z);
            if (type.isAir()) continue;
            if (type.isSolid() || type == Material.WATER || type == Material.LAVA) return y;
        }
        return MIN_Y - 1;
    }

    @Override
    public Biome getBiome(int x, int y, int z) {
        return null;
    }
}
