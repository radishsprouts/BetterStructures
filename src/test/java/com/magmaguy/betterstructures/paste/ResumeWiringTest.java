package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.BetterStructures;
import com.magmaguy.betterstructures.integration.EliteMobsHook;
import com.magmaguy.betterstructures.runtime.GenerationGovernor;
import com.magmaguy.betterstructures.runtime.Services;
import com.magmaguy.betterstructures.scan.ScanCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 起動時の配線（{@link BetterStructures#wireNativePaste}）: エンジンが受け渡し口に入り、停止をまたぐ再開で
 * 走査の待ち行列が走査側へ戻り、引けない図面の記録は捨てずに残ること。
 */
final class ResumeWiringTest {

    @TempDir
    Path temp;
    private PasteTestKit kit;

    @BeforeEach
    void setUp() {
        kit = new PasteTestKit(temp.resolve("journal.json"));
    }

    @AfterEach
    void tearDown() {
        GenerationGovernor.instance().clear();
        ScanCoordinator.shutdown();
        Services.clear();
        kit.close();
    }

    @Test
    void 配線したエンジンが入り走査の記録は走査側へ戻り引けない貼り付けは残る() throws IOException {
        PasteJournal journal = new PasteJournal(temp.resolve("journal.json"));
        PasteJournal.Data data = new PasteJournal.Data();
        String worldId = kit.world.getUID().toString();
        PasteJournal.Entry paste = new PasteJournal.Entry();
        paste.sessionId = "lost";
        paste.worldId = worldId;
        paste.schematicName = "no-such-structure.yml";
        data.pastes.add(paste);
        PasteJournal.Entry dungeon = new PasteJournal.Entry();
        dungeon.sessionId = "lost-dungeon";
        dungeon.worldId = worldId;
        dungeon.schematicName = "dungeon|no_generator.yml||module_a";
        data.pastes.add(dungeon);
        data.scans.add(new PasteJournal.ScanEntry(worldId, 5, -7, "SURFACE"));
        journal.write(data);

        NativePasteEngine engine = new NativePasteEngine(kit.env);
        assertSame(engine, BetterStructures.wireNativePaste(engine, EliteMobsHook.none()));
        assertSame(engine, Services.pasteEngineOrNull());

        engine.resumeFromJournal();
        kit.tickUntil(() -> !ScanCoordinator.journalEntries().isEmpty(), 2000);

        var restored = ScanCoordinator.journalEntries();
        assertEquals(1, restored.size(), "記録の走査の依頼が走査の待ち行列へ戻る");
        assertEquals(5, restored.get(0).chunkX);
        assertEquals(-7, restored.get(0).chunkZ);
        assertEquals("SURFACE", restored.get(0).kind);
        assertEquals(worldId, restored.get(0).worldId);

        engine.shutdown();
        PasteJournal.Data after = journal.read();
        assertEquals(2, after.pastes.size(), "引けない図面・設定の記録を捨てない");
        assertTrue(after.pastes.stream().anyMatch(e -> e.sessionId.equals("lost")));
        assertTrue(after.pastes.stream().anyMatch(e -> e.sessionId.equals("lost-dungeon")));
        assertEquals(1, after.scans.size(), "停止時に、まだ走っていない走査の依頼を記録へ載せ直す");
    }
}
