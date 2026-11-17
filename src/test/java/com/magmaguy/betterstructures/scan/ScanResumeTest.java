package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType;
import com.magmaguy.betterstructures.paste.PasteJournal;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 地形走査が始めた貼り付けの記録の作り直し。種類の推定と、引けない図面の扱い。 */
final class ScanResumeTest {

    private static PasteJournal.Entry entry(boolean fillPedestal, boolean clearTrees) {
        PasteJournal.Entry entry = new PasteJournal.Entry();
        entry.fillPedestal = fillPedestal;
        entry.clearTrees = clearTrees;
        return entry;
    }

    private static SchematicContainer container(StructureType... types) {
        SchematicContainer container = mock(SchematicContainer.class);
        GeneratorConfigFields generator = mock(GeneratorConfigFields.class);
        when(generator.getStructureTypes()).thenReturn(List.of(types));
        when(container.getGeneratorConfigFields()).thenReturn(generator);
        return container;
    }

    @Test
    void 木を除去していれば地表_台座が無ければ空中か水面_それ以外は地中() {
        assertEquals(StructureType.SURFACE, ScanResume.inferKind(entry(true, true), container(StructureType.SURFACE)));
        assertEquals(StructureType.SKY, ScanResume.inferKind(entry(false, false), container(StructureType.SKY)));
        assertEquals(StructureType.LIQUID_SURFACE,
                ScanResume.inferKind(entry(false, false), container(StructureType.LIQUID_SURFACE, StructureType.SKY)));
        assertEquals(StructureType.UNDERGROUND_DEEP,
                ScanResume.inferKind(entry(true, false), container(StructureType.UNDERGROUND_DEEP)));
        assertEquals(StructureType.UNDERGROUND_SHALLOW, ScanResume.inferKind(entry(true, false), container()));
    }

    @Test
    void 登録の無い図面名は作り直せず_nullを返す() {
        PasteJournal.Entry entry = entry(true, true);
        entry.worldId = java.util.UUID.randomUUID().toString();
        entry.schematicName = "never-registered.yml";
        assertNull(ScanResume.rebuild(entry, fit -> null, ScanEvents.BUKKIT));
        entry.schematicName = null;
        assertNull(ScanResume.rebuild(entry, fit -> null, ScanEvents.BUKKIT));
    }
}
