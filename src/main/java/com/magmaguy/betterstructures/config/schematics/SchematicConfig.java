package com.magmaguy.betterstructures.config.schematics;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.integration.NativeSchematicImport;
import com.magmaguy.betterstructures.integration.SchematicImport;
import com.magmaguy.betterstructures.schematic.reader.UnresolvedBlockReport;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.util.SchematicFileUtils;
import com.magmaguy.betterstructures.integration.SchematicConversionLog;
import com.magmaguy.betterstructures.integration.WorldEditCompatibility;
import com.magmaguy.magmacore.config.CustomConfig;
import com.magmaguy.magmacore.config.ContentFileSelector;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SchematicConfig extends CustomConfig {
    private static final Duration LOAD_SHUTDOWN_TIMEOUT = Duration.ofSeconds(15);
    private static final SchematicLoadCoordinator loadCoordinator =
            new SchematicLoadCoordinator();
    @Getter
    private static final HashMap<String, SchematicConfigField> schematicConfigurations = new HashMap<>();
    // In-memory cache of parsed StructureSchematics for zero-disk-IO reuse across reloads
    private static final Map<File, com.magmaguy.betterstructures.schematic.model.StructureSchematic> structureSchematicCache = new ConcurrentHashMap<>();

    public static void prepareForEnable() {
        loadCoordinator.open();
    }

    /**
     * Prevents new schematic loads, cancels the active generation, and waits for its worker pool and
     * owning initialization thread to leave the load method.
     *
     * @return true when no schematic-loading code remains active
     */
    public static boolean shutdownLoading() {
        return loadCoordinator.shutdownAndAwait(LOAD_SHUTDOWN_TIMEOUT);
    }

    public SchematicConfig() {
        super("schematics", SchematicConfigField.class);
        File readMeFile = new File(MetadataHandler.PLUGIN.getDataFolder(), "schematics" + File.separatorChar + "ReadMe.txt");
        if (!readMeFile.exists()) {
            readMeFile.getParentFile().mkdirs();
            MetadataHandler.PLUGIN.saveResource("schematics" + File.separatorChar + "ReadMe.txt", false);
        }

        //Initialize schematics
        long phaseStart = System.nanoTime();
        List<File> schematicFilesList = new ArrayList<>();
        File[] schematicFiles = readMeFile.getParentFile().listFiles();
        if (schematicFiles == null) throw new java.io.UncheckedIOException(
                new java.io.IOException("Could not enumerate schematic root: " + readMeFile.getParentFile()));
        for (File file : schematicFiles) SchematicFileUtils.scanDirectoryForSchematics(file, schematicFilesList);
        // Resolve filename collisions before reading clipboards or generating their configurations.
        schematicFilesList = ContentFileSelector.select(schematicFilesList);

        Map<File, com.magmaguy.betterstructures.schematic.model.StructureSchematic> structureSchematics = loadStructureSchematics(schematicFilesList);
        phaseStart = logPhase("read " + structureSchematics.size() + " schematic files", phaseStart);

        //Filename -> source file, so the container loop below can look its schematic up directly.
        Map<String, File> sourceByFilename = new HashMap<>();
        for (Map.Entry<File, com.magmaguy.betterstructures.schematic.model.StructureSchematic> entry : structureSchematics.entrySet()) {
            sourceByFilename.put(
                    entry.getKey().getName(),
                    entry.getKey());
        }

        schematicConfigurations.clear();
        for (File file : structureSchematics.keySet()) {
            String configurationName = SchematicFileUtils.convertFromSchematicFilename(file.getName());
            SchematicConfigField schematicConfigField = new SchematicConfigField(configurationName, true);
            new CustomConfig(relativizeToDataFolder(file.getParentFile()),
                    SchematicConfigField.class, schematicConfigField);
            schematicConfigurations.put(configurationName, schematicConfigField);
        }
        phaseStart = logPhase("load " + schematicConfigurations.size() + " schematic configurations", phaseStart);

        for (SchematicConfigField schematicConfigField : schematicConfigurations.values()) {
            if (!schematicConfigField.isEnabled()) continue;
            String schematicFilename = SchematicFileUtils.convertFromConfigurationFilename(schematicConfigField.getFilename());
            File source = sourceByFilename.get(schematicFilename);
            com.magmaguy.betterstructures.schematic.model.StructureSchematic schematic = source == null ? null : structureSchematics.get(source);
            new SchematicContainer(
                    schematic,
                    schematicFilename,
                    schematicConfigField,
                    schematicConfigField.getFilename());
        }
        logPhase("scan schematic contents", phaseStart);
    }

    private static Map<File, com.magmaguy.betterstructures.schematic.model.StructureSchematic> loadStructureSchematics(List<File> schematicFiles) {
        Map<File, com.magmaguy.betterstructures.schematic.model.StructureSchematic> result = new LinkedHashMap<>();
        if (schematicFiles == null || schematicFiles.isEmpty()) return result;

        // ネイティブで読めない図面だけ、WorldEdit/FAWE が有効なら WorldEdit 実装で読む（貼り付けは常に独自エンジン）
        SchematicImport schematicImport = NativeSchematicImport.withFallback(WorldEditCompatibility.createFallbackImport());
        UnresolvedBlockReport unresolvedBlocks = new UnresolvedBlockReport();
        WorldEditCompatibility.reset();
        // Parallel parsing using common ForkJoinPool (pure Java, zero locks)。
        // 古い版の変換でサーバーが出す大量のログは1行にまとめる
        try (SchematicConversionLog.Session conversionLog = SchematicConversionLog.capture()) {
            schematicFiles.parallelStream().forEach(file -> {
                com.magmaguy.betterstructures.schematic.model.StructureSchematic cached = structureSchematicCache.get(file);
                if (cached != null) {
                    return;
                }
                try {
                    com.magmaguy.betterstructures.schematic.model.StructureSchematic parsed =
                            schematicImport.read(file.toPath(), unresolvedBlocks);
                    if (parsed != null) {
                        structureSchematicCache.put(file, parsed);
                    }
                } catch (Exception e) {
                    Logger.warn("Failed to read schematic file " + file.getPath() + ": " + e.getMessage());
                }
            });
        }
        // 変換できずに空気にしたブロックは、読み込みの最後に1回だけ警告する
        unresolvedBlocks.logSummary();

        // Insert in stable, sorted file order
        for (File file : schematicFiles) {
            com.magmaguy.betterstructures.schematic.model.StructureSchematic parsed = structureSchematicCache.get(file);
            if (parsed != null) {
                result.put(file, parsed);
            }
        }
        return result;
    }

    /**
     * Reports how long a phase of schematic loading took, so the three very different costs hiding
     * inside the single "Schematics" initialization step (reading .schem files, generating/reading
     * their configurations, and scanning their block contents) can be told apart.
     * <p>
     * Enabled by the same -Dmagmacore.inittiming=true used for the per-step initialization profile.
     *
     * @return the timestamp to measure the next phase from
     */
    private static long logPhase(String description, long phaseStartNanos) {
        long now = System.nanoTime();
        if (Boolean.getBoolean("magmacore.inittiming"))
            Logger.info(String.format("  [schematics] %6d ms  %s", (now - phaseStartNanos) / 1_000_000, description));
        return now;
    }

    /**
     * Returns a folder path relative to the plugin's data folder, which is what CustomConfig and
     * ConfigurationEngine.fileCreator expect — they resolve it against getDataFolder().getPath().
     * <p>
     * This used to be done by string-replacing the data folder's ABSOLUTE path out of
     * file.getParent(). Bukkit hands out a RELATIVE data folder ("plugins/BetterStructures"), so
     * the schematic files walked from it also carry relative parents and the replace never matched.
     * The full relative parent was then passed through as if it were a subfolder name, and configs
     * were written to plugins/BetterStructures/plugins/BetterStructures/schematics/... — a path
     * nothing ever reads back.
     * <p>
     * The consequence was not just wasted writes: every shipped DLC schematic config (which does
     * set generatorConfigFilename) was missed, a blank one was generated in the stray tree instead,
     * and so every one of those structures ended up with no generator and never spawned.
     * <p>
     * Both sides are made absolute and normalised here so the relativize works no matter which form
     * the server hands us.
     */
    private static String relativizeToDataFolder(File folder) {
        Path dataFolder = MetadataHandler.PLUGIN.getDataFolder().getAbsoluteFile().toPath().normalize();
        Path target = folder.getAbsoluteFile().toPath().normalize();
        if (target.equals(dataFolder)) return "";
        if (target.startsWith(dataFolder)) return dataFolder.relativize(target).toString();
        //Outside the data folder entirely (symlinked content pack, unusual setup): fall back to the
        //schematics root rather than writing somewhere unpredictable.
        Logger.warn("Schematic folder " + folder.getPath() + " is outside the BetterStructures data folder; " +
                "its configuration will be stored under schematics instead.");
        return "schematics";
    }

    public static SchematicConfigField getSchematicConfiguration(String filename) {
        return schematicConfigurations.get(filename);
    }
}
