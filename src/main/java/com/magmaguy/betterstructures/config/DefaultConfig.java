package com.magmaguy.betterstructures.config;

import com.magmaguy.betterstructures.runtime.FlowSettings;
import com.magmaguy.betterstructures.runtime.RuntimeSettings;
import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginUpdater;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;

import java.util.List;

public class DefaultConfig extends ConfigurationFile {
    private static final int DEFAULT_DISTANCE_SURFACE = 27;
    private static final int DEFAULT_DISTANCE_SHALLOW = 22;
    private static final int DEFAULT_DISTANCE_DEEP = 22;
    private static final int DEFAULT_DISTANCE_SKY = 90;
    private static final int DEFAULT_DISTANCE_LIQUID = 60;
    private static final int DEFAULT_DISTANCE_DUNGEON = 80;
    private static final int DEFAULT_MAX_OFFSET = 5;
    private static final int DEFAULT_MAX_OFFSET_DUNGEON = 18;
    // nextInt requires a positive int bound for (2 * offset + 1).
    private static final int MAX_SAFE_OFFSET = (Integer.MAX_VALUE - 1) / 2;
    @Getter
    private static int lowestYNormalCustom;
    @Getter
    private static int highestYNormalCustom;
    @Getter
    private static int lowestYNether;
    @Getter
    private static int highestYNether;
    @Getter
    private static int lowestYEnd;
    @Getter
    private static int highestYEnd;
    @Getter
    private static int normalCustomAirBuildingMinAltitude;
    @Getter
    private static int normalCustomAirBuildingMaxAltitude;
    @Getter
    private static int endAirBuildMinAltitude;
    @Getter
    private static int endAirBuildMaxAltitude;
    @Getter
    private static boolean newBuildingWarn;
    @Getter
    private static String regionProtectedMessage;
    @Getter
    private static boolean protectEliteMobsRegions;
    private static DefaultConfig instance;
    @Getter
    private static boolean setupDone;
    @Getter
    private static int modularChunkPastingSpeed = 10;
    @Getter
    private static double percentageOfTickUsedForPasting = 0.2;
    @Getter
    private static double percentageOfTickUsedForPregeneration = 0.1;
    @Getter
    private static double pregenerationTPSPauseThreshold = 12.0;
    @Getter
    private static double pregenerationTPSResumeThreshold = 14.0;

    // Adding getters for the new distance and offset variables
    @Getter
    private static int distanceSurface;
    @Getter
    private static int distanceShallow;
    @Getter
    private static int distanceDeep;
    @Getter
    private static int distanceSky;
    @Getter
    private static int distanceLiquid;
    @Getter
    private static int distanceDungeon;

    @Getter
    private static int maxOffsetSurface;
    @Getter
    private static int maxOffsetShallow;
    @Getter
    private static int maxOffsetDeep;
    @Getter
    private static int maxOffsetSky;
    @Getter
    private static int maxOffsetLiquid;
    @Getter
    private static int maxOffsetDungeon;

    @Getter
    private static int spawnProtectionRadius;

    public DefaultConfig() {
        super("config.yml");
        instance = this;
    }

    public static void toggleSetupDone() {
        setupDone = !setupDone;
        ConfigurationEngine.writeValue(setupDone, instance.file, instance.getFileConfiguration(), "setupDone");
    }

    public static void toggleSetupDone(boolean value) {
        setupDone = value;
        ConfigurationEngine.writeValue(setupDone, instance.file, instance.getFileConfiguration(), "setupDone");
    }


    public static boolean toggleWarnings() {
        newBuildingWarn = !newBuildingWarn;
        ConfigurationEngine.writeValue(newBuildingWarn, instance.file, instance.fileConfiguration, "warnAdminsAboutNewBuildings");
        return newBuildingWarn;
    }

    @Override
    public void initializeValues() {
        lowestYNormalCustom = ConfigurationEngine.setInt(fileConfiguration, "lowestYNormalCustom", -60);
        highestYNormalCustom = ConfigurationEngine.setInt(fileConfiguration, "highestYNormalCustom", 320);
        lowestYNether = ConfigurationEngine.setInt(fileConfiguration, "lowestYNether", 4);
        highestYNether = ConfigurationEngine.setInt(fileConfiguration, "highestYNether", 120);
        lowestYEnd = ConfigurationEngine.setInt(fileConfiguration, "lowestYEnd", 0);
        highestYEnd = ConfigurationEngine.setInt(fileConfiguration, "highestYEnd", 320);
        normalCustomAirBuildingMinAltitude = ConfigurationEngine.setInt(fileConfiguration, "normalCustomAirBuildingMinAltitude", 80);
        normalCustomAirBuildingMaxAltitude = ConfigurationEngine.setInt(fileConfiguration, "normalCustomAirBuildingMaxAltitude", 120);
        endAirBuildMinAltitude = ConfigurationEngine.setInt(fileConfiguration, "endAirBuildMinAltitude", 80);
        endAirBuildMaxAltitude = ConfigurationEngine.setInt(fileConfiguration, "endAirBuildMaxAltitude", 120);
        newBuildingWarn = ConfigurationEngine.setBoolean(fileConfiguration, "warnAdminsAboutNewBuildings", true);
        regionProtectedMessage = ConfigurationEngine.setString(fileConfiguration, "regionProtectedMessage", "&8[BetterStructures] &cDefeat the zone's bosses to edit blocks!");
        protectEliteMobsRegions = ConfigurationEngine.setBoolean(fileConfiguration, "protectEliteMobsRegions", true);
        setupDone = ConfigurationEngine.setBoolean(fileConfiguration, "setupDone", false);
        modularChunkPastingSpeed = ConfigurationEngine.setInt(fileConfiguration, "modularChunkPastingSpeed", 10);
        percentageOfTickUsedForPasting = ConfigurationEngine.setDouble(List.of("Sets the maximum percentage of a tick that BetterStructures will use to paste builds, however many it maybe trying to generate.", "Ranges from 0.01 to 1, where 0.01 is 1% and 1 is 100%.", "Slower speeds will lower performance impact, but can lead to other problems such as builds suddenly popping in."),fileConfiguration, "percentageOfTickUsedForPasting", 0.2);
        percentageOfTickUsedForPregeneration = ConfigurationEngine.setDouble(List.of("Sets the maximum percentage of a tick that BetterStructures will use for world pregeneration when using the pregenerate command.", "Ranges from 0.01 to 1, where 0.01 is 1% and 1 is 100%.", "This controls how much of each server tick is dedicated to generating chunks, allowing you to balance generation speed with server performance.", "Lower values will generate chunks more slowly but reduce server lag, while higher values will generate faster but may impact server performance."), fileConfiguration, "percentageOfTickUsedForPregeneration", 0.1);
        pregenerationTPSPauseThreshold = ConfigurationEngine.setDouble(List.of("The TPS threshold at which chunk pregeneration will pause to protect server performance.", "When server TPS drops below this value, pregeneration will pause until TPS recovers.", "Default: 12.0"), fileConfiguration, "pregenerationTPSPauseThreshold", 12.0);
        pregenerationTPSResumeThreshold = ConfigurationEngine.setDouble(List.of("The TPS threshold at which chunk pregeneration will resume after being paused.", "Pregeneration will only resume when server TPS is at or above this value.", "Should be higher than the pause threshold to prevent rapid pause/resume cycles.", "Default: 14.0"), fileConfiguration, "pregenerationTPSResumeThreshold", 14.0);
        NightbreakPluginUpdater.setAutoDownloadConfigDefault(fileConfiguration);

        // Initialize the distances from configuration
        distanceSurface = validatedDistance("distanceSurface", ConfigurationEngine.setInt(
                List.of(
                        "Sets the distance between structures in the surface of a world.",
                        "Shorter distances between structures will result in more structures overall.",
                        "Must be at least 1. Invalid values use the default of " + DEFAULT_DISTANCE_SURFACE + "."),
                fileConfiguration, "distanceSurface", DEFAULT_DISTANCE_SURFACE), DEFAULT_DISTANCE_SURFACE);
        distanceShallow = validatedDistance("distanceShallow", ConfigurationEngine.setInt(
                List.of(
                        "Sets the distance between structures in shallow underground structure generation.",
                        "Shorter distances between structures will result in more structures overall.",
                        "Must be at least 1. Invalid values use the default of " + DEFAULT_DISTANCE_SHALLOW + "."),
                fileConfiguration, "distanceShallow", DEFAULT_DISTANCE_SHALLOW), DEFAULT_DISTANCE_SHALLOW);
        distanceDeep = validatedDistance("distanceDeep", ConfigurationEngine.setInt(
                List.of(
                        "Sets the distance between structures in deep underground structure generation.",
                        "Shorter distances between structures will result in more structures overall.",
                        "Must be at least 1. Invalid values use the default of " + DEFAULT_DISTANCE_DEEP + "."),
                fileConfiguration, "distanceDeep", DEFAULT_DISTANCE_DEEP), DEFAULT_DISTANCE_DEEP);
        distanceSky = validatedDistance("distanceSky", ConfigurationEngine.setInt(
                List.of(
                        "Sets the distance between structures in placed in the air.",
                        "Shorter distances between structures will result in more structures overall.",
                        "Must be at least 1. Invalid values use the default of " + DEFAULT_DISTANCE_SKY + "."),
                fileConfiguration, "distanceSky", DEFAULT_DISTANCE_SKY), DEFAULT_DISTANCE_SKY);
        distanceLiquid = validatedDistance("distanceLiquid", ConfigurationEngine.setInt(
                List.of(
                        "Sets the distance between structures liquid surfaces such as oceans.",
                        "Shorter distances between structures will result in more structures overall.",
                        "Must be at least 1. Invalid values use the default of " + DEFAULT_DISTANCE_LIQUID + "."),
                fileConfiguration, "distanceLiquid", DEFAULT_DISTANCE_LIQUID), DEFAULT_DISTANCE_LIQUID);
        distanceDungeon = validatedDistance("distanceDungeonV2", ConfigurationEngine.setInt(
                List.of(
                        "Sets the distance between dungeons.",
                        "Shorter distances between dungeons will result in more dungeons overall.",
                        "Must be at least 1. Invalid values use the default of " + DEFAULT_DISTANCE_DUNGEON + "."
                ),
                fileConfiguration, "distanceDungeonV2", DEFAULT_DISTANCE_DUNGEON), DEFAULT_DISTANCE_DUNGEON);

        // Initialize the maximum offsets from configuration
        maxOffsetSurface = validatedOffset("maxOffsetSurface", ConfigurationEngine.setInt(
                List.of(
                        "Used to tweak the randomization of the distance between structures in the surface of a world.",
                        "Smaller values will result in structures being more on a grid, and larger values will result in them being less predictably placed.",
                        offsetValidationDescription(DEFAULT_MAX_OFFSET)),
                fileConfiguration, "maxOffsetSurface", DEFAULT_MAX_OFFSET), DEFAULT_MAX_OFFSET);
        maxOffsetShallow = validatedOffset("maxOffsetShallow", ConfigurationEngine.setInt(
                List.of(
                        "Used to tweak the randomization of the distance between structures in the shallow underworld of a world.",
                        "Smaller values will result in structures being more on a grid, and larger values will result in them being less predictably placed.",
                        offsetValidationDescription(DEFAULT_MAX_OFFSET)),
                fileConfiguration, "maxOffsetShallow", DEFAULT_MAX_OFFSET), DEFAULT_MAX_OFFSET);
        maxOffsetDeep = validatedOffset("maxOffsetDeep", ConfigurationEngine.setInt(
                List.of(
                        "Used to tweak the randomization of the distance between structures in the deep underground of a world.",
                        "Smaller values will result in structures being more on a grid, and larger values will result in them being less predictably placed.",
                        offsetValidationDescription(DEFAULT_MAX_OFFSET)),
                fileConfiguration, "maxOffsetDeep", DEFAULT_MAX_OFFSET), DEFAULT_MAX_OFFSET);
        maxOffsetSky = validatedOffset("maxOffsetSky", ConfigurationEngine.setInt(
                List.of(
                        "Used to tweak the randomization of the distance between structures in the sky.",
                        "Smaller values will result in structures being more on a grid, and larger values will result in them being less predictably placed.",
                        offsetValidationDescription(DEFAULT_MAX_OFFSET)),
                fileConfiguration, "maxOffsetSky", DEFAULT_MAX_OFFSET), DEFAULT_MAX_OFFSET);
        maxOffsetLiquid = validatedOffset("maxOffsetLiquid", ConfigurationEngine.setInt(
                List.of(
                        "Used to tweak the randomization of the distance between structures on oceans.",
                        "Smaller values will result in structures being more on a grid, and larger values will result in them being less predictably placed.",
                        offsetValidationDescription(DEFAULT_MAX_OFFSET)),
                fileConfiguration, "maxOffsetLiquid", DEFAULT_MAX_OFFSET), DEFAULT_MAX_OFFSET);
        maxOffsetDungeon = validatedOffset("maxOffsetDungeonV2", ConfigurationEngine.setInt(
                List.of(
                        "Used to tweak the randomization of the distance between dungeons.",
                        "Smaller values will result in dungeons being more on a grid, and larger values will result in them being less predictably placed.",
                        offsetValidationDescription(DEFAULT_MAX_OFFSET_DUNGEON)),
                fileConfiguration, "maxOffsetDungeonV2", DEFAULT_MAX_OFFSET_DUNGEON), DEFAULT_MAX_OFFSET_DUNGEON);

        spawnProtectionRadius = ConfigurationEngine.setInt(
                List.of(
                        "Sets the minimum distance (in blocks) from world spawn (coordinates 0, 0) within which no structures will be placed.",
                        "This applies to all worlds. Set to 0 to disable spawn protection."),
                fileConfiguration, "spawnProtectionRadius", 100);

        initializeFlowSettings();

        ConfigurationEngine.fileSaverOnlyDefaults(fileConfiguration, file);
    }

    /**
     * Reads the generation flow control settings. Missing keys are appended with their defaults by
     * {@code fileSaverOnlyDefaults}, existing keys keep their meaning. Out-of-range values fall back to the
     * default with a single warning.
     */
    private void initializeFlowSettings() {
        FlowSettings d = FlowSettings.defaults();
        int scanSlots = validatedInt("generationScanSlots", ConfigurationEngine.setInt(
                List.of(
                        "How many terrain scans for new structures may run at the same time.",
                        "Scans beyond this number wait in line and are processed in order, none are dropped.",
                        "Must be between 1 and 64. Invalid values use the default of " + d.scanSlots() + "."),
                fileConfiguration, "generationScanSlots", d.scanSlots()), 1, 64, d.scanSlots());
        int pasteSlots = validatedInt("generationPasteSlots", ConfigurationEngine.setInt(
                List.of(
                        "How many structures may be pasted at the same time.",
                        "Structures beyond this number wait in line and are pasted in order, none are dropped.",
                        "Must be between 1 and 64. Invalid values use the default of " + d.pasteSlots() + "."),
                fileConfiguration, "generationPasteSlots", d.pasteSlots()), 1, 64, d.pasteSlots());
        int wfcSlots = validatedInt("generationDungeonSlots", ConfigurationEngine.setInt(
                List.of(
                        "How many dungeons (wave function collapse) may be generated at the same time.",
                        "Must be between 1 and 16. Invalid values use the default of " + d.wfcSlots() + "."),
                fileConfiguration, "generationDungeonSlots", d.wfcSlots()), 1, 16, d.wfcSlots());
        int chunkLoadLimit = validatedInt("chunkLoadLimit", ConfigurationEngine.setInt(
                List.of(
                        "How many chunks BetterStructures may ask the server to load at the same time, across all worlds.",
                        "Scans, pastes, dungeons and pregeneration all share this limit.",
                        "Must be between 1 and 256. Invalid values use the default of " + d.chunkLoadLimit() + "."),
                fileConfiguration, "chunkLoadLimit", d.chunkLoadLimit()), 1, 256, d.chunkLoadLimit());
        int chunkLoadLimitPerWorld = validatedInt("chunkLoadLimitPerWorld", ConfigurationEngine.setInt(
                List.of(
                        "How many chunks BetterStructures may ask the server to load at the same time in a single world.",
                        "Must be between 1 and 256. Invalid values use the default of " + d.chunkLoadLimitPerWorld() + "."),
                fileConfiguration, "chunkLoadLimitPerWorld", d.chunkLoadLimitPerWorld()), 1, 256, d.chunkLoadLimitPerWorld());
        double minBudget = validatedDouble("pasteBudgetMinMilliseconds", ConfigurationEngine.setDouble(
                List.of(
                        "The lowest time budget per tick, in milliseconds, that BetterStructures can be throttled down to",
                        "when the server falls behind. Pasting never gets slower than this.",
                        "Must be between 0.05 and 50. Invalid values use the default of " + d.budgetMinMillis() + "."),
                fileConfiguration, "pasteBudgetMinMilliseconds", d.budgetMinMillis()), 0.05, 50.0, d.budgetMinMillis());
        double recover = validatedDouble("pasteBudgetRecoverMilliseconds", ConfigurationEngine.setDouble(
                List.of(
                        "How many milliseconds of the throttled time budget are given back per tick once the server",
                        "is no longer falling behind, until percentageOfTickUsedForPasting is reached again.",
                        "Must be between 0.01 and 50. Invalid values use the default of " + d.budgetRecoverMillis() + "."),
                fileConfiguration, "pasteBudgetRecoverMilliseconds", d.budgetRecoverMillis()), 0.01, 50.0, d.budgetRecoverMillis());
        double backoff = validatedDouble("pasteBudgetBackoffFactor", ConfigurationEngine.setDouble(
                List.of(
                        "When the server falls behind, the time budget is multiplied by this factor.",
                        "Must be above 0 and below 1. Invalid values use the default of " + d.budgetBackoffFactor() + "."),
                fileConfiguration, "pasteBudgetBackoffFactor", d.budgetBackoffFactor()), 0.01, 0.99, d.budgetBackoffFactor());
        double lagThreshold = validatedDouble("pasteLagThresholdMilliseconds", ConfigurationEngine.setDouble(
                List.of(
                        "A scheduled task that runs this many milliseconds later than planned counts as the server falling behind.",
                        "Must be between 1 and 5000. Invalid values use the default of " + d.lagThresholdMillis() + "."),
                fileConfiguration, "pasteLagThresholdMilliseconds", d.lagThresholdMillis()), 1.0, 5000.0, d.lagThresholdMillis());
        int wfcWindow = validatedInt("dungeonPasteWindowChunks", ConfigurationEngine.setInt(
                List.of(
                        "While pasting a dungeon, only the chunk being pasted and this many chunks ahead are kept loaded.",
                        "Must be between 1 and 64. Invalid values use the default of " + d.wfcWindowChunks() + "."),
                fileConfiguration, "dungeonPasteWindowChunks", d.wfcWindowChunks()), 1, 64, d.wfcWindowChunks());
        boolean metrics = ConfigurationEngine.setBoolean(
                List.of(
                        "Logs the generation queues, the time budget and chunk loading to the console at a fixed interval.",
                        "Useful for finding out why structures show up late. Off by default."),
                fileConfiguration, "runtimeMetricsLogging", d.metricsEnabled());
        int metricsInterval = validatedInt("runtimeMetricsIntervalSeconds", ConfigurationEngine.setInt(
                List.of(
                        "Seconds between two runtimeMetricsLogging lines.",
                        "Must be between 5 and 3600. Invalid values use the default of " + d.metricsIntervalSeconds() + "."),
                fileConfiguration, "runtimeMetricsIntervalSeconds", d.metricsIntervalSeconds()), 5, 3600, d.metricsIntervalSeconds());

        // percentageOfTickUsedForPasting keeps its meaning; the flow control only clamps it to 0.01-1 when used.
        RuntimeSettings.set(new FlowSettings(scanSlots, pasteSlots, wfcSlots, chunkLoadLimit, chunkLoadLimitPerWorld,
                percentageOfTickUsedForPasting, minBudget, recover, backoff, lagThreshold, wfcWindow,
                metrics, metricsInterval));
    }

    private int validatedInt(String configKey, int configuredValue, int min, int max, int defaultValue) {
        if (configuredValue >= min && configuredValue <= max) return configuredValue;
        Logger.warn("Invalid " + configKey + " value " + configuredValue + "; using default " + defaultValue +
                ". Values must be between " + min + " and " + max + ".");
        fileConfiguration.set(configKey, defaultValue);
        return defaultValue;
    }

    private double validatedDouble(String configKey, double configuredValue, double min, double max, double defaultValue) {
        if (!Double.isNaN(configuredValue) && configuredValue >= min && configuredValue <= max) return configuredValue;
        Logger.warn("Invalid " + configKey + " value " + configuredValue + "; using default " + defaultValue +
                ". Values must be between " + min + " and " + max + ".");
        fileConfiguration.set(configKey, defaultValue);
        return defaultValue;
    }

    private int validatedDistance(String configKey, int configuredValue, int defaultValue) {
        if (configuredValue >= 1) return configuredValue;
        Logger.warn("Invalid " + configKey + " value " + configuredValue + "; using default " + defaultValue + ". Distances must be at least 1.");
        fileConfiguration.set(configKey, defaultValue);
        return defaultValue;
    }

    private int validatedOffset(String configKey, int configuredValue, int defaultValue) {
        if (configuredValue >= 0 && configuredValue <= MAX_SAFE_OFFSET) return configuredValue;
        Logger.warn("Invalid " + configKey + " value " + configuredValue + "; using default " + defaultValue +
                ". Offsets must be between 0 and " + MAX_SAFE_OFFSET + ".");
        fileConfiguration.set(configKey, defaultValue);
        return defaultValue;
    }

    private static String offsetValidationDescription(int defaultValue) {
        return "Must be between 0 and " + MAX_SAFE_OFFSET + ". Invalid values use the default of " + defaultValue + ".";
    }
}
