package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.util.SurfaceMaterials;
import org.bukkit.Material;

/**
 * 図面の体積が地形に収まるかの採点（旧 {@code TerrainAdequacy}）。World の代わりに {@link BlockSource} を読む
 * 純粋な関数。点数は {@code 100 - 否定の数 * 100 / 総数}（式と丸めは旧実装と同じ）。
 *
 * <p>結果を変えない早期打ち切り: 点数は否定が増えるほど単調に下がるので、「否定が k 個の時点の上限」
 * {@code 100 - k*100/総数} が（合格の境界を下回る）か（今の最良点以下になる）と分かった時点で
 * {@link #ABORTED} を返す。打ち切られた候補は、最後まで読んでも採用されない。
 */
public final class AdequacyScorer {
    /** 打ち切りの目印。本物の点数は 0 以上なので区別できる。 */
    public static final double ABORTED = -1;

    /** 採点の種類。 */
    public enum ScanType {
        SURFACE,
        UNDERGROUND,
        AIR,
        LIQUID
    }

    private AdequacyScorer() {
    }

    /**
     * @param source     地形
     * @param shape      図面
     * @param scanStep   走査の間隔
     * @param x          候補の位置 X（オフセットを足す前）
     * @param y          候補の位置 Y（床の高さは y - 1）
     * @param z          候補の位置 Z
     * @param type       採点の種類
     * @param atLeast    これ未満になると分かったら打ち切る（打ち切らないなら {@link Double#NEGATIVE_INFINITY}）
     * @param mustExceed これ以下になると分かったら打ち切る（同上）
     * @return 点数、または {@link #ABORTED}
     */
    public static double score(BlockSource source, SchematicShape shape, int scanStep, int x, int y, int z,
                               ScanType type, double atLeast, double mustExceed) {
        int width = shape.width();
        int depth = shape.depth();
        int height = shape.height();
        long totalCount = (long) Math.ceil(width / (double) scanStep)
                * (long) Math.ceil(height / (double) scanStep)
                * (long) Math.ceil(depth / (double) scanStep);
        if (totalCount <= 0) return 0;
        int floorHeight = y - 1;
        int negativeCount = 0;

        for (int dx = 0; dx < width; dx += scanStep) {
            for (int dy = 0; dy < height; dy += scanStep) {
                for (int dz = 0; dz < depth; dz += scanStep) {
                    int sample = shape.sample(dx, dy, dz);
                    int worldY = y + shape.offsetY() + dy;
                    Material terrain = source.getType(x + shape.offsetX() + dx, worldY, z + shape.offsetZ() + dz);
                    if (!isBlockAdequate(terrain, sample == SchematicShape.AIR, sample == SchematicShape.LIQUID,
                            floorHeight, worldY, type)) {
                        negativeCount++;
                        double upperBound = 100 - negativeCount * 100D / (double) totalCount;
                        if (upperBound < atLeast || upperBound <= mustExceed) return ABORTED;
                    }
                }
            }
        }
        return 100 - negativeCount * 100D / (double) totalCount;
    }

    private static boolean isBlockAdequate(Material terrain, boolean schematicBlockIsAir, boolean schematicBlockIsLiquid,
                                           int floorHeight, int floorYValue, ScanType type) {
        if (terrain == Material.VOID_AIR) return false;
        switch (type) {
            case SURFACE:
                if (floorYValue > floorHeight) {
                    return SurfaceMaterials.ignorable(terrain) || !schematicBlockIsAir;
                } else {
                    return !terrain.isAir();
                }
            case AIR:
                return terrain.isAir();
            case UNDERGROUND:
                return terrain.isSolid();
            case LIQUID:
                if (floorYValue > floorHeight) {
                    return terrain.isAir();
                } else if (schematicBlockIsLiquid) {
                    return terrain == Material.WATER || terrain == Material.LAVA;
                } else {
                    return true;
                }
            default:
                return false;
        }
    }
}
