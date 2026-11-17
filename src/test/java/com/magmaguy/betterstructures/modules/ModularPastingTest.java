package com.magmaguy.betterstructures.modules;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.api.WorldGenerationFinishEvent;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.integration.EliteMobsHook;
import com.magmaguy.betterstructures.paste.MarkerHandler;
import com.magmaguy.betterstructures.paste.PasteEngine;
import com.magmaguy.betterstructures.paste.PasteRequest;
import com.magmaguy.betterstructures.paste.PasteResult;
import com.magmaguy.betterstructures.runtime.RuntimeSettings;
import com.magmaguy.betterstructures.runtime.Services;
import com.magmaguy.betterstructures.runtime.WorkerPool;
import com.magmaguy.betterstructures.schematic.model.StructureBlock;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Vector3i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ダンジョンの貼り付け: 実際の ModulePasting が、偽の PasteEngine へ正しい依頼を出し、目印を ModularWorld へ登録し、
 * 完了を1回だけ知らせることを確かめる。WorldEdit の型は使わない。
 */
class ModularPastingTest {
    @TempDir Path folder;
    private ServerMock server;
    private JavaPlugin plugin;
    private World world;
    private FakePasteEngine engine;
    private final List<WorldGenerationFinishEvent> completions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void open() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("ModularPasteFixture");
        MetadataHandler.PLUGIN = plugin;
        WorldMock fixture = new SpawnTolerantWorld();
        fixture.setName("modular-paste-fixture");
        server.addWorld(fixture);
        world = fixture;
        engine = new FakePasteEngine();
        Services.setPasteEngine(engine);
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void finished(WorldGenerationFinishEvent event) { completions.add(event); }
        }, plugin);
    }

    @AfterEach
    void close() {
        Services.clear();
        WorkerPool.shutdownInstance();
        MetadataHandler.PLUGIN = null;
        MockBukkit.unmock();
    }

    // ---- 依頼の中身 ----

    @ParameterizedTest(name = "{displayName} rotation={0}")
    @CsvSource({"0,0,0,1,0,0,1", "90,1,0,1,1,0,0", "180,1,1,0,1,1,0", "270,0,1,0,0,1,1"})
    void rotatedSchematicKeepsTheOldOrientationAndDropsBarrierCells(int rotation,
            int airX, int airZ, int glowX, int glowZ, int barrierX, int barrierZ) {
        Location origin = new Location(world, -17, 70, 31);
        engine.autoComplete = true;
        start(false, origin, rotation, square(), 2, 16, EliteMobsHook.none());

        PasteRequest request = awaitRequests(1).getFirst();

        StructureSchematic pasted = request.schematic();
        assertEquals(Material.AIR, pasted.getBlockData(airX, 0, airZ).getMaterial(),
                "既存のワールドでは空気も貼る（空洞を掘る）");
        assertEquals(Material.GLOWSTONE, pasted.getBlockData(glowX, 0, glowZ).getMaterial());
        assertNull(pasted.getBlockData(barrierX, 0, barrierZ), "障壁のブロックは地形を上書きしない");
        int lanterns = 0;
        for (int x = 0; x < 2; x++)
            for (int z = 0; z < 2; z++) {
                BlockData data = pasted.getBlockData(x, 0, z);
                if (data != null && data.getMaterial() == Material.SEA_LANTERN) lanterns++;
            }
        assertEquals(1, lanterns, "4つめのブロックは別の行き先に届く");
        assertEquals(rotation, request.rotationDegrees());
        assertEquals(origin, request.baseLocation());
        assertEquals("fixture_module", request.schematicName());
        assertEquals(Material.STONE, request.bedrockReplacement(), "岩盤は STONE に置き換える");
        assertEquals(RuntimeSettings.get().wfcWindowChunks(), request.windowChunks());
        assertFalse(request.announce());
        assertTrue(completions.isEmpty(), "自然生成の貼り付けは、生成ワールドの完了を知らせない");
    }

    @Test
    void voidWorldDropsAirAndBarrierAndCompletesOnceAfterEveryModuleFinished() {
        Location origin = new Location(world, 0, 70, 0);
        start(true, origin, 0, square(), 2, 16, EliteMobsHook.none());

        PasteRequest request = awaitRequests(1).getFirst();
        server.getScheduler().performTicks(3);

        assertNull(request.schematic().getBlockData(0, 0, 0), "虚無のワールドでは空気を貼らない");
        assertNull(request.schematic().getBlockData(0, 0, 1), "障壁を貼らない");
        assertEquals(Material.GLOWSTONE, request.schematic().getBlockData(1, 0, 0).getMaterial());
        assertTrue(completions.isEmpty(), "貼り付けが終わるまで完了を知らせない");

        engine.completeAll(PasteResult.Outcome.COMPLETED);
        awaitCompletion();

        assertEquals(1, completions.size());
        ModularWorld generated = completions.getFirst().getModularWorld();
        assertSame(world, generated.getWorld());
        assertEquals(new Location(world, 8, 62, 8), generated.getCenter());
        assertEquals(48D, generated.getHorizontalSize());
    }

    @Test
    void completionWaitsForEveryModuleOfTheDungeon() {
        List<WFCNode> nodes = List.of(
                node(new Vector3i(0, 0, 0), square(), 0, 2, 16),
                node(new Vector3i(1, 0, 0), square(), 90, 2, 16));
        new ModulePasting(world, folder.toFile(), new ArrayDeque<>(nodes), "",
                new Location(world, 8, 62, 8), config(true, 2, 16), EliteMobsHook.none());

        awaitRequests(2);
        engine.handles.getFirst().finish(PasteResult.Outcome.COMPLETED);
        server.getScheduler().performTicks(3);
        assertTrue(completions.isEmpty(), "1つ残っている間は完了を知らせない");

        engine.handles.get(1).finish(PasteResult.Outcome.COMPLETED);
        awaitCompletion();
        assertEquals(1, completions.size());
    }

    @Test
    void unloadingAnOwnedWorldDoesNotAnnounceCompletion() {
        start(true, new Location(world, 0, 70, 0), 0, square(), 2, 16, EliteMobsHook.none());
        awaitRequests(1);
        assertTrue(server.unloadWorld(world, false));

        engine.completeAll(PasteResult.Outcome.CANCELLED);
        server.getScheduler().performTicks(5);

        assertTrue(completions.isEmpty(), "消えたワールドの途中の貼り付けは、完了を知らせない");
    }

    @ParameterizedTest(name = "{displayName} moduleWidth={0}")
    @CsvSource({"32,-2,47,160", "33,-1.5,47.5,165"})
    void completionRetainsTheTranslatedGeneratorGeometry(int moduleWidth, double centerX, double centerZ, double size) {
        engine.autoComplete = true;
        start(true, new Location(world, -17.8, 70.4, 31.75), 0, square(), 3, moduleWidth, EliteMobsHook.none());

        awaitRequests(1);
        awaitCompletion();

        assertEquals(1, completions.size());
        ModularWorld generated = completions.getFirst().getModularWorld();
        assertEquals(new Location(world, centerX, 62, centerZ), generated.getCenter());
        assertEquals(size, generated.getHorizontalSize());
    }

    // ---- 目印 ----

    @Test
    void everyDungeonMarkerIsRegisteredInTheModularWorldAndItsSignBecomesAir() {
        start(true, new Location(world, 0, 70, 0), 0, square(), 2, 16, EliteMobsHook.none());
        MarkerHandler handler = awaitRequests(1).getFirst().markerHandler();

        placeMarker(handler, 10, 70, 10, "[spawn]");
        placeMarker(handler, 11, 70, 10, "[chest]");
        placeMarker(handler, 12, 70, 10, "[trapped_chest]");
        placeMarker(handler, 13, 70, 10, "[barrel]");
        placeMarker(handler, 14, 70, 10, "[exit]", "elevator_up", "elevator_down");

        engine.completeAll(PasteResult.Outcome.COMPLETED);
        awaitCompletion();
        ModularWorld generated = completions.getFirst().getModularWorld();

        assertEquals(List.of(new Location(world, 10.5, 70, 10.5)), generated.getSpawnLocations());
        assertEquals(List.of(new Location(world, 11, 70, 10)), generated.getChestLocations());
        assertEquals(List.of(new Location(world, 12, 70, 10)), generated.getTrappedChestLocations());
        assertEquals(List.of(new Location(world, 13, 70, 10)), generated.getBarrelLocations());
        assertEquals(1, generated.getExitLocations().size());
        assertEquals(Material.AIR, world.getBlockAt(10, 70, 10).getType());
        assertEquals(Material.AIR, world.getBlockAt(13, 70, 10).getType());
        assertEquals(Material.AIR, world.getBlockAt(14, 70, 10).getType());
        assertEquals(Material.CHEST, world.getBlockAt(11, 70, 10).getType(), "[chest] は宝箱を置く");
        assertEquals(Material.TRAPPED_CHEST, world.getBlockAt(12, 70, 10).getType());
    }

    @Test
    void markerHandlerRecognizesOnlyMarkerSigns() {
        start(true, new Location(world, 0, 70, 0), 0, square(), 2, 16, EliteMobsHook.none());
        MarkerHandler handler = awaitRequests(1).getFirst().markerHandler();

        assertTrue(handler.isMarker(signBlock("[SPAWN]")), "大文字小文字は区別しない");
        assertTrue(handler.isMarker(signBlock("", "[pool:crypt]")));
        assertFalse(handler.isMarker(signBlock("hello")));
        assertFalse(handler.isMarker(signBlock("", "", "", "")));
        assertFalse(handler.isMarker(new StructureBlock(0, 0, 0, Material.STONE.createBlockData(), null, null)));
        assertEquals(MarkerHandler.PlainSignPolicy.REMOVE, handler.plainSignPolicy());
    }

    @Test
    void vanillaSpawnMarkerSpawnsTheMobAtTheBlockCenter() {
        start(false, new Location(world, -120, 70, 41), 0, square(), 2, 16, EliteMobsHook.none());
        MarkerHandler handler = awaitRequests(1).getFirst().markerHandler();

        placeMarker(handler, -120, 70, 41, "[spawn]", "VINDICATOR");

        var found = world.getEntities().stream().filter(entity -> entity.getType() == EntityType.VINDICATOR).toList();
        assertEquals(1, found.size());
        assertEquals(new Location(world, -119.5, 70, 41.5), found.getFirst().getLocation());
        assertEquals(Material.AIR, world.getBlockAt(-120, 70, 41).getType());
    }

    @Test
    void bossSignsSpawnThroughTheEliteMobsHookWhenTheDungeonCreatesNoWorld() {
        FakeEliteMobsHook hook = new FakeEliteMobsHook(true, Set.of(), Set.of());
        start(false, new Location(world, 0, 70, 0), 0, square(), 2, 16, hook);
        MarkerHandler handler = awaitRequests(1).getFirst().markerHandler();

        placeMarker(handler, 5, 70, 6, "[elitemobs]", "boss_one.yml");

        assertEquals(List.of(new FakeEliteMobsHook.Spawn(new Location(world, 5.5, 70, 6.5), "boss_one.yml")), hook.spawned);
    }

    @Test
    void bossSignsAreSkippedWithoutEliteMobs() {
        FakeEliteMobsHook hook = new FakeEliteMobsHook(false, Set.of(), Set.of());
        start(false, new Location(world, 0, 70, 0), 0, square(), 2, 16, hook);
        MarkerHandler handler = awaitRequests(1).getFirst().markerHandler();

        assertDoesNotThrow(() -> placeMarker(handler, 5, 70, 6, "[elitemobs]", "boss_one.yml"));

        assertTrue(hook.spawned.isEmpty());
        assertEquals(Material.AIR, world.getBlockAt(5, 70, 6).getType());
    }

    // ---- 部品 ----

    private void start(boolean generatedWorld, Location origin, int rotation, StructureSchematic schematic,
                       int radius, int moduleSize, EliteMobsHook hook) {
        WFCNode node = node(new Vector3i(), schematic, rotation, radius, moduleSize);
        // 格子の原点のセルは、生成の起点から半分のモジュールだけ西・北・上にずれる。
        Location generatorOrigin = origin.clone().add(moduleSize / 2, -8, moduleSize / 2);
        new ModulePasting(world, folder.toFile(), new ArrayDeque<>(List.of(node)), "", generatorOrigin,
                config(generatedWorld, radius, moduleSize), hook);
    }

    private ModuleGeneratorsConfigFields config(boolean generatedWorld, int radius, int moduleSize) {
        ModuleGeneratorsConfigFields config = mock(ModuleGeneratorsConfigFields.class);
        when(config.isWorldGeneration()).thenReturn(generatedWorld);
        when(config.getRadius()).thenReturn(radius);
        when(config.getModuleSizeXZ()).thenReturn(moduleSize);
        return config;
    }

    private WFCNode node(Vector3i position, StructureSchematic schematic, int rotation, int radius, int moduleSize) {
        ModuleGeneratorsConfigFields config = config(false, radius, moduleSize);
        WFCGenerator generator = mock(WFCGenerator.class);
        when(generator.getModuleGeneratorsConfigFields()).thenReturn(config);
        ModulesContainer module = mock(ModulesContainer.class);
        when(module.getStructureSchematic()).thenReturn(schematic);
        when(module.getRotation()).thenReturn(rotation);
        when(module.getClipboardFilename()).thenReturn("fixture_module");
        WFCLattice lattice = new WFCLattice(radius, moduleSize, 16, 0, 0);
        WFCNode node = new WFCNode(position, world, lattice, new HashMap<>(), generator);
        node.setModulesContainer(module);
        return node;
    }

    /** 2x2 の図面。(0,0)=空気、(1,0)=グロウストーン、(0,1)=障壁、(1,1)=シーランタン。 */
    private static StructureSchematic square() {
        BlockData[] blocks = new BlockData[4];
        blocks[0] = Material.AIR.createBlockData();
        blocks[1] = Material.GLOWSTONE.createBlockData();
        blocks[2] = Material.BARRIER.createBlockData();
        blocks[3] = Material.SEA_LANTERN.createBlockData();
        return new StructureSchematic((short) 2, (short) 1, (short) 2, 0, 0, 0, blocks,
                new HashMap<>(), new HashMap<>(), new ArrayList<>());
    }

    private static StructureBlock signBlock(String... lines) {
        String[] front = new String[4];
        for (int i = 0; i < 4; i++) front[i] = i < lines.length ? lines[i] : "";
        SignTextParser.ParsedSign parsed = new SignTextParser.ParsedSign(
                front, new String[]{"", "", "", ""}, DyeColor.BLACK, DyeColor.BLACK, false, false, false);
        return new StructureBlock(0, 0, 0, Material.OAK_SIGN.createBlockData(), null, parsed);
    }

    private void placeMarker(MarkerHandler handler, int x, int y, int z, String... lines) {
        world.getBlockAt(x, y, z).setType(Material.OAK_SIGN);
        StructureBlock block = signBlock(lines);
        assertTrue(handler.isMarker(block));
        handler.handleMarker(world, x, y, z, block);
    }

    private List<PasteRequest> awaitRequests(int count) {
        await(() -> engine.requests.size() >= count, "貼り付けの依頼が " + count + " 件に届かなかった");
        return List.copyOf(engine.requests);
    }

    /** 完了の通知はスケジューラ経由で届くので、tick を進めながら待つ。 */
    private void awaitCompletion() {
        await(() -> {
            server.getScheduler().performOneTick();
            return !completions.isEmpty();
        }, "完了の通知が届かなかった");
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(message);
            }
        }
        fail(message);
    }

    /** 依頼を記録し、完了を試験が決める偽の貼り付けエンジン。 */
    private static final class FakePasteEngine implements PasteEngine {
        final List<PasteRequest> requests = new CopyOnWriteArrayList<>();
        final List<FakeHandle> handles = new CopyOnWriteArrayList<>();
        volatile boolean autoComplete;

        @Override
        public PasteHandle start(PasteRequest request) {
            FakeHandle handle = new FakeHandle();
            requests.add(request);
            handles.add(handle);
            if (autoComplete) handle.finish(PasteResult.Outcome.COMPLETED);
            return handle;
        }

        void completeAll(PasteResult.Outcome outcome) {
            for (FakeHandle handle : handles) handle.finish(outcome);
        }

        @Override public int pendingCount() { return (int) handles.stream().filter(h -> !h.isDone()).count(); }

        @Override public void shutdown() { }
    }

    private static final class FakeHandle implements PasteEngine.PasteHandle {
        private final CompletableFuture<PasteResult> done = new CompletableFuture<>();

        void finish(PasteResult.Outcome outcome) { done.complete(PasteResult.of(outcome)); }

        @Override public void cancel() { finish(PasteResult.Outcome.CANCELLED); }

        @Override public boolean isDone() { return done.isDone(); }

        @Override public CompletableFuture<PasteResult> completion() { return done; }
    }

    /** MockBukkit が持たない「遠くで消える」設定の変更だけを受け流す世界。 */
    private static final class SpawnTolerantWorld extends WorldMock {
        @Override
        public org.bukkit.entity.Entity spawnEntity(Location location, EntityType type) {
            var entity = super.spawnEntity(location, type);
            if (!(entity instanceof org.bukkit.entity.LivingEntity living)) return entity;
            var supported = spy(living);
            doNothing().when(supported).setRemoveWhenFarAway(false);
            return supported;
        }
    }
}
