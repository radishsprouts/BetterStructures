package com.magmaguy.betterstructures.modules;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.runtime.FlowSettings;
import com.magmaguy.betterstructures.runtime.GenerationGovernor;
import com.magmaguy.betterstructures.runtime.WorkerPool;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** ダンジョン生成の流れ: Governor の WFC 枠・Folia での拒否・後片付け。 */
class WFCGeneratorFlowTest {
    @TempDir Path tempFolder;
    private ServerMock server;
    private World world;

    @BeforeEach
    void open() {
        // MockBukkit のサーバーと世界は、保存先の取得を実装していないので、一時フォルダを返す物を使う
        File folder = tempFolder.toFile();
        server = MockBukkit.mock(new ServerMock() {
            @Override
            public File getWorldContainer() {
                return folder;
            }
        });
        MetadataHandler.PLUGIN = MockBukkit.createMockPlugin("WfcFlowFixture");
        WorldMock fixture = new WorldMock() {
            @Override
            public File getWorldFolder() {
                return folder;
            }
        };
        fixture.setName("wfc-flow-fixture");
        server.addWorld(fixture);
        world = fixture;
    }

    @AfterEach
    void close() {
        WorkerPool.shutdownInstance();
        MetadataHandler.PLUGIN = null;
        MockBukkit.unmock();
    }

    private ModuleGeneratorsConfigFields config(boolean generatedWorld, List<String> startModules) {
        ModuleGeneratorsConfigFields config = mock(ModuleGeneratorsConfigFields.class);
        when(config.isWorldGeneration()).thenReturn(generatedWorld);
        when(config.getStartModules()).thenReturn(startModules);
        when(config.getFilename()).thenReturn("fixture_generator.yml");
        when(config.getRadius()).thenReturn(2);
        when(config.getModuleSizeXZ()).thenReturn(16);
        when(config.getModuleSizeY()).thenReturn(16);
        when(config.getMinChunkY()).thenReturn(0);
        when(config.getMaxChunkY()).thenReturn(0);
        return config;
    }

    // ---- Folia では新しいワールドを作る設定を使わない ----

    @Test
    void foliaRefusesOnlyTheWorldGenerationSetting() {
        assertNotNull(WFCGenerator.unsupportedWorldGenerationMessage(config(true, List.of("a")), true));
        assertNull(WFCGenerator.unsupportedWorldGenerationMessage(config(true, List.of("a")), false),
                "Paper は今までどおり");
        assertNull(WFCGenerator.unsupportedWorldGenerationMessage(config(false, List.of("a")), true),
                "足元に生成する設定は Folia でも使える");
    }

    @Test
    void foliaCommandOnAWorldGenerationConfigOnlySendsANoticeAndQueuesNothing() {
        GenerationGovernor governor = new GenerationGovernor(FlowSettings::defaults);
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(world, 1, 2, 3));

        WFCGenerator.generateFromConfig(config(true, List.of("a")), player, true, governor);

        verify(player).sendMessage(contains("Folia"));
        assertEquals(0, governor.waiting(GenerationGovernor.Lane.WFC));
        assertEquals(0, governor.running(GenerationGovernor.Lane.WFC));
    }

    @Test
    void foliaCommandOnAFeetGenerationConfigQueuesTheGeneration() {
        GenerationGovernor governor = new GenerationGovernor(FlowSettings::defaults);
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(world, 1, 2, 3));

        WFCGenerator.generateFromConfig(config(false, List.of("a")), player, true, governor);

        assertEquals(1, governor.waiting(GenerationGovernor.Lane.WFC));
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void foliaNaturalGenerationOfAWorldGenerationConfigIsSkipped() {
        GenerationGovernor governor = new GenerationGovernor(FlowSettings::defaults);

        WFCGenerator.generateNaturally(config(true, List.of("a")), new Location(world, 0, 64, 0), () -> true, true, governor);

        assertEquals(0, governor.waiting(GenerationGovernor.Lane.WFC));
    }

    // ---- Governor の WFC 枠 ----

    @Test
    void generationsNeverRunBeyondTheWfcSlotAndNoneIsDropped() {
        GenerationGovernor governor = new GenerationGovernor(FlowSettings::defaults);
        assertEquals(1, FlowSettings.defaults().wfcSlots());
        List<GenerationGovernor.Ticket> started = new CopyOnWriteArrayList<>();
        UUID worldId = world.getUID();
        for (int i = 0; i < 3; i++) assertTrue(WFCGenerator.enqueue(governor, worldId, started::add));

        governor.tick();
        assertEquals(1, started.size());
        assertEquals(1, governor.running(GenerationGovernor.Lane.WFC));
        assertEquals(2, governor.waiting(GenerationGovernor.Lane.WFC), "待たせるだけで捨てない");

        governor.tick();
        assertEquals(1, started.size(), "枠が埋まっている間は次を始めない");

        started.get(0).release();
        governor.tick();
        assertEquals(2, started.size());
        assertEquals(1, governor.running(GenerationGovernor.Lane.WFC));

        started.get(1).release();
        governor.tick();
        assertEquals(3, started.size());
        started.get(2).release();
        assertEquals(0, governor.running(GenerationGovernor.Lane.WFC));
        assertEquals(0, governor.waiting(GenerationGovernor.Lane.WFC));
    }

    @Test
    void aGenerationThatCannotStartReturnsItsSlot() {
        GenerationGovernor governor = new GenerationGovernor(FlowSettings::defaults);
        WFCGenerator.generateNaturally(config(false, List.of()), new Location(world, 0, 64, 0), () -> true, false, governor);

        governor.tick();

        assertEquals(0, governor.running(GenerationGovernor.Lane.WFC), "始めの段階で終わった生成は枠を返す");
    }

    // ---- 後片付け ----

    @Test
    void cleanupRunsAndReturnsTheSlotEvenWhenFinishingThrows() throws Exception {
        // 計算担当を全部塞いで、生成の続きが勝手に進まないようにする（片付けの結果を取り違えないため）
        WorkerPool pool = WorkerPool.instance();
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(pool.threadCount());
        for (int i = 0; i < pool.threadCount(); i++) pool.execute(() -> {
            occupied.countDown();
            try {
                hold.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(occupied.await(10, TimeUnit.SECONDS));
        try {
            List<Integer> releases = new ArrayList<>();
            GenerationGovernor.Ticket ticket = () -> releases.add(1);
            WFCGenerator generator = mock(WFCGenerator.class, withSettings()
                    .useConstructor(config(false, List.of("start")), new Location(world, 0, 64, 0),
                            (java.util.function.BooleanSupplier) null, ticket)
                    .defaultAnswer(CALLS_REAL_METHODS));
            assertFalse(generator.isCleanedUp(), "始まったばかりで、まだ片付いていない");
            doThrow(new IllegalStateException("paste failed")).when(generator).instantPaste();

            assertDoesNotThrow(generator::finishGeneration);

            assertTrue(generator.isCleanedUp());
            assertEquals(1, releases.size(), "枠は1回だけ返す");
            generator.finishGeneration();
            assertEquals(1, releases.size(), "片付けを繰り返しても返却は1回");
        } finally {
            hold.countDown();
        }
    }

    @Test
    void modulesWithoutASchematicAreReportedAndNothingIsExemptExceptTheBorder() {
        ModulesContainer border = mock(ModulesContainer.class);
        when(border.isNothing()).thenReturn(true);
        ModulesContainer good = mock(ModulesContainer.class);
        when(good.getStructureSchematic()).thenReturn(mock(StructureSchematic.class));
        ModulesContainer broken = mock(ModulesContainer.class);
        when(broken.getClipboardFilename()).thenReturn("broken_module");

        assertEquals(List.of("broken_module"), WFCGenerator.modulesWithoutSchematic(List.of(border, good, broken)));
        assertTrue(WFCGenerator.modulesWithoutSchematic(List.of(border, good)).isEmpty());
    }
}
