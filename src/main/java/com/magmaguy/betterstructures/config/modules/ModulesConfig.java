package com.magmaguy.betterstructures.config.modules;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.modules.ModulesContainer;
import com.magmaguy.betterstructures.util.SchematicFileUtils;
import com.magmaguy.betterstructures.worldedit.SchematicClipboardCache;
import com.magmaguy.betterstructures.worldedit.SchematicConversionLog;
import com.magmaguy.betterstructures.worldedit.SchematicDiskCache;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematic.reader.SchematicReader;
import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.config.CustomConfig;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import lombok.Getter;
import org.bukkit.Bukkit;
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
    //Module schematics are parsed on a single thread, so re-reading unchanged ones on every content
    //reload costs even more per file here than it does for structures.
    private static final SchematicClipboardCache clipboardCache = new SchematicClipboardCache();

    public ModulesConfig() {
        super("modules", ModulesConfigFields.class);
        if (shutdownRequested()) return;
        File modulesFile = new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath()+ File.separatorChar + "modules");
        if (!modulesFile.exists()) modulesFile.mkdir();

        HashMap<File, Clipboard> clipboards = new HashMap<>();
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

        // Optionally load WorldEdit clipboards if WorldEdit is present
        boolean worldEditEnabled = Bukkit.getPluginManager().isPluginEnabled("WorldEdit");
        if (worldEditEnabled) {
            int forgotten = clipboardCache.retainOnly(discoveredModuleFiles);
            if (!discoveredModuleFiles.isEmpty()) {
                SchematicDiskCache diskCache =
                        new SchematicDiskCache(SchematicDiskCache.moduleCacheFolder());
                try (SchematicConversionLog.Session conversionLog = SchematicConversionLog.capture()) {
                    for (File file : discoveredModuleFiles) {
                        if (shutdownRequested()) return;
                        Clipboard clipboard = clipboardCache.get(file);
                        if (clipboard == null) {
                            SchematicDiskCache.LoadedClipboard loaded = diskCache.loadWithIdentity(file);
                            clipboard = loaded == null ? null : loaded.clipboard();
                            if (loaded != null) clipboardCache.put(file, loaded);
                        }
                        if (clipboard != null) {
                            clipboards.put(file, clipboard);
                        }
                    }
                } catch (Exception | LinkageError e) {
                    // WorldEdit failed or unsupported
                }
                if (diskCache.filesRead() > 0 || forgotten > 0)
                    diskCache.pruneStaleEntries(discoveredModuleFiles);
            }
        }

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
            Clipboard clipboard = source == null ? null : clipboards.get(source);

            if (schematic == null && clipboard == null) {
                throw new IllegalStateException(
                        "Enabled module configuration "
                                + modulesConfigFields.getFilename()
                                + " has no readable schematic "
                                + schematicFilename
                                + "; refusing to initialize a partial module registry.");
            }

            if (schematic != null) {
                ModulesContainer.initializeModulesContainer(
                        schematic,
                        schematicFilename,
                        modulesConfigFields,
                        modulesConfigFields.getFilename());
            } else {
                ModulesContainer.initializeModulesContainer(
                        clipboard,
                        schematicFilename,
                        modulesConfigFields,
                        modulesConfigFields.getFilename());
            }
        }

        if (!shutdownRequested()) ModulesContainer.postInitializeModulesContainer();

    }

    private static Map<File, StructureSchematic> loadStructureSchematics(List<File> files) {
        Map<File, StructureSchematic> result = new LinkedHashMap<>();
        if (files == null || files.isEmpty()) return result;

        files.parallelStream().forEach(file -> {
            StructureSchematic cached = structureSchematicCache.get(file);
            if (cached != null) return;
            try {
                StructureSchematic parsed = SchematicReader.read(file);
                if (parsed != null) {
                    structureSchematicCache.put(file, parsed);
                }
            } catch (Exception e) {
                // Ignore
            }
        });

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
