package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.util.SurfaceMaterials;
import org.bukkit.Material;

import java.util.Arrays;

/**
 * 地表の候補の地形の凹凸の採点（旧 {@code Topology}）。World の代わりに {@link BlockSource} を読む純粋な関数で、
 * ワールドにもスレッドにも依らない。採点の式・閾値・浮動小数の計算順は旧実装と同じにしてある。
 *
 * <p>結果は 0（不合格）か 85 以上（合格）。旧実装が呼び出し側の Location の Y を書き換えていた
 * （平均の地面の高さ + 1）ので、ここでは結果に {@link Result#y()} として返す。
 *
 * <p>結果を変えない早期打ち切り: 最低と最高の高さの差が 20 に達した時点で（旧実装は全点を読んでから判定）
 * 0 を返す。
 */
public final class TopologyScorer {
    private TopologyScorer() {
    }

    /**
     * @param score 0（不合格）または合格の点
     * @param y     合格した時の貼り付けの Y（平均の地面の高さ + 1）。不合格なら意味を持たない
     */
    public record Result(double score, int y) {
        static final Result FAIL = new Result(0, 0);

        public boolean passed() {
            return score != 0;
        }
    }

    /**
     * @param source        地形
     * @param startingScore 開始点（通常 100、ネザーの地表は 200）
     * @param scanStep      走査の間隔
     * @param shape         図面（幅・奥行きとオフセットだけを見る）
     * @param x             候補の位置 X（オフセットを足す前）
     * @param z             候補の位置 Z（同上）
     * @param nether        ネザーの列の探し方を使うか
     */
    public static Result score(BlockSource source, double startingScore, int scanStep, SchematicShape shape,
                               int x, int z, boolean nether) {
        double score = startingScore;
        int width = shape.width();
        int depth = shape.depth();
        // ceil であること: 下のループは ceil(width/step) x ceil(depth/step) 点を訪れる。
        int totalPointAmount = (int) (Math.ceil(width / (double) scanStep) * Math.ceil(depth / (double) scanStep));
        if (totalPointAmount <= 0) return Result.FAIL;
        int[] heights = new int[totalPointAmount];
        int count = 0;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;

        for (int dx = 0; dx < width; dx += scanStep) {
            for (int dz = 0; dz < depth; dz += scanStep) {
                int worldX = x + shape.offsetX() + dx;
                int worldZ = z + shape.offsetZ() + dz;
                int y;
                if (nether) {
                    y = netherSurface(source, worldX, worldZ);
                    if (y == Integer.MIN_VALUE) return Result.FAIL;
                } else {
                    y = source.getHighestBlockY(worldX, worldZ);
                }
                int safeGuard = 0;
                while (SurfaceMaterials.ignorable(source.getType(worldX, y, worldZ))) {
                    if (source.getType(worldX, y, worldZ) == Material.VOID_AIR) return Result.FAIL;
                    y--;
                    safeGuard++;
                    if (safeGuard > 50) break;
                }
                Material type = source.getType(worldX, y, worldZ);
                if (type == Material.WATER || type == Material.LAVA) {
                    score -= 50 / (double) totalPointAmount;
                }
                if (score < 75) return Result.FAIL;
                heights[count++] = y;
                if (y < lowest) lowest = y;
                if (y > highest) highest = y;
                // 高さの差が 20 以上なら、残りを読んでも 0 になる。
                if (highest - lowest >= 20) return Result.FAIL;
            }
        }
        if (count == 0) return Result.FAIL;

        Arrays.sort(heights, 0, count);
        int averageFloorLevel = 0;
        for (int i = 0; i < count; i++) averageFloorLevel += heights[i];
        averageFloorLevel /= count;

        double maxImpact = score / 2D / count;
        for (int i = 0; i < count; i++) {
            int difference = Math.abs(averageFloorLevel - heights[i]);
            if (difference < 3) continue;
            double currentHeightScore = Math.min(1D, Math.pow(difference, 2) * 4 / 100) * maxImpact;
            score -= currentHeightScore;
            if (score < 85) return Result.FAIL;
        }
        // 位置の Y は、地面の上の最初の空気ブロック。
        return new Result(score, averageFloorLevel + 1);
    }

    /** ネザーの地面の探し方。見つからなければ {@link Integer#MIN_VALUE}。 */
    static int netherSurface(BlockSource source, int x, int z) {
        int start = 63;
        if (SurfaceMaterials.ignorable(source.getType(x, start, z))) {
            for (int y = start; y > 30; y--) {
                if (validNetherSurface(source, x, y, z)) return y;
            }
        } else {
            for (int y = start; y < 100; y++) {
                if (validNetherSurface(source, x, y, z)) return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean validNetherSurface(BlockSource source, int x, int y, int z) {
        if (SurfaceMaterials.ignorable(source.getType(x, y, z))) return false;
        if (!SurfaceMaterials.ignorable(source.getType(x, y + 1, z))) return false;
        for (int i = 1; i < 11; i++) {
            if (!SurfaceMaterials.ignorable(source.getType(x, y + i, z))) return false;
        }
        return true;
    }
}
