package com.magmaguy.betterstructures;

import com.magmaguy.betterstructures.config.modules.ModulesConfig;
import com.magmaguy.betterstructures.config.schematics.SchematicConfig;
import com.magmaguy.betterstructures.integration.NativeSchematicImport;
import com.magmaguy.betterstructures.integration.RegionProtection;
import com.magmaguy.betterstructures.integration.RegionProtectionSelector;
import com.magmaguy.betterstructures.integration.SchematicImport;
import com.magmaguy.betterstructures.schematic.reader.UnresolvedBlockReport;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.integration.WorldEditCompatibility;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U4（WorldEdit の分離と図面の読み込み）の持ち物のクラスが、WorldEdit / FAWE / WorldGuard が
 * クラスパスに無くても読み込め（検証・静的初期化が NoClassDefFoundError にならず）、呼べること。
 * このソースセットのクラスパスには worldedit-core / worldedit-bukkit / worldguard が入っていない。
 */
class OwnedClassesLoadWithoutWorldEditTest {

    @Test
    void nativeImportLoadsAndRecognizesSpongeFilesWithoutAServer() {
        NativeSchematicImport nativeImport = new NativeSchematicImport();
        assertTrue(nativeImport.canRead(Path.of("castle.schem")));
        assertFalse(nativeImport.canRead(Path.of("castle.schematic")));
        assertInstanceOf(NativeSchematicImport.class, NativeSchematicImport.withFallback(null));
    }

    @Test
    void regionProtectionSelectorFallsBackToNoneWithoutWorldGuard() {
        RegionProtection protection = RegionProtectionSelector.select(false);
        assertFalse(protection.isAvailable());
        assertFalse(protection.overlapsRegion(null, 0, 0, 1, 1));
    }

    @Test
    void worldEditCompatibilityLoadsAndNoWorldEditFailureIsReported() {
        assertNull(WorldEditCompatibility.firstFault());
        assertNull(WorldEditCompatibility.chatWarning());
        assertFalse(WorldEditCompatibility.report(new IllegalStateException("not from worldedit"), new java.io.File("x.schem")));
        WorldEditCompatibility.reset();
    }

    @Test
    void conversionReportAggregatesWithoutBukkit() {
        UnresolvedBlockReport report = new UnresolvedBlockReport();
        assertNull(report.summary());
        report.unresolvedBlock("a.schem", "minecraft:old");
        report.unresolvedBlock("a.schem", "minecraft:old");
        assertNotNull(report.summary());
        assertTrue(report.summary().contains("minecraft:old x2"));
        assertInstanceOf(SchematicImport.ConversionReport.class, report);
    }

    @Test
    void configurationAndContainerClassesLoadWithoutWorldEdit() {
        // 検証器が WorldEdit の型を探しに行くクラス（旧 Clipboard のフィールド・コンストラクタ・catch）が残っていないこと
        assertNotNull(ModulesConfig.getModuleConfigurations());
        assertNotNull(SchematicConfig.getSchematicConfigurations());
        SchematicContainer.shutdown();
    }
}
