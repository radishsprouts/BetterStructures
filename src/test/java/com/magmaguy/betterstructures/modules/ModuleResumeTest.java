package com.magmaguy.betterstructures.modules;

import com.magmaguy.betterstructures.integration.EliteMobsHook;
import com.magmaguy.betterstructures.paste.PasteJournal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ダンジョンのモジュールの記録の鍵（図面名）の形と、引けない時の扱い。 */
final class ModuleResumeTest {

    @Test
    void 設定名があれば鍵に入れ無ければモジュール名そのまま() {
        assertEquals("dungeon|gen.yml|_x|module_a", ModuleResume.encode("gen.yml", "_x", "module_a"));
        assertEquals("dungeon|gen.yml||module_a", ModuleResume.encode("gen.yml", null, "module_a"));
        assertEquals("module_a", ModuleResume.encode(null, "_x", "module_a"));
        assertEquals("module_a", ModuleResume.encode("", "_x", "module_a"));
    }

    @Test
    void ダンジョンの記録だけを扱う() {
        PasteJournal.Entry dungeon = new PasteJournal.Entry();
        dungeon.schematicName = "dungeon|gen.yml||module_a";
        PasteJournal.Entry building = new PasteJournal.Entry();
        building.schematicName = "castle.yml";
        assertTrue(ModuleResume.handles(dungeon));
        assertFalse(ModuleResume.handles(building));
        assertFalse(ModuleResume.handles(null));
    }

    @Test
    void 設定もモジュールも無ければ作り直せず_nullを返す() {
        PasteJournal.Entry entry = new PasteJournal.Entry();
        entry.schematicName = "dungeon|missing.yml||module_a";
        entry.worldId = java.util.UUID.randomUUID().toString();
        assertNull(ModuleResume.rebuild(entry, EliteMobsHook.none()));
        entry.schematicName = "dungeon|broken";
        assertNull(ModuleResume.rebuild(entry, EliteMobsHook.none()), "形が違う記録でも例外にしない");
    }
}
