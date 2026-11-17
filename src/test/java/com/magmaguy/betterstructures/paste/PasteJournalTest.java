package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PasteJournal（04章 G6）: 書いて読み戻すと同じ状態、停止から再開してもブロックが二重にならない、
 * エンティティが二重に出ない、壊れた記録・再開手段が未設定の記録を捨てない。
 */
final class PasteJournalTest {

    @TempDir
    Path temp;
    private PasteTestKit kit;
    private Path journalFile;

    @BeforeEach
    void setUp() {
        journalFile = temp.resolve("journal.json");
        kit = new PasteTestKit(journalFile);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void 書いて読み戻すと同じ状態になる() throws IOException {
        PasteJournal journal = new PasteJournal(journalFile);
        PasteJournal.Data data = new PasteJournal.Data();
        PasteJournal.Entry entry = new PasteJournal.Entry();
        entry.sessionId = "セッション-1";
        entry.worldId = "w-1";
        entry.schematicName = "castle.schem";
        entry.rotationDegrees = 270;
        entry.baseX = -10;
        entry.baseY = 64;
        entry.baseZ = 200;
        entry.offsetY = -2;
        entry.fillPedestal = true;
        entry.announce = true;
        entry.windowChunks = 3;
        entry.bedrockReplacement = "STONE";
        entry.chunks.put("42", new PasteJournal.ChunkProgress(PasteStage.SIGNS, 7));
        entry.chunks.put("43", new PasteJournal.ChunkProgress(PasteStage.DONE, 0));
        data.pastes.add(entry);
        data.scans.add(new PasteJournal.ScanEntry("w-1", 5, -6, "BUILDING"));

        journal.write(data);
        PasteJournal.Data read = journal.read();

        assertEquals(1, read.pastes.size());
        PasteJournal.Entry back = read.pastes.get(0);
        assertEquals("セッション-1", back.sessionId);
        assertEquals("castle.schem", back.schematicName);
        assertEquals(270, back.rotationDegrees);
        assertEquals(-10, back.baseX);
        assertEquals(200, back.baseZ);
        assertEquals(-2, back.offsetY);
        assertTrue(back.fillPedestal);
        assertFalse(back.clearTrees);
        assertEquals(3, back.windowChunks);
        assertEquals("STONE", back.bedrockReplacement);
        assertEquals(PasteStage.SIGNS, back.chunks.get("42").stage());
        assertEquals(7, back.chunks.get("42").cursor);
        assertEquals(PasteStage.DONE, back.chunks.get("43").stage());
        assertEquals(1, read.scans.size());
        assertEquals(-6, read.scans.get(0).chunkZ);
        assertEquals("BUILDING", read.scans.get(0).kind);
        assertFalse(Files.exists(journalFile.resolveSibling("journal.json.tmp")), "一時ファイルを残さない");
    }

    @Test
    void ファイルが無ければ空で_壊れていれば退避して空になる() throws IOException {
        PasteJournal journal = new PasteJournal(journalFile);
        assertTrue(journal.read().pastes.isEmpty());

        Files.writeString(journalFile, "{ これはJSONではない");
        assertTrue(journal.read().pastes.isEmpty());
        assertTrue(Files.exists(journalFile.resolveSibling("journal.json.corrupt")), "壊れた記録は捨てずに退避する");
        assertFalse(Files.exists(journalFile));
    }

    private StructureSchematic band() {
        return new PasteTestKit.SchemBuilder(48, 1, 2).fill(Material.STONE).build();
    }

    private PasteRequest bandRequest(StructureSchematic schematic, AtomicInteger finishedCount) {
        return kit.request(schematic, 0, 64, 0, new PasteTestKit.TestMarkers())
                .schematicName("band").onFinished(r -> finishedCount.incrementAndGet()).build();
    }

    @Test
    void 停止から再開してもブロックが二重にならず終わったチャンクを書き直さない() throws IOException {
        StructureSchematic schematic = band();
        kit.costPerAccessNanos = kit.budget.limitNanos() / 4 + 1;
        AtomicInteger finished = new AtomicInteger();
        PasteEngine.PasteHandle handle = kit.engine.start(bandRequest(schematic, finished));
        // チャンク 0 が DONE になって hold が返るまで進める。
        kit.tickUntil(() -> !kit.ticketHistory.isEmpty() && kit.ticketHistory.contains("-0,0"), 5000);
        assertFalse(handle.isDone(), "全体はまだ終わっていない");

        kit.engine.shutdown();
        kit.server.getScheduler().performTicks(2L);

        assertEquals(PasteResult.Outcome.CANCELLED, handle.completion().join().outcome());
        assertEquals(0, finished.get(), "停止での中断は完了通知を出さない（記録に残して続きから）");
        assertTrue(kit.tickets.isEmpty(), "停止で hold を返す: " + kit.tickets);
        assertTrue(Files.exists(journalFile), "停止時に最後の記録を同期で書く");
        PasteJournal.Data saved = new PasteJournal(journalFile).read();
        assertEquals(1, saved.pastes.size());
        PasteJournal.Entry entry = saved.pastes.get(0);
        assertEquals("band", entry.schematicName);
        assertEquals(PasteStage.DONE, entry.chunks.get(Long.toString(org.bukkit.Chunk.getChunkKey(0, 0))).stage());

        // 終わったチャンクの中を書き換えておく。再開が書き直すなら消える。
        kit.world.getBlockAt(0, 64, 0).setType(Material.DIRT);

        NativePasteEngine second = new NativePasteEngine(kit.env);
        second.setResumer(e -> bandRequest(schematic, finished));
        second.resumeFromJournal();
        kit.tickUntil(() -> second.pendingCount() == 0 && kit.idle(), 5000);

        assertEquals(1, finished.get(), "再開して完了通知は1回だけ");
        assertEquals(Material.DIRT, kit.world.getBlockAt(0, 64, 0).getType(), "DONE のチャンクは書き直さない");
        long stone = 0;
        for (int x = 0; x < 48; x++)
            for (int z = 0; z < 2; z++)
                if (kit.world.getBlockAt(x, 64, z).getType() == Material.STONE) stone++;
        assertEquals(95, stone, "残りは全部置かれている（落とさない・二重にしない）");
        assertTrue(kit.tickets.isEmpty());
        assertEquals(List.of(), kit.ticketViolations);
    }

    @Test
    void 再開の手段が無い記録は警告して保持し書き戻す() throws IOException {
        PasteJournal journal = new PasteJournal(journalFile);
        PasteJournal.Data data = new PasteJournal.Data();
        PasteJournal.Entry entry = new PasteJournal.Entry();
        entry.sessionId = "keep-me";
        entry.worldId = kit.world.getUID().toString();
        entry.schematicName = "gone.schem";
        data.pastes.add(entry);
        data.scans.add(new PasteJournal.ScanEntry(entry.worldId, 1, 2, "BUILDING"));
        journal.write(data);

        NativePasteEngine engine = new NativePasteEngine(kit.env);
        engine.resumeFromJournal();
        engine.shutdown();

        PasteJournal.Data after = journal.read();
        assertEquals(1, after.pastes.size(), "再開できない記録を捨てない");
        assertEquals("keep-me", after.pastes.get(0).sessionId);
        assertEquals(1, after.scans.size(), "走査の待ち行列も受け取り先が無い間は保持する");
    }

    @Test
    void 図面が見つからない記録は保持される() throws IOException {
        PasteJournal journal = new PasteJournal(journalFile);
        PasteJournal.Data data = new PasteJournal.Data();
        PasteJournal.Entry entry = new PasteJournal.Entry();
        entry.sessionId = "no-schem";
        entry.schematicName = "deleted.schem";
        data.pastes.add(entry);
        journal.write(data);

        NativePasteEngine engine = new NativePasteEngine(kit.env);
        engine.setResumer(e -> null);
        engine.resumeFromJournal();
        engine.shutdown();

        assertEquals("no-schem", journal.read().pastes.get(0).sessionId);
    }

    @Test
    void 同じ印のエンティティが既にあれば二重に作らない() {
        Entity existing = mock(Entity.class);
        org.bukkit.persistence.PersistentDataContainer pdc = mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(existing.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(EntityPlacer.PASTE_TAG_KEY, PersistentDataType.STRING)).thenReturn(EntityPlacer.tagValue("s-1", 4));

        assertTrue(EntityPlacer.alreadyPlaced(List.of(existing), EntityPlacer.tagValue("s-1", 4)));
        assertFalse(EntityPlacer.alreadyPlaced(List.of(existing), EntityPlacer.tagValue("s-1", 5)));
        assertFalse(EntityPlacer.alreadyPlaced(List.of(existing), EntityPlacer.tagValue("s-2", 4)));
    }

    @Test
    void エンティティの段から再開してもそのチャンクだけを続きから終える() {
        // 模擬サーバーはエンティティの生成（createEntity）を実装していないため、二重生成の防止そのものは
        // 上の alreadyPlaced の試験で確かめ、ここでは途中の段（ENTITIES）の記録から再開して完了することを確かめる。
        SimpleNbt.CompoundTag nbt = new SimpleNbt.CompoundTag();
        StructureSchematic schematic = new PasteTestKit.SchemBuilder(2, 1, 2).fill(Material.STONE)
                .entity(1.0, 1.0, 1.0, "minecraft:armor_stand", nbt).build();

        PasteJournal.Entry entry = new PasteJournal.Entry();
        entry.sessionId = "resume-entities";
        entry.schematicName = "with-entity";
        entry.chunks.put(Long.toString(org.bukkit.Chunk.getChunkKey(0, 0)), new PasteJournal.ChunkProgress(PasteStage.ENTITIES, 0));
        kit.world.getBlockAt(0, 64, 0).setType(Material.DIRT);
        PasteEngine.PasteHandle again = kit.engine.resume(kit.request(schematic, 0, 64, 0, new PasteTestKit.TestMarkers())
                .schematicName("with-entity").build(), entry);
        kit.tickUntil(again::isDone, 200);

        assertTrue(again.isDone());
        assertEquals(PasteResult.Outcome.COMPLETED, again.completion().join().outcome());
        assertEquals(Material.DIRT, kit.world.getBlockAt(0, 64, 0).getType(), "ブロックの段は終わっているので書き直さない");
        assertTrue(kit.tickets.isEmpty());
        assertEquals(List.of(), kit.ticketViolations);
    }
}
