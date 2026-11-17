package com.magmaguy.betterstructures.config.modules;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.integration.NativeSchematicImport;
import com.magmaguy.betterstructures.integration.SchematicImport;
import com.magmaguy.betterstructures.modules.ModulesContainer;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematic.reader.UnresolvedBlockReport;
import com.magmaguy.betterstructures.util.SchematicFileUtils;
import com.magmaguy.betterstructures.worldedit.SchematicConversionLog;
import com.magmaguy.betterstructures.worldedit.WorldEditCompatibility;
import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.config.CustomConfig;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModulesConfig extends CustomConfig {
    @Getter
    private static final HashMap<String, ModulesConfigFields> moduleConfigurations = new HashMap<>();
    private static final Map<File, StructureSchematic> structureSchematicCache = new ConcurrentHashMap<>();

    public ModulesConfig() {
        super("modules", ModulesConfigFields.class);
        if (shutdownRequested()) return;
        File modulesFile = new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath()+ File.separatorChar + "modules");
        if (!modulesFile.exists()) modulesFile.mkdir();

        //Initialize schematics
        File[] moduleFiles = modulesFile.listFiles();
        List<File> discoveredModuleFiles = new ArrayList<>();
        if (moduleFiles == null) throw new java.io.UncheckedIOException(
                new java.io.IOException("Could not enumerate module root: " + modulesFile));
        {
            for (File file : moduleFiles) {
                SchematicFileUtils.scanDirectoryForSchematics(file, discoveredModuleFiles);
            }
        }
        discoveredModuleFiles.sort(
                Comparator.comparing(File::getAbsolutePath));
        Map<String, File> moduleSourcesByFilename = new HashMap<>();

        for (File file : discoveredModuleFiles) {
            File previous = moduleSourcesByFilename.putIfAbsent(file.getName(), file);
            if (previous != null && !previous.equals(file)) {
                throw new IllegalStateException(
                        "Duplicate module schematic filename '"
                                + file.getName() + "' exists at both "
                                + previous.getPath() + " and "
                                + file.getPath()
                                + "; module configuration lookup would be ambiguous.");
            }
        }

        // Load structure schematics natively using Folia-safe Pure Java reader
        Map<File, StructureSchematic> structureSchematics = loadStructureSchematics(discoveredModuleFiles);

        if (shutdownRequested()) return;
        moduleConfigurations.clear();
        ModulesContainer.initializeSpecialModules();
        for (File file : discoveredModuleFiles) {
            if (shutdownRequested()) return;
            String configurationName = SchematicFileUtils.convertFromSchematicFilename(file.getName());
            ModulesConfigFields moduleConfigField = new ModulesConfigFields(configurationName, true);
            new CustomConfig(file.getParent().replace(
                    MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar, ""),
                    ModulesConfigFields.class, moduleConfigField);
            moduleConfigurations.put(configurationName, moduleConfigField);
        }

        ModulesConfigFields.validateCloneGraph(moduleConfigurations.values());
        moduleConfigurations.values().forEach(ModulesConfigFields::validateClones);

        for (ModulesConfigFields modulesConfigFields : moduleConfigurations.values()) {
            if (shutdownRequested()) return;
            if (!modulesConfigFields.isEnabled()) continue;
            String schematicFilename = SchematicFileUtils.convertFromConfigurationFilename(modulesConfigFields.getFilename());
            File source = moduleSourcesByFilename.get(schematicFilename);
            StructureSchematic schematic = source == null ? null : structureSchematics.get(source);

            if (schematic == null) {
                throw new IllegalStateException(
                        "Enabled module configuration "
                                + modulesConfigFields.getFilename()
                                + " has no readable schematic "
                                + schematicFilename
                                + "; refusing to initialize a partial module registry.");
            }

            ModulesContainer.initializeModulesContainer(
                    schematic,
                    schematicFilename,
                    modulesConfigFields,
                    modulesConfigFields.getFilename());
        }

        if (!shutdownRequested()) ModulesContainer.postInitializeModulesContainer();

    }

    private static Map<File, StructureSchematic> loadStructureSchematics(List<File> files) {
        Map<File, StructureSchematic> result = new LinkedHashMap<>();
        if (files == null || files.isEmpty()) return result;

        // ネイティブで読めない図面だけ、WorldEdit/FAWE が有効なら WorldEdit 実装で読む（貼り付けは常に独自エンジン）
        SchematicImport schematicImport = NativeSchematicImport.withFallback(WorldEditCompatibility.createFallbackImport());
        UnresolvedBlockReport unresolvedBlocks = new UnresolvedBlockReport();
        // 古い版の変換でサーバーが出す大量のログを1行にまとめる
        try (SchematicConversionLog.Session conversionLog = SchematicConversionLog.capture()) {
            files.parallelStream().forEach(file -> {
                StructureSchematic cached = structureSchematicCache.get(file);
                if (cached != null) return;
                try {
                    StructureSchematic parsed = schematicImport.read(file.toPath(), unresolvedBlocks);
                    if (parsed != null) {
                        structureSchematicCache.put(file, parsed);
                    }
                } catch (Exception e) {
                    Logger.warn("モジュールの図面を読めませんでした " + file.getPath() + ": " + e.getMessage());
                }
            });
        }
        // 変換できずに空気にしたブロックは、読み込みの最後に1回だけ警告する
        unresolvedBlocks.logSummary();

        for (File file : files) {
            StructureSchematic parsed = structureSchematicCache.get(file);
            if (parsed != null) {
                result.put(file, parsed);
            }
        }
        return result;
    }

    private static boolean shutdownRequested() {
        return MagmaCore.isShutdownRequested((JavaPlugin) MetadataHandler.PLUGIN);
    }

    public static ModulesConfigFields getModuleConfiguration(String filename) {
        return moduleConfigurations.get(filename);
    }
}
