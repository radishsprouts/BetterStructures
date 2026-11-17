package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新しい走査（AnchorPlanner・CandidateSelector）が、旧実装（OldFitReference に写した Fit*Building）と
 * 同じ位置を採用することを、多数の疑似地形で確かめる。
 */
class ScanEquivalenceTest {
    private static final AnchorPlanner.Config CONFIG = new AnchorPlanner.Config(
            60, 120, 50, 100, -60, 100, 5, 120, 0, 90);
    private static final int CASES = 400;

    @BeforeAll
    static void startServer() {
        MockBukkit.mock();
    }

    @AfterAll
    static void stopServer() {
        MockBukkit.unmock();
    }

    private static final StructureType[] KINDS = {StructureType.SURFACE, StructureType.UNDERGROUND_SHALLOW,
            StructureType.UNDERGROUND_DEEP, StructureType.SKY, StructureType.LIQUID_SURFACE};

    /** 読んだ座標を記録する包み。 */
    private static final class Recording implements BlockSource {
        final BlockSource inner;
        int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;

        Recording(BlockSource inner) {
            this.inner = inner;
        }

        private void note(int x, int z) {
            int cx = Math.floorDiv(x, 16);
            int cz = Math.floorDiv(z, 16);
            minCx = Math.min(minCx, cx);
            maxCx = Math.max(maxCx, cx);
            minCz = Math.min(minCz, cz);
            maxCz = Math.max(maxCz, cz);
        }

        @Override public int minY() { return inner.minY(); }
        @Override public int maxY() { return inner.maxY(); }
        @Override public Material getType(int x, int y, int z) { note(x, z); return inner.getType(x, y, z); }
        @Override public int getHighestBlockY(int x, int z) { note(x, z); return inner.getHighestBlockY(x, z); }
        @Override public Biome getBiome(int x, int y, int z) { return inner.getBiome(x, y, z); }
    }

    private static int[] newPipeline(StructureType kind, World.Environment env, BlockSource source, OldFitReference.FakeShape shape,
                                     int chunkX, int chunkZ, long randomSeed) {
        AnchorPlanner.Anchor anchor = AnchorPlanner.plan(kind, env, source, chunkX, chunkZ, new SplittableRandom(randomSeed), CONFIG);
        if (anchor == null) return null;
        anchor = AnchorPlanner.clampUnderground(kind, env, anchor, shape, CONFIG);
        CandidateSelector selector = new CandidateSelector(kind, env, shape, anchor.x(), anchor.y(), anchor.z());
        for (int i = 0; i < selector.candidateCount(); i++) {
            selector.score(i, source);
            if (selector.isFinished()) break;
        }
        CandidateSelector.Candidate c = selector.result();
        return c == null ? null : new int[]{c.x(), c.y(), c.z()};
    }

    private static int[] oldPipeline(StructureType kind, World.Environment env, BlockSource source, OldFitReference.FakeShape shape,
                                     int chunkX, int chunkZ, long randomSeed) {
        SplittableRandom random = new SplittableRandom(randomSeed);
        int[] anchor;
        switch (kind) {
            case SURFACE -> {
                int x = chunkX * 16 + 8;
                int z = chunkZ * 16 + 8;
                anchor = new int[]{x, source.getHighestBlockY(x, z), z};
            }
            case UNDERGROUND_SHALLOW, UNDERGROUND_DEEP -> {
                anchor = OldFitReference.oldUndergroundAnchor(kind, env, source, chunkX, chunkZ, random);
                if (anchor != null) OldFitReference.oldClamp(env, anchor, shape, CONFIG);
            }
            case SKY -> anchor = OldFitReference.oldSkyAnchor(env, source, chunkX, chunkZ, random, CONFIG);
            default -> anchor = OldFitReference.oldLiquidAnchor(env, source, chunkX, chunkZ);
        }
        if (anchor == null) return null;
        return OldFitReference.evaluate(kind, env, source, shape, anchor);
    }

    private void compareAll(World.Environment env) {
        int found = 0;
        for (StructureType kind : KINDS) {
            for (int s = 0; s < CASES; s++) {
                long seed = s * 7919L + kind.ordinal();
                FakeTerrain terrain = new FakeTerrain(seed, env);
                OldFitReference.FakeShape shape = new OldFitReference.FakeShape(new SplittableRandom(seed * 31 + 5));
                int chunkX = (int) (seed % 50) - 25;
                int chunkZ = (int) (seed % 37) - 18;
                int[] expected = oldPipeline(kind, env, terrain, shape, chunkX, chunkZ, seed);
                int[] actual = newPipeline(kind, env, terrain, shape, chunkX, chunkZ, seed);
                String label = env + " " + kind + " seed=" + seed;
                if (expected == null) assertNull(actual, label);
                else {
                    assertNotNull(actual, label);
                    assertArrayEquals(expected, actual, label);
                    found++;
                }
            }
        }
        // 採用が1件も無い試験は何も確かめていない
        assertTrue(found > 0, env + " で採用された例が無い");
    }

    @Test
    void overworldMatchesOld() {
        compareAll(World.Environment.NORMAL);
    }

    @Test
    void netherMatchesOld() {
        compareAll(World.Environment.NETHER);
    }

    @Test
    void endMatchesOld() {
        compareAll(World.Environment.THE_END);
    }

    @Test
    void topologyMatchesOld() {
        int passed = 0;
        for (int s = 0; s < 1500; s++) {
            World.Environment env = s % 3 == 0 ? World.Environment.NETHER : World.Environment.NORMAL;
            boolean nether = env == World.Environment.NETHER;
            FakeTerrain terrain = new FakeTerrain(s, env);
            OldFitReference.FakeShape shape = new OldFitReference.FakeShape(new SplittableRandom(s + 99));
            int x = (s % 61) * 16;
            int z = (s % 43) * 16;
            int[] loc = {x, 70, z};
            double start = nether ? 200 : 100;
            double old = OldFitReference.topology(terrain, start, 3, shape, loc, nether);
            TopologyScorer.Result result = TopologyScorer.score(terrain, start, 3, shape, x, z, nether);
            assertEquals(old, result.score(), 0.0, "score s=" + s);
            if (old != 0) {
                assertEquals(loc[1], result.y(), "y s=" + s);
                passed++;
            }
        }
        assertTrue(passed > 0);
    }

    @Test
    void adequacyAbortIsExactlyBelowThresholdOrNotAboveBest() {
        int aborted = 0;
        int kept = 0;
        for (int s = 0; s < 1500; s++) {
            World.Environment env = World.Environment.values()[s % 3];
            FakeTerrain terrain = new FakeTerrain(s, env);
            OldFitReference.FakeShape shape = new OldFitReference.FakeShape(new SplittableRandom(s + 3));
            for (AdequacyScorer.ScanType type : AdequacyScorer.ScanType.values()) {
                int[] loc = {(s % 29) * 16, 20 + s % 60, (s % 23) * 16};
                double full = OldFitReference.adequacy(terrain, 3, shape, loc, type);
                // 閾値ちょうど・最良点ちょうどを含む
                double[] atLeasts = {Double.NEGATIVE_INFINITY, full, full + 0.000001, full - 0.000001, 70, 90};
                double[] mustExceeds = {Double.NEGATIVE_INFINITY, full, full - 0.000001, full + 0.000001, 10, 90};
                for (double atLeast : atLeasts) {
                    for (double mustExceed : mustExceeds) {
                        double got = AdequacyScorer.score(terrain, shape, 3, loc[0], loc[1], loc[2], type, atLeast, mustExceed);
                        boolean shouldAbort = full < atLeast || full <= mustExceed;
                        if (got == AdequacyScorer.ABORTED) {
                            aborted++;
                            // 打ち切られたのは、最後まで読んでも採用されない候補だけ
                            assertTrue(shouldAbort, "不要な打ち切り s=" + s + " " + type + " full=" + full + " atLeast=" + atLeast + " mustExceed=" + mustExceed);
                        } else {
                            kept++;
                            assertEquals(full, got, 0.0, "s=" + s + " " + type);
                            assertTrue(!shouldAbort || got < atLeast || got <= mustExceed);
                        }
                    }
                }
            }
        }
        assertTrue(aborted > 0 && kept > 0);
    }

    @Test
    void neededChunksCoverEveryRead() {
        for (StructureType kind : KINDS) {
            for (int s = 0; s < 150; s++) {
                World.Environment env = World.Environment.values()[s % 3];
                FakeTerrain terrain = new FakeTerrain(s, env);
                OldFitReference.FakeShape shape = new OldFitReference.FakeShape(new SplittableRandom(s + 17));
                int ax = (s % 31) * 16 + 8;
                int az = (s % 19) * 16 + 8;
                CandidateSelector selector = new CandidateSelector(kind, env, shape, ax, 40 + s % 30, az);
                for (int i = 0; i < selector.candidateCount(); i++) {
                    CandidateSelector.ChunkRange range = selector.neededChunks(i);
                    Recording recording = new Recording(terrain);
                    selector.score(i, recording);
                    if (recording.minCx != Integer.MAX_VALUE) {
                        String label = kind + " s=" + s + " i=" + i;
                        assertTrue(recording.minCx >= range.minX() && recording.maxCx <= range.maxX(), "X " + label);
                        assertTrue(recording.minCz >= range.minZ() && recording.maxCz <= range.maxZ(), "Z " + label);
                    }
                    if (selector.isFinished()) break;
                }
            }
        }
    }
}
