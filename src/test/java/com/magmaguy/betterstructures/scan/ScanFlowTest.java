package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType;
import com.magmaguy.betterstructures.paste.MarkerHandler;
import com.magmaguy.betterstructures.paste.PasteEngine;
import com.magmaguy.betterstructures.paste.PasteRequest;
import com.magmaguy.betterstructures.paste.PasteResult;
import com.magmaguy.betterstructures.runtime.Services;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ScanJob（偽の ports で）と PasteHandoff（偽の PasteEngine で）の流れの試験。 */
class ScanFlowTest {
    private static final AnchorPlanner.Config CONFIG = new AnchorPlanner.Config(
            60, 120, 50, 100, -60, 100, 5, 120, 0, 90);

    @BeforeAll
    static void startServer() {
        MockBukkit.mock();
    }

    @AfterAll
    static void stopServer() {
        MockBukkit.unmock();
    }

    @AfterEach
    void clearServices() {
        Services.clear();
    }

    /** FakeTerrain の1チャンク分。 */
    private record TerrainChunk(FakeTerrain terrain, int chunkX, int chunkZ) implements ChunkData {
        @Override
        public Material type(int localX, int y, int localZ) {
            return terrain.getType(chunkX * 16 + localX, y, chunkZ * 16 + localZ);
        }

        @Override
        public boolean containsWater(int localX, int y, int localZ) {
            return type(localX, y, localZ) == Material.WATER;
        }

        @Override
        public int highestY(int localX, int localZ) {
            return terrain.getHighestBlockY(chunkX * 16 + localX, chunkZ * 16 + localZ);
        }

        @Override
        public Biome biome(int localX, int y, int localZ) {
            return null;
        }
    }

    /** 偽の ports。delayed なら acquire の完了を試験が手で進める。 */
    private static final class FakePorts implements ScanPorts {
        final FakeTerrain terrain;
        final World.Environment environment;
        final boolean delayed;
        final List<long[]> acquired = new ArrayList<>();
        final List<CompletableFuture<ChunkData>> pending = new ArrayList<>();
        final List<long[]> pendingKeys = new ArrayList<>();
        volatile boolean stale;
        int failAtCount = -1;

        FakePorts(FakeTerrain terrain, World.Environment environment, boolean delayed) {
            this.terrain = terrain;
            this.environment = environment;
            this.delayed = delayed;
        }

        @Override public String worldName() { return "test"; }
        @Override public World.Environment environment() { return environment; }
        @Override public int minY() { return FakeTerrain.MIN_Y; }
        @Override public int maxY() { return FakeTerrain.MAX_Y; }

        @Override
        public CompletableFuture<ChunkData> acquire(int chunkX, int chunkZ) {
            acquired.add(new long[]{chunkX, chunkZ});
            CompletableFuture<ChunkData> future = new CompletableFuture<>();
            if (failAtCount >= 0 && acquired.size() > failAtCount) {
                future.completeExceptionally(new IllegalStateException("読み込みに失敗"));
                return future;
            }
            ChunkData data = new TerrainChunk(terrain, chunkX, chunkZ);
            if (delayed) {
                pending.add(future);
                pendingKeys.add(new long[]{chunkX, chunkZ});
                return future.thenApply(ignored -> data);
            }
            future.complete(data);
            return future;
        }

        /** 保留中の読み込みを完了させる。完了させた件数を返す。 */
        int completeAll() {
            int count = pending.size();
            List<CompletableFuture<ChunkData>> now = new ArrayList<>(pending);
            pending.clear();
            for (CompletableFuture<ChunkData> f : now) f.complete(null);
            return count;
        }

        @Override public void worker(Runnable task) { task.run(); }
        @Override public boolean stale() { return stale; }
        @Override public PluginChunkHolds.HoldSet holds() { return null; }
    }

    private static ScanJob.Chooser chooserOf(OldFitReference.FakeShape shape) {
        return (biome, y) -> new ScanJob.Choice(null, shape);
    }

    private static ScanJob job(FakePorts ports, StructureType kind, int cx, int cz, OldFitReference.FakeShape shape, long seed) {
        return new ScanJob(ports, kind, cx, cz, chooserOf(shape), CONFIG, new SplittableRandom(seed));
    }

    @Test
    void jobMatchesDirectPipelineAndAcquiresEachChunkOnce() {
        int plans = 0;
        for (int s = 0; s < 120; s++) {
            StructureType kind = StructureType.SURFACE;
            FakeTerrain terrain = new FakeTerrain(s, World.Environment.NORMAL);
            OldFitReference.FakeShape shape = new OldFitReference.FakeShape(new SplittableRandom(s + 1));
            FakePorts ports = new FakePorts(terrain, World.Environment.NORMAL, false);
            ScanJob.Result result = job(ports, kind, s % 9 - 4, s % 7 - 3, shape, s).run().join();

            Set<Long> seen = new HashSet<>();
            for (long[] key : ports.acquired) assertTrue(seen.add(key[0] * 1_000_003L + key[1]), "同じチャンクを2回取った s=" + s);

            // 直接の計算と同じ位置
            int[] expected = OldFitReference.evaluate(kind, World.Environment.NORMAL, terrain, shape,
                    new int[]{(s % 9 - 4) * 16 + 8, terrain.getHighestBlockY((s % 9 - 4) * 16 + 8, (s % 7 - 3) * 16 + 8), (s % 7 - 3) * 16 + 8});
            if (expected == null) {
                assertEquals(ScanJob.End.NO_FIT, result.end(), "s=" + s);
            } else {
                assertEquals(ScanJob.End.PLAN, result.end(), "s=" + s);
                assertEquals(expected[0], result.plan().x());
                assertEquals(expected[1], result.plan().y());
                assertEquals(expected[2], result.plan().z());
                plans++;
            }
        }
        assertTrue(plans > 0);
    }

    @Test
    void jobWaitsForDelayedChunksWithoutRestarting() {
        FakeTerrain terrain = new FakeTerrain(5, World.Environment.NORMAL);
        OldFitReference.FakeShape shape = new OldFitReference.FakeShape(new SplittableRandom(6));
        FakePorts ports = new FakePorts(terrain, World.Environment.NORMAL, true);
        CompletableFuture<ScanJob.Result> future = job(ports, StructureType.SURFACE, 2, 3, shape, 5).run();
        int rounds = 0;
        while (!future.isDone() && rounds++ < 50) ports.completeAll();
        assertTrue(future.isDone());
        Set<Long> seen = new HashSet<>();
        for (long[] key : ports.acquired) assertTrue(seen.add(key[0] * 1_000_003L + key[1]), "やり直して同じチャンクを再取得した");
        ScanJob.Result result = future.join();
        assertTrue(result.end() == ScanJob.End.PLAN || result.end() == ScanJob.End.NO_FIT);
    }

    @Test
    void failedAcquirePropagates() {
        FakeTerrain terrain = new FakeTerrain(8, World.Environment.NORMAL);
        OldFitReference.FakeShape shape = new OldFitReference.FakeShape(new SplittableRandom(9));
        FakePorts ports = new FakePorts(terrain, World.Environment.NORMAL, false);
        ports.failAtCount = 0;
        CompletableFuture<ScanJob.Result> future = job(ports, StructureType.SURFACE, 0, 0, shape, 8).run();
        assertTrue(future.isCompletedExceptionally());
        assertThrows(Exception.class, future::join);
    }

    @Test
    void staleJobEndsAsStale() {
        FakeTerrain terrain = new FakeTerrain(11, World.Environment.NORMAL);
        OldFitReference.FakeShape shape = new OldFitReference.FakeShape(new SplittableRandom(12));
        FakePorts ports = new FakePorts(terrain, World.Environment.NORMAL, false);
        ports.stale = true;
        ScanJob.Result result = job(ports, StructureType.SURFACE, 0, 0, shape, 11).run().join();
        assertEquals(ScanJob.End.STALE, result.end());
        assertNull(result.plan());
    }

    @Test
    void noSchematicWhenChooserFinds() {
        FakeTerrain terrain = new FakeTerrain(13, World.Environment.NORMAL);
        FakePorts ports = new FakePorts(terrain, World.Environment.NORMAL, false);
        ScanJob scan = new ScanJob(ports, StructureType.SURFACE, 0, 0, (biome, y) -> null, CONFIG, new SplittableRandom(1));
        assertEquals(ScanJob.End.NO_SCHEMATIC, scan.run().join().end());
    }

    // ---- PasteHandoff ----

    private static final class FakeEngine implements PasteEngine {
        final List<PasteRequest> requests = new ArrayList<>();
        final AtomicInteger shutdowns = new AtomicInteger();

        @Override
        public PasteHandle start(PasteRequest request) {
            requests.add(request);
            return new PasteHandle() {
                @Override public void cancel() { }
                @Override public boolean isDone() { return false; }
                @Override public CompletableFuture<PasteResult> completion() { return new CompletableFuture<>(); }
            };
        }

        @Override public int pendingCount() { return requests.size(); }
        @Override public void shutdown() { shutdowns.incrementAndGet(); }
    }

    private static final class RecordingEvents implements ScanEvents {
        boolean cancel;
        int places;
        final List<Location[]> completes = new ArrayList<>();

        @Override
        public boolean firePlace(com.magmaguy.betterstructures.buildingfitter.FitAnything fit) {
            places++;
            return cancel;
        }

        @Override
        public void fireComplete(com.magmaguy.betterstructures.buildingfitter.FitAnything fit, Location lowestCorner,
                                 Location highestCorner, List<Location> containers) {
            completes.add(new Location[]{lowestCorner, highestCorner});
        }
    }

    private ScanJob.Plan plan(StructureType kind, SchematicContainer container, OldFitReference.FakeShape shape) {
        return new ScanJob.Plan(kind, container, shape, 100, 64, 200, 90, PedestalSampler.Samples.EMPTY);
    }

    private static World world() {
        World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        return world;
    }

    private static SchematicContainer container() {
        SchematicContainer container = mock(SchematicContainer.class);
        when(container.getConfigFilename()).thenReturn("tower.yml");
        return container;
    }

    @Test
    void handoffBuildsRequestAndFiresCompleteOnFinish() {
        FakeEngine engine = new FakeEngine();
        Services.setPasteEngine(engine);
        World world = world();
        OldFitReference.FakeShape shape = new OldFitReference.FakeShape(new SplittableRandom(3));
        PluginChunkHolds.HoldSet holds = new PluginChunkHolds.HoldSet(world);
        // 貼り付けの範囲の内側と外側にひとつずつ
        int minChunkX = Math.floorDiv(100 + shape.offsetX(), 16);
        int minChunkZ = Math.floorDiv(200 + shape.offsetZ(), 16);
        holds.hold(minChunkX, minChunkZ);
        holds.hold(minChunkX + 500, minChunkZ + 500);
        RecordingEvents events = new RecordingEvents();
        MarkerHandler markers = mock(MarkerHandler.class);

        ScanOutcome outcome = PasteHandoff.start(world, plan(StructureType.SURFACE, container(), shape),
                mock(StructureSchematic.class), holds, fit -> markers, events);

        assertEquals(ScanOutcome.PLACED, outcome);
        assertEquals(1, events.places);
        assertEquals(1, engine.requests.size());
        PasteRequest request = engine.requests.get(0);
        assertEquals("tower.yml", request.schematicName());
        assertEquals(0, request.rotationDegrees());
        assertSame(markers, request.markerHandler());
        assertEquals(100, request.baseLocation().getBlockX());
        assertEquals(64, request.baseLocation().getBlockY());
        assertEquals(200, request.baseLocation().getBlockZ());
        assertEquals(shape.offsetX(), request.schematicOffset().getBlockX());
        assertTrue(request.fillPedestal());
        assertTrue(request.clearTrees());
        assertNotNull(request.pedestalMaterialProvider());
        assertSame(holds, request.heldChunks());
        assertTrue(holds.contains(minChunkX, minChunkZ));
        assertFalse(holds.contains(minChunkX + 500, minChunkZ + 500), "範囲外の hold を手放していない");

        // 完了でイベントを出す。取り消し・失敗では出さない
        request.onFinished().accept(PasteResult.of(PasteResult.Outcome.CANCELLED));
        request.onFinished().accept(PasteResult.of(PasteResult.Outcome.FAILED));
        assertEquals(0, events.completes.size());
        Location low = new Location(world, 1, 2, 3);
        Location high = new Location(world, 4, 5, 6);
        request.onFinished().accept(new PasteResult(PasteResult.Outcome.COMPLETED, low, high, List.of()));
        assertEquals(1, events.completes.size());
        assertSame(low, events.completes.get(0)[0]);
        assertSame(high, events.completes.get(0)[1]);
    }

    @Test
    void handoffKindsSetPedestalAndTreeFlags() {
        FakeEngine engine = new FakeEngine();
        Services.setPasteEngine(engine);
        World world = world();
        OldFitReference.FakeShape shape = new OldFitReference.FakeShape(new SplittableRandom(4));
        MarkerHandler markers = mock(MarkerHandler.class);
        for (StructureType kind : new StructureType[]{StructureType.SKY, StructureType.LIQUID_SURFACE,
                StructureType.UNDERGROUND_SHALLOW, StructureType.UNDERGROUND_DEEP}) {
            PasteHandoff.start(world, plan(kind, container(), shape), mock(StructureSchematic.class),
                    new PluginChunkHolds.HoldSet(world), fit -> markers, new RecordingEvents());
        }
        PasteRequest sky = engine.requests.get(0);
        assertNull(sky.pedestalMaterialProvider());
        assertFalse(sky.fillPedestal());
        assertFalse(sky.clearTrees());
        PasteRequest liquid = engine.requests.get(1);
        assertFalse(liquid.fillPedestal());
        assertFalse(liquid.clearTrees());
        for (int i = 2; i < 4; i++) {
            assertTrue(engine.requests.get(i).fillPedestal());
            assertFalse(engine.requests.get(i).clearTrees());
        }
    }

    @Test
    void cancelledPlaceEventDoesNotPaste() {
        FakeEngine engine = new FakeEngine();
        Services.setPasteEngine(engine);
        World world = world();
        OldFitReference.FakeShape shape = new OldFitReference.FakeShape(new SplittableRandom(5));
        RecordingEvents events = new RecordingEvents();
        events.cancel = true;
        PluginChunkHolds.HoldSet holds = new PluginChunkHolds.HoldSet(world);
        holds.hold(1, 1);
        ScanOutcome outcome = PasteHandoff.start(world, plan(StructureType.SURFACE, container(), shape),
                mock(StructureSchematic.class), holds, fit -> mock(MarkerHandler.class), events);
        assertEquals(ScanOutcome.CANCELLED, outcome);
        assertEquals(0, engine.requests.size());
        // 取り消した時の hold の後始末は呼び出し側の責任なので、ここでは手放さない
        assertTrue(holds.contains(1, 1));
    }
}
