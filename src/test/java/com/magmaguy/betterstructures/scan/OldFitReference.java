package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType;
import com.magmaguy.betterstructures.util.SurfaceMaterials;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 改修前の {@code Topology}・{@code TerrainAdequacy}・{@code Fit*Building} の処理の写し（比較用）。
 * Location の代わりに {@code int[]{x, y, z}} を使い、World の代わりに {@link BlockSource} を読む。
 * 計算の順序・閾値・可変の位置の扱いは元のコードのまま写してあり、新しい実装と同じ結果になることの基準になる。
 */
final class OldFitReference {
    private OldFitReference() {
    }

    /** 図面の寸法だけを持つ試験用の図面。サンプルは座標のハッシュで決まる。 */
    static final class FakeShape implements SchematicShape {
        final int width, height, depth, offsetX, offsetY, offsetZ;
        final long seed;
        final int airPercent, liquidPercent;

        FakeShape(SplittableRandom random) {
            this.width = 1 + random.nextInt(40);
            this.height = 1 + random.nextInt(30);
            this.depth = 1 + random.nextInt(40);
            this.offsetX = -random.nextInt(width + 1);
            this.offsetY = -random.nextInt(6);
            this.offsetZ = -random.nextInt(depth + 1);
            this.seed = random.nextLong();
            this.airPercent = random.nextInt(100);
            this.liquidPercent = random.nextInt(30);
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public int depth() { return depth; }
        @Override public int offsetX() { return offsetX; }
        @Override public int offsetY() { return offsetY; }
        @Override public int offsetZ() { return offsetZ; }

        @Override
        public int sample(int x, int y, int z) {
            long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
            h ^= h >>> 29;
            h *= 0xbf58476d1ce4e5b9L;
            h ^= h >>> 32;
            int roll = (int) Math.floorMod(h, 100L);
            if (roll < liquidPercent) return LIQUID;
            if (roll < liquidPercent + airPercent) return AIR;
            return OTHER;
        }
    }

    // ---- Topology（旧）----

    static double topology(BlockSource source, double startingScore, int scanStep, SchematicShape shape, int[] location, boolean nether) {
        double score = startingScore;
        int width = shape.width();
        int depth = shape.depth();
        ArrayList<Integer> heights = new ArrayList<>();
        score = scanHighestLocations(source, width, depth, scanStep, location, shape, heights, score, nether);
        if (score == 0) return 0;
        score = scanExtremeHeightDifferences(heights, score);
        if (score == 0) return 0;
        int averageFloorLevel = 0;
        for (Integer integer : heights) averageFloorLevel += integer;
        averageFloorLevel /= heights.size();
        location[1] = averageFloorLevel + 1;
        return scoreTerrainHeightVariation(heights, averageFloorLevel, score);
    }

    private static double scanHighestLocations(BlockSource source, int width, int depth, int scanStep, int[] location,
                                               SchematicShape shape, ArrayList<Integer> heights, double score, boolean nether) {
        int totalPointAmount = (int) (Math.ceil(width / (double) scanStep) * Math.ceil(depth / (double) scanStep));
        for (int x = 0; x < width; x += scanStep) {
            for (int z = 0; z < depth; z += scanStep) {
                int px = location[0] + shape.offsetX() + x;
                int pz = location[2] + shape.offsetZ() + z;
                Integer py = highestBlockAt(source, px, pz, nether);
                if (py == null) return 0;
                int safeGuard = 0;
                while (SurfaceMaterials.ignorable(source.getType(px, py, pz))) {
                    if (source.getType(px, py, pz).equals(Material.VOID_AIR)) return 0;
                    py = py - 1;
                    safeGuard++;
                    if (safeGuard > 50) break;
                }
                switch (source.getType(px, py, pz)) {
                    case WATER:
                    case LAVA:
                        score -= 50 / (double) totalPointAmount;
                    default:
                }
                if (score < 75) return 0;
                heights.add(py);
            }
        }
        return score;
    }

    private static Integer highestBlockAt(BlockSource source, int x, int z, boolean nether) {
        if (!nether) return source.getHighestBlockY(x, z);
        int start = 63;
        if (SurfaceMaterials.ignorable(source.getType(x, start, z))) {
            for (int y = start; y > 30; y--) if (validNetherSurface(source, x, y, z)) return y;
        } else {
            for (int y = start; y < 100; y++) if (validNetherSurface(source, x, y, z)) return y;
        }
        return null;
    }

    private static boolean validNetherSurface(BlockSource source, int x, int y, int z) {
        if (!(!SurfaceMaterials.ignorable(source.getType(x, y, z)) && SurfaceMaterials.ignorable(source.getType(x, y + 1, z))))
            return false;
        for (int i = 1; i < 11; i++) {
            if (SurfaceMaterials.ignorable(source.getType(x, y + i, z))) continue;
            return false;
        }
        return true;
    }

    private static double scanExtremeHeightDifferences(ArrayList<Integer> heights, double score) {
        Collections.sort(heights);
        if (Math.abs(heights.get(0) - heights.get(heights.size() - 1)) >= 20) return 0;
        return score;
    }

    private static double scoreTerrainHeightVariation(ArrayList<Integer> heights, int averageFloorLevel, double score) {
        double maxImpact = score / 2D / heights.size();
        for (Integer integer : heights) {
            int difference = Math.abs(averageFloorLevel - integer);
            if (difference < 3) continue;
            double currentHeightScore = Math.min(1D, Math.pow(difference, 2) * 4 / 100) * maxImpact;
            score -= currentHeightScore;
            if (score < 85) return 0;
        }
        return score;
    }

    // ---- TerrainAdequacy（旧）----

    static double adequacy(BlockSource source, int scanStep, SchematicShape shape, int[] location, AdequacyScorer.ScanType scanType) {
        int totalCount = 0;
        int negativeCount = 0;
        for (int x = 0; x < shape.width(); x += scanStep) {
            for (int y = 0; y < shape.height(); y += scanStep) {
                for (int z = 0; z < shape.depth(); z += scanStep) {
                    int sample = shape.sample(x, y, z);
                    boolean air = sample == SchematicShape.AIR;
                    boolean liquid = sample == SchematicShape.LIQUID;
                    int px = location[0] + shape.offsetX() + x;
                    int py = location[1] + shape.offsetY() + y;
                    int pz = location[2] + shape.offsetZ() + z;
                    if (!isBlockAdequate(source, px, py, pz, air, liquid, location[1] - 1, scanType)) negativeCount++;
                    totalCount++;
                }
            }
        }
        return 100 - negativeCount * 100D / (double) totalCount;
    }

    private static boolean isBlockAdequate(BlockSource source, int px, int py, int pz, boolean schematicBlockIsAir,
                                           boolean schematicBlockIsLiquid, int floorHeight, AdequacyScorer.ScanType scanType) {
        Material terrain = source.getType(px, py, pz);
        if (terrain == Material.VOID_AIR) return false;
        switch (scanType) {
            case SURFACE:
                if (py > floorHeight) return SurfaceMaterials.ignorable(terrain) || !schematicBlockIsAir;
                else return !terrain.isAir();
            case AIR:
                return terrain.isAir();
            case UNDERGROUND:
                return terrain.isSolid();
            case LIQUID:
                if (py > floorHeight) return terrain.isAir();
                if (schematicBlockIsLiquid) return terrain == Material.WATER || terrain == Material.LAVA;
                return true;
            default:
                return false;
        }
    }

    // ---- Fit*Building#evaluate（旧）。採用した位置、無ければ null ----

    static int[] evaluate(StructureType kind, World.Environment environment, BlockSource source, SchematicShape shape, int[] anchor) {
        int scanStep = 3;
        boolean nether = environment == World.Environment.NETHER;
        double highestScore = 10;
        int[] location = null;
        switch (kind) {
            case SURFACE -> {
                double startingScore = 100;
                List<int[]> order = new ArrayList<>();
                order.add(new int[]{0, 0});
                for (int cx = -1; cx < 2; cx++) {
                    for (int cz = -1; cz < 2; cz++) {
                        if (cx == -1 && cz == -1 || cx == 1 && cz == 1 || cx == -1 && cz == 1 || cx == 1 && cz == -1) continue;
                        order.add(new int[]{cx, cz});
                    }
                }
                // (0,0) の二重走査は旧実装にもある（同じ入力に同じ結果なので省いても変わらない）。ここでは旧実装どおり行う。
                int i = 0;
                double[] best = {highestScore};
                int[][] loc = {null};
                for (int[] c : new int[][]{{0, 0}}) {
                    scanSurface(kind, nether, startingScore, scanStep, source, shape, anchor, c[0], c[1], best, loc);
                }
                if (best[0] < 50) {
                    outer:
                    for (int cx = -1; cx < 2; cx++) {
                        for (int cz = -1; cz < 2; cz++) {
                            if (cx == -1 && cz == -1 || cx == 1 && cz == 1 || cx == -1 && cz == 1 || cx == 1 && cz == -1) continue;
                            scanSurface(kind, nether, startingScore, scanStep, source, shape, anchor, cx, cz, best, loc);
                            if (best[0] > 50) break outer;
                        }
                    }
                }
                return loc[0];
            }
            case UNDERGROUND_SHALLOW, UNDERGROUND_DEEP -> {
                double[] best = {highestScore};
                int[][] loc = {null};
                scanUnderground(nether, scanStep, source, shape, anchor, 0, 0, best, loc);
                if (best[0] < 90) {
                    outer:
                    for (int cx = -1; cx < 2; cx++) {
                        for (int cz = -1; cz < 2; cz++) {
                            if (cx == 0 && cz == 0) continue;
                            scanUnderground(nether, scanStep, source, shape, anchor, cx, cz, best, loc);
                            if (best[0] > 90) break outer;
                        }
                    }
                }
                return loc[0];
            }
            case SKY -> {
                int[] found = scanAir(scanStep, source, shape, anchor, 0, 0);
                if (found == null) {
                    outer:
                    for (int cx = -1; cx < 2; cx++) {
                        for (int cz = -1; cz < 2; cz++) {
                            if (cx == 0 && cz == 0) continue;
                            found = scanAir(scanStep, source, shape, anchor, cx, cz);
                            if (found != null) break outer;
                        }
                    }
                }
                return found;
            }
            case LIQUID_SURFACE -> {
                double[] best = {highestScore};
                int[][] loc = {null};
                scanLiquid(scanStep, source, shape, anchor, 0, 0, best, loc);
                if (best[0] < 90) {
                    outer:
                    for (int cx = -1; cx < 2; cx++) {
                        for (int cz = -1; cz < 2; cz++) {
                            if (cx == 0 && cz == 0) continue;
                            scanLiquid(scanStep, source, shape, anchor, cx, cz, best, loc);
                            if (best[0] >= 90) break outer;
                        }
                    }
                }
                return loc[0];
            }
            default -> throw new IllegalArgumentException(kind.toString());
        }
    }

    private static void scanSurface(StructureType kind, boolean nether, double startingScore, int scanStep, BlockSource source,
                                    SchematicShape shape, int[] anchor, int cx, int cz, double[] best, int[][] loc) {
        int[] iterated = {anchor[0] + cx * 16, anchor[1], anchor[2] + cz * 16};
        if (nether) startingScore = 200;
        double score = topology(source, startingScore, scanStep, shape, iterated, nether);
        if (score == 0) return;
        double adequacy = adequacy(source, scanStep, shape, iterated, AdequacyScorer.ScanType.SURFACE);
        score += (.5 * adequacy);
        if (score > best[0]) {
            best[0] = score;
            loc[0] = iterated;
        }
    }

    private static void scanUnderground(boolean nether, int scanStep, BlockSource source, SchematicShape shape, int[] anchor,
                                        int cx, int cz, double[] best, int[][] loc) {
        int[] iterated = {anchor[0] + cx * 16, anchor[1], anchor[2] + cz * 16};
        double score = adequacy(source, scanStep, shape, iterated, AdequacyScorer.ScanType.UNDERGROUND);
        if (!nether) {
            if (score < 70) return;
        } else if (score < 50) return;
        if (score > best[0]) {
            best[0] = score;
            loc[0] = iterated;
        }
    }

    private static int[] scanAir(int scanStep, BlockSource source, SchematicShape shape, int[] anchor, int cx, int cz) {
        int[] iterated = {anchor[0] + cx * 16, anchor[1], anchor[2] + cz * 16};
        double newScore = adequacy(source, scanStep, shape, iterated, AdequacyScorer.ScanType.AIR);
        return newScore == 100 ? iterated : null;
    }

    private static void scanLiquid(int scanStep, BlockSource source, SchematicShape shape, int[] anchor, int cx, int cz,
                                   double[] best, int[][] loc) {
        int[] iterated = {anchor[0] + cx * 16, anchor[1] + 1, anchor[2] + cz * 16};
        double newScore = adequacy(source, scanStep, shape, iterated, AdequacyScorer.ScanType.LIQUID);
        if (newScore == 100) {
            best[0] = newScore;
            loc[0] = iterated;
        }
    }

    // ---- Fit*Building#scan の Y の決め方（旧）。決められなければ null ----

    /** 地下（浅い・深い）。戻りは {x, y, z}。 */
    static int[] oldUndergroundAnchor(StructureType kind, World.Environment environment, BlockSource source, int chunkX, int chunkZ,
                                      SplittableRandom random) {
        int lowestY;
        int highestY;
        switch (environment) {
            case NORMAL, CUSTOM -> {
                lowestY = kind == StructureType.UNDERGROUND_SHALLOW ? 0 : -53;
                highestY = kind == StructureType.UNDERGROUND_SHALLOW ? 50 : 0;
            }
            case NETHER -> {
                lowestY = kind == StructureType.UNDERGROUND_SHALLOW ? 60 : 5;
                highestY = kind == StructureType.UNDERGROUND_SHALLOW ? 120 : 60;
            }
            default -> {
                lowestY = 0;
                highestY = 80;
            }
        }
        int[] original = {chunkX * 16 + 8, 0, chunkZ * 16 + 8};
        switch (environment) {
            case NORMAL, CUSTOM -> original[1] = random.nextInt(lowestY, highestY);
            case NETHER -> {
                if (kind == StructureType.UNDERGROUND_SHALLOW) {
                    if (!setShallowUndergroundY(source, original, lowestY, highestY, random)) return null;
                } else {
                    boolean streak = false;
                    int lowPoint = 0;
                    int highPoint = 0;
                    int tolerance = 3;
                    for (int y = highestY; y > lowestY; y--) {
                        Material type = source.getType(original[0], y, original[2]);
                        if (type.isSolid()) {
                            if (streak) lowPoint = y;
                            else {
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
                    if (highPoint - lowPoint > 30) original[1] = random.nextInt(lowPoint, highPoint - 20);
                    else original[1] = lowPoint + 1;
                }
            }
            case THE_END -> {
                if (kind == StructureType.UNDERGROUND_SHALLOW) {
                    if (!setShallowUndergroundY(source, original, lowestY, highestY, random)) return null;
                }
            }
            default -> {
            }
        }
        return original;
    }

    private static boolean setShallowUndergroundY(BlockSource source, int[] original, int lowestY, int highestY, SplittableRandom random) {
        boolean streak = false;
        int lowPoint = 0;
        int highPoint = 0;
        int tolerance = 3;
        for (int y = lowestY; y < highestY; y++) {
            Material type = source.getType(original[0], y, original[2]);
            if (type.isSolid()) {
                if (streak) highPoint = y;
                else {
                    lowPoint = y;
                    streak = true;
                }
            } else {
                if (type == Material.VOID_AIR || type == Material.BEDROCK || tolerance == 0) {
                    if (streak) {
                        streak = false;
                        if (highPoint - lowPoint >= 20) break;
                        if (type == Material.VOID_AIR || type == Material.BEDROCK) return false;
                        tolerance = 3;
                    }
                } else if (streak) {
                    tolerance--;
                    highPoint = y;
                }
            }
        }
        if (highPoint - lowPoint < 20) return false;
        if (highPoint - lowPoint > 30) original[1] = random.nextInt(lowPoint + 1, highPoint - 20);
        else original[1] = lowPoint + 1;
        return true;
    }

    /** 地下の Y を岩盤・世界の上限の内側に直す（旧 FitUndergroundBuilding の後半）。 */
    static void oldClamp(World.Environment environment, int[] original, SchematicShape shape, AnchorPlanner.Config config) {
        int offset = Math.abs(shape.offsetY());
        int height = shape.height();
        switch (environment) {
            case NORMAL, CUSTOM -> {
                if (original[1] - offset < config.lowestYNormalCustom()) original[1] = config.lowestYNormalCustom() + 1 + offset;
                else if (original[1] + offset - height > config.highestYNormalCustom()) original[1] = config.highestYNormalCustom() - height + offset;
            }
            case NETHER -> {
                if (original[1] - offset < config.lowestYNether()) original[1] = config.lowestYNether() + 1 + offset;
                else if (original[1] + offset - height > config.highestYNether()) original[1] = config.highestYNether() - height + offset;
            }
            case THE_END -> {
                if (original[1] - offset < config.lowestYEnd()) original[1] = config.lowestYEnd() + 1 + offset;
                else if (original[1] + offset - height > config.highestYEnd()) original[1] = config.highestYEnd() - height + offset;
            }
            default -> {
            }
        }
    }

    /** 空。戻りは {x, y, z}、置けなければ null。 */
    static int[] oldSkyAnchor(World.Environment environment, BlockSource source, int chunkX, int chunkZ, SplittableRandom random,
                              AnchorPlanner.Config config) {
        int altitude = 0;
        switch (environment) {
            case NORMAL, CUSTOM -> altitude = random.nextInt(config.skyNormalMinAltitude(), config.skyNormalMaxAltitude() + 1);
            case THE_END -> altitude = random.nextInt(config.skyEndMinAltitude(), config.skyEndMaxAltitude() + 1);
            default -> {
            }
        }
        int x = chunkX * 16 + 8;
        int z = chunkZ * 16 + 8;
        int[] original = {x, source.getHighestBlockY(x, z) + altitude, z};
        if (environment == World.Environment.NETHER) {
            boolean streak = false;
            int lowestY = 45;
            int highestY = 100;
            int lowPoint = 0;
            int highPoint = 0;
            int tolerance = 3;
            for (int y = lowestY; y < highestY; y++) {
                Material type = source.getType(x, y, z);
                if (type.isAir()) {
                    if (streak) highPoint = y;
                    else {
                        lowPoint = y;
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
                        highPoint = y;
                    }
                }
            }
            if (highPoint - lowPoint < 20) return null;
            if (highPoint - lowPoint > 30) original[1] = random.nextInt(lowPoint + 1, highPoint - 20);
            else original[1] = lowPoint + 1;
        }
        return original;
    }

    /** 液体。戻りは {x, y, z}、置けなければ null。 */
    static int[] oldLiquidAnchor(World.Environment environment, BlockSource source, int chunkX, int chunkZ) {
        int x = chunkX * 16 + 8;
        int z = chunkZ * 16 + 8;
        int y = source.getHighestBlockY(x, z);
        switch (environment) {
            case CUSTOM, NORMAL -> {
                Material type = source.getType(x, y, z);
                if (type != Material.WATER && type != Material.LAVA) return null;
            }
            case NETHER -> {
                y = 31;
                if (source.getType(x, y, z) != Material.LAVA) return null;
                for (int i = 1; i < 20; i++) if (!source.getType(x, y + i, z).isAir()) return null;
            }
            default -> {
            }
        }
        return new int[]{x, y, z};
    }
}
