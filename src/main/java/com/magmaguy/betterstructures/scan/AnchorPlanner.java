package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.random.RandomGenerator;

/**
 * 原点チャンクの中心列だけを読んで、構造物の仮の位置（anchor）を決める（旧 {@code Fit*Building#scan} の前半）。
 * 純粋な関数で、乱数は引数で受け取る（試験で固定できる）。ワールドにもスレッドにも依らない。
 *
 * <p>読むのは原点チャンクの中心列 (チャンク x*16+8, z*16+8) だけなので、原点チャンクのスナップショット1つで足りる。
 */
public final class AnchorPlanner {
    private AnchorPlanner() {
    }

    /** 仮の位置。 */
    public record Anchor(int x, int y, int z) {
    }

    /**
     * 設定から読む値。範囲の値は {@code DefaultConfig} の同名の項目。
     *
     * @param skyNormalMinAltitude 通常・カスタムワールドの空の構造物の最低の高さ（地表からの加算）
     * @param skyNormalMaxAltitude 同・最高
     * @param skyEndMinAltitude    エンドの空の構造物の最低の高さ
     * @param skyEndMaxAltitude    同・最高
     * @param lowestYNormalCustom  地下の下限（通常・カスタム）
     * @param highestYNormalCustom 地下の上限（同）
     * @param lowestYNether        同（ネザー）
     * @param highestYNether       同
     * @param lowestYEnd           同（エンド）
     * @param highestYEnd          同
     */
    public record Config(int skyNormalMinAltitude, int skyNormalMaxAltitude, int skyEndMinAltitude, int skyEndMaxAltitude,
                         int lowestYNormalCustom, int highestYNormalCustom, int lowestYNether, int highestYNether,
                         int lowestYEnd, int highestYEnd) {
    }

    /**
     * 図面を選ぶ前の仮の位置。置ける見込みが無ければ null。
     *
     * @param origin 原点チャンクを含む地形（中心列が読めること）
     */
    public static Anchor plan(StructureType kind, World.Environment environment, BlockSource origin,
                              int chunkX, int chunkZ, RandomGenerator random, Config config) {
        int x = chunkX * 16 + 8;
        int z = chunkZ * 16 + 8;
        return switch (kind) {
            case SURFACE -> new Anchor(x, origin.getHighestBlockY(x, z), z);
            case UNDERGROUND_SHALLOW -> underground(true, environment, origin, x, z, random);
            case UNDERGROUND_DEEP -> underground(false, environment, origin, x, z, random);
            case SKY -> sky(environment, origin, x, z, random, config);
            case LIQUID_SURFACE -> liquid(environment, origin, x, z);
            default -> null;
        };
    }

    private static Anchor underground(boolean shallow, World.Environment environment, BlockSource origin,
                                      int x, int z, RandomGenerator random) {
        int lowestY;
        int highestY;
        switch (environment) {
            case NORMAL, CUSTOM -> {
                lowestY = shallow ? 0 : -53;
                highestY = shallow ? 50 : 0;
            }
            case NETHER -> {
                lowestY = shallow ? 60 : 5;
                highestY = shallow ? 120 : 60;
            }
            case THE_END -> {
                lowestY = 0;
                highestY = 80;
            }
            default -> {
                return null;
            }
        }
        switch (environment) {
            case NORMAL, CUSTOM -> {
                return new Anchor(x, random.nextInt(lowestY, highestY), z);
            }
            case NETHER -> {
                if (shallow) return shallowUndergroundY(origin, x, z, lowestY, highestY, random);
                return netherDeepY(origin, x, z, lowestY, highestY, random);
            }
            case THE_END -> {
                if (shallow) return shallowUndergroundY(origin, x, z, lowestY, highestY, random);
                // エンドに深い地下は無い（浮島）。旧実装は Y を 0 のまま進める。
                return new Anchor(x, 0, z);
            }
            default -> {
                return null;
            }
        }
    }

    private static Anchor netherDeepY(BlockSource origin, int x, int z, int lowestY, int highestY, RandomGenerator random) {
        boolean streak = false;
        int lowPoint = 0;
        int highPoint = 0;
        int tolerance = 3;
        for (int y = highestY; y > lowestY; y--) {
            Material type = origin.getType(x, y, z);
            if (type.isSolid()) {
                if (streak) {
                    lowPoint = y;
                } else {
                    highPoint = y;
                    streak = true;
                }
            } else {
                if (type == Material.VOID_AIR || type == Material.BEDROCK || tolerance == 0) {
                    if (streak) {
                        streak = false;
                        if (highPoint - lowPoint >= 20) break;
                        if (type == Material.VOID_AIR || type == Material.BEDROCK) return null;
                        tolerance = 3;
                    }
                } else if (streak) {
                    tolerance--;
                    lowPoint = y;
                }
            }
        }
        if (highPoint - lowPoint < 20) return null;
        if (highPoint - lowPoint > 30) return new Anchor(x, random.nextInt(lowPoint, highPoint - 20), z);
        return new Anchor(x, lowPoint + 1, z);
    }

    /** ネザーとエンドの浅い地下: 下から上へ、20 以上の高さの固い層を探して、その中の埋まる位置を選ぶ。 */
    private static Anchor shallowUndergroundY(BlockSource origin, int x, int z, int lowestY, int highestY, RandomGenerator random) {
        boolean streak = false;
        int lowPoint = 0;
        int highPoint = 0;
        int tolerance = 3;
        for (int y = lowestY; y < highestY; y++) {
            Material type = origin.getType(x, y, z);
            if (type.isSolid()) {
                if (streak) {
                    highPoint = y;
                } else {
                    lowPoint = y;
                    streak = true;
                }
            } else {
                if (type == Material.VOID_AIR || type == Material.BEDROCK || tolerance == 0) {
                    if (streak) {
                        streak = false;
                        if (highPoint - lowPoint >= 20) break;
                        if (type == Material.VOID_AIR || type == Material.BEDROCK) return null;
                        tolerance = 3;
                    }
                } else if (streak) {
                    tolerance--;
                    highPoint = y;
                }
            }
        }
        if (highPoint - lowPoint < 20) return null;
        if (highPoint - lowPoint > 30) return new Anchor(x, random.nextInt(lowPoint + 1, highPoint - 20), z);
        return new Anchor(x, lowPoint + 1, z);
    }

    private static Anchor sky(World.Environment environment, BlockSource origin, int x, int z, RandomGenerator random, Config config) {
        int altitude = 0;
        switch (environment) {
            case NORMAL, CUSTOM -> altitude = random.nextInt(config.skyNormalMinAltitude(), config.skyNormalMaxAltitude() + 1);
            case THE_END -> altitude = random.nextInt(config.skyEndMinAltitude(), config.skyEndMaxAltitude() + 1);
            default -> {
            }
        }
        int y = origin.getHighestBlockY(x, z) + altitude;
        if (environment != World.Environment.NETHER) return new Anchor(x, y, z);

        boolean streak = false;
        int lowestY = 45;
        int highestY = 100;
        int lowPoint = 0;
        int highPoint = 0;
        int tolerance = 3;
        for (int scanY = lowestY; scanY < highestY; scanY++) {
            Material type = origin.getType(x, scanY, z);
            if (type.isAir()) {
                if (streak) {
                    highPoint = scanY;
                } else {
                    lowPoint = scanY;
                    streak = true;
                }
            } else {
                if (type == Material.VOID_AIR || type == Material.BEDROCK || tolerance == 0) {
                    if (streak) {
                        streak = false;
                        if (highPoint - lowPoint >= 40) break;
                        if (type == Material.VOID_AIR || type == Material.BEDROCK) return null;
                        tolerance = 3;
                    }
                } else if (streak) {
                    tolerance--;
                    highPoint = scanY;
                }
            }
        }
        if (highPoint - lowPoint < 20) return null;
        if (highPoint - lowPoint > 30) return new Anchor(x, random.nextInt(lowPoint + 1, highPoint - 20), z);
        return new Anchor(x, lowPoint + 1, z);
    }

    private static Anchor liquid(World.Environment environment, BlockSource origin, int x, int z) {
        int y = origin.getHighestBlockY(x, z);
        switch (environment) {
            case NORMAL, CUSTOM -> {
                Material type = origin.getType(x, y, z);
                if (type != Material.WATER && type != Material.LAVA) return null;
            }
            case NETHER -> {
                y = 31;
                if (origin.getType(x, y, z) != Material.LAVA) return null;
                for (int i = 1; i < 20; i++) {
                    if (!origin.getType(x, y + i, z).isAir()) return null;
                }
            }
            default -> {
            }
        }
        return new Anchor(x, y, z);
    }

    /**
     * 地下の構造物が、岩盤や世界の上限を越えないように Y を直す（図面を選んだ後。旧 FitUndergroundBuilding の後半）。
     * 地下以外の種類では何もしない。
     */
    public static Anchor clampUnderground(StructureType kind, World.Environment environment, Anchor anchor,
                                          SchematicShape shape, Config config) {
        if (kind != StructureType.UNDERGROUND_SHALLOW && kind != StructureType.UNDERGROUND_DEEP) return anchor;
        int offset = Math.abs(shape.offsetY());
        int height = shape.height();
        int lowest;
        int highest;
        switch (environment) {
            case NORMAL, CUSTOM -> {
                lowest = config.lowestYNormalCustom();
                highest = config.highestYNormalCustom();
            }
            case NETHER -> {
                lowest = config.lowestYNether();
                highest = config.highestYNether();
            }
            case THE_END -> {
                lowest = config.lowestYEnd();
                highest = config.highestYEnd();
            }
            default -> {
                return anchor;
            }
        }
        int y = anchor.y();
        if (y - offset < lowest) y = lowest + 1 + offset;
        else if (y + offset - height > highest) y = highest - height + offset;
        return new Anchor(anchor.x(), y, anchor.z());
    }
}
