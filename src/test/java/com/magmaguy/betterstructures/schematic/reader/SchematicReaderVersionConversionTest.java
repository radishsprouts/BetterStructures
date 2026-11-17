package com.magmaguy.betterstructures.schematic.reader;

import com.magmaguy.betterstructures.integration.SchematicImport;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 古い DataVersion の図面で、今のサーバーが解釈できない旧名のブロックが空気にならないこと、
 * 変換できなかったブロックは空気にして図面名とブロック名で数えること。
 *
 * <p>バニラの DataFixer（{@code StructureManager#loadStructure}）はモックのサーバーでは動かないので、
 * ここで確かめるのは「同梱の旧名→新名の対応表」と「バニラの変換を差し込む口」の経路。
 * 実サーバーでのバニラ変換は実機の確認事項。
 */
class SchematicReaderVersionConversionTest {

    @BeforeEach
    void startServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void stopServer() {
        MockBukkit.unmock();
    }

    @Test
    void oldNamedBlockIsConvertedInsteadOfBecomingAir() throws IOException {
        // 1.12 の頃の DataVersion。grass_path は今は dirt_path
        SimpleNbt.CompoundTag root = schematic(1343, Map.of("minecraft:grass_path", 0, "minecraft:stone", 1), 0, 1);
        CountingReport report = new CountingReport();

        StructureSchematic schematic = SchematicReader.parse(root, "old.schem", report);

        assertEquals(Material.DIRT_PATH, schematic.getBlockData(0, 0, 0).getMaterial(), "旧名のブロックは新名へ変換される");
        assertEquals(Material.STONE, schematic.getBlockData(1, 0, 0).getMaterial());
        assertEquals(0, report.count.get(), "変換できたものは報告しない");
    }

    @Test
    void blockThatCannotBeConvertedBecomesAirAndIsCountedByNames() throws IOException {
        SimpleNbt.CompoundTag root = schematic(1343, Map.of("minecraft:definitely_not_a_block", 0, "minecraft:stone", 1), 0, 0);
        UnresolvedBlockReport report = new UnresolvedBlockReport();

        StructureSchematic schematic = SchematicReader.parse(root, "broken.schem", report);

        assertEquals(Material.AIR, schematic.getBlockData(0, 0, 0).getMaterial());
        assertEquals(Material.AIR, schematic.getBlockData(1, 0, 0).getMaterial());
        String summary = report.summary();
        assertNotNull(summary);
        assertTrue(summary.contains("broken.schem"), summary);
        assertTrue(summary.contains("minecraft:definitely_not_a_block x2"), "2ブロック分を数える: " + summary);
        assertFalse(summary.contains("minecraft:stone"), "解釈できたブロックは含めない");
    }

    @Test
    void nothingIsReportedWhenEveryBlockIsKnown() throws IOException {
        SimpleNbt.CompoundTag root = schematic(1343, Map.of("minecraft:stone", 0, "minecraft:dirt", 1), 0, 1);
        UnresolvedBlockReport report = new UnresolvedBlockReport();

        SchematicReader.parse(root, "fine.schem", report);

        assertTrue(report.isEmpty());
        assertNull(report.summary());
    }

    @Test
    void injectedVanillaConversionIsUsedFirstAndTheTableCoversTheRest() {
        List<String> asked = new ArrayList<>();
        PaletteUpgrader.VanillaConverter vanilla = (dataVersion, states) -> {
            asked.addAll(states);
            assertEquals(1343, dataVersion);
            Map<String, BlockData> result = new LinkedHashMap<>();
            // バニラの変換が答えられるのは1つだけ
            result.put("minecraft:made_up_old_name", Material.MOSSY_COBBLESTONE.createBlockData());
            return result;
        };

        Map<String, BlockData> upgraded = PaletteUpgrader.upgrade(1343,
                List.of("minecraft:made_up_old_name", "minecraft:grass_path", "minecraft:no_such"), vanilla);

        assertEquals(3, asked.size(), "直接解釈できなかった項目が全部バニラの変換に渡る");
        assertEquals(Material.MOSSY_COBBLESTONE, upgraded.get("minecraft:made_up_old_name").getMaterial());
        assertEquals(Material.DIRT_PATH, upgraded.get("minecraft:grass_path").getMaterial(), "バニラが答えなかった分は対応表で変換");
        assertFalse(upgraded.containsKey("minecraft:no_such"), "変換できないものは結果に入れない");
    }

    @Test
    void failingVanillaConversionFallsBackToTheTable() {
        PaletteUpgrader.VanillaConverter failing = (dataVersion, states) -> {
            throw new UnsupportedOperationException("server structure manager unavailable");
        };

        Map<String, BlockData> upgraded = PaletteUpgrader.upgrade(1343, List.of("minecraft:grass_path[x=1]", "minecraft:web"), failing);

        // 性質が今の版に無い項目は変換できない、性質の無い項目は変換できる
        assertEquals(Material.COBWEB, upgraded.get("minecraft:web").getMaterial());
        assertFalse(upgraded.containsKey("minecraft:grass_path[x=1]"));
    }

    @Test
    void structureNbtHandedToTheServerKeepsTheOriginalDataVersionAndOrder() throws IOException {
        byte[] nbt = PaletteUpgrader.buildStructureNbt(1343,
                List.of("minecraft:grass_path", "minecraft:oak_stairs[facing=north,half=top]"));

        SimpleNbt.CompoundTag root = SimpleNbt.read(new ByteArrayInputStream(nbt));
        assertEquals(1343, root.getInt("DataVersion"));
        SimpleNbt.ListTag palette = root.getList("palette");
        assertEquals(2, palette.size());
        SimpleNbt.CompoundTag second = (SimpleNbt.CompoundTag) palette.get(1);
        assertEquals("minecraft:oak_stairs", second.getString("Name"));
        assertEquals("north", second.getCompound("Properties").getString("facing"));
        SimpleNbt.ListTag blocks = root.getList("blocks");
        assertEquals(2, blocks.size());
        assertEquals(1, ((SimpleNbt.CompoundTag) blocks.get(1)).getInt("state"));
        assertEquals(3, root.getList("size").size());
    }

    // ---- 小さな Sponge v2 図面 ----

    /** 横に並べた 2 ブロック（幅2・高さ1・奥行1）。ids は左から右のパレット番号。 */
    private static SimpleNbt.CompoundTag schematic(int dataVersion, Map<String, Integer> palette, int left, int right) {
        SimpleNbt.CompoundTag paletteTag = new SimpleNbt.CompoundTag();
        palette.forEach((state, id) -> paletteTag.put(state, new SimpleNbt.IntTag(id)));

        SimpleNbt.CompoundTag root = new SimpleNbt.CompoundTag();
        root.put("Version", new SimpleNbt.IntTag(2));
        root.put("DataVersion", new SimpleNbt.IntTag(dataVersion));
        root.put("Width", new SimpleNbt.ShortTag((short) 2));
        root.put("Height", new SimpleNbt.ShortTag((short) 1));
        root.put("Length", new SimpleNbt.ShortTag((short) 1));
        root.put("Palette", paletteTag);
        root.put("BlockData", new SimpleNbt.ByteArrayTag(new byte[] {(byte) left, (byte) right}));
        return root;
    }

    private static final class CountingReport implements SchematicImport.ConversionReport {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public void unresolvedBlock(String schematicName, String blockName) {
            count.incrementAndGet();
        }
    }
}
