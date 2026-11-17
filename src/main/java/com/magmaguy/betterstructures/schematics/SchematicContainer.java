package com.magmaguy.betterstructures.schematics;

import com.google.common.collect.ArrayListMultimap;
import com.magmaguy.betterstructures.chests.ChestContents;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.config.schematics.SchematicConfigField;
import com.magmaguy.betterstructures.config.treasures.TreasureConfig;
import com.magmaguy.betterstructures.config.treasures.TreasureConfigFields;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SchematicContainer {
    @Getter
    private static final ArrayListMultimap<GeneratorConfigFields.StructureType, SchematicContainer> schematics = ArrayListMultimap.create();
    private static final Map<Object, String> BIOME_ID_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean eliteMobsAvailable;
    private static volatile boolean mythicMobsAvailable;
    @Getter
    private final com.magmaguy.betterstructures.schematic.model.StructureSchematic structureSchematic;
    @Getter
    private final SchematicConfigField schematicConfigField;
    @Getter
    private final GeneratorConfigFields generatorConfigFields;
    @Getter
    private final String clipboardFilename;
    @Getter
    private final String configFilename;
    @Getter
    private final List<Vector> chestLocations = new ArrayList<>();
    @Getter
    private final HashMap<Vector, EntityType> vanillaSpawns = new HashMap<>();
    @Getter
    private final HashMap<Vector, String> eliteMobsSpawns = new HashMap<>();
    @Getter
    private final HashMap<Vector, String> mythicMobsSpawns = new HashMap<>(); // carm - Support for MythicMobs
    /** Lines two to four of each [custom] sign, for other plugins to interpret. */
    @Getter
    private final HashMap<Vector, List<String>> customMarkers = new HashMap<>();
    @Getter
    private ChestContents chestContents = null;
    @Getter
    private ChestContents barrelContents = null;
    private boolean valid = true;

    public SchematicContainer(com.magmaguy.betterstructures.schematic.model.StructureSchematic structureSchematic, String clipboardFilename, SchematicConfigField schematicConfigField, String configFilename) {
        this.structureSchematic = structureSchematic;
        this.clipboardFilename = clipboardFilename;
        this.schematicConfigField = schematicConfigField;
        this.configFilename = configFilename;
        generatorConfigFields = schematicConfigField.getGeneratorConfigFields();
        if (generatorConfigFields == null) {
            Logger.warn("Failed to assign generator for configuration of schematic " + schematicConfigField.getFilename() + " ! This means this structure will not appear in the world.");
            return;
        }
        if (!generatorConfigFields.isEnabled()) {
            valid = false;
            return;
        }
        if (structureSchematic == null) {
            Logger.warn("Failed to find schematic " + clipboardFilename + " for configuration " + configFilename + "; this structure will not be used.");
            valid = false;
            return;
        }

        // Direct copy from pre-indexed StructureSchematic (O(1) instead of nested triple loop!)
        for (Vector v : structureSchematic.getChestLocations()) chestLocations.add(v.clone());
        structureSchematic.getVanillaSpawns().forEach((pos, type) -> vanillaSpawns.put(pos.clone(), type));
        structureSchematic.getEliteMobsSpawns().forEach((pos, boss) -> eliteMobsSpawns.put(pos.clone(), boss));
        structureSchematic.getMythicMobsSpawns().forEach((pos, mob) -> mythicMobsSpawns.put(pos.clone(), mob));
        structureSchematic.getCustomMarkers().forEach((pos, args) -> customMarkers.put(pos.clone(), List.copyOf(args)));

        if ((!eliteMobsAvailable && !eliteMobsSpawns.isEmpty())
                || (!mythicMobsAvailable && !mythicMobsSpawns.isEmpty())) {
            Logger.warn(configFilename + " requires an unavailable mob integration; this schematic will not be used.");
            valid = false;
            return;
        }
        chestContents = generatorConfigFields.getChestContents();
        barrelContents = generatorConfigFields.getBarrelContents();
        if (schematicConfigField.getTreasureFile() != null && !schematicConfigField.getTreasureFile().isEmpty()) {
            TreasureConfigFields treasureConfigFields = TreasureConfig.getConfigFields(schematicConfigField.getTreasureFile());
            if (treasureConfigFields == null) {
                Logger.warn("Failed to get treasure configuration " + schematicConfigField.getTreasureFile() + " for schematic " + schematicConfigField.getFilename() + " ! Defaulting to the generator chest treasure.");
            } else {
                chestContents = schematicConfigField.getChestContents();
            }
        }
        if (schematicConfigField.getBarrelTreasureFilename() != null && !schematicConfigField.getBarrelTreasureFilename().isEmpty()) {
            TreasureConfigFields barrelTreasureConfigFields = TreasureConfig.getConfigFields(schematicConfigField.getBarrelTreasureFilename());
            if (barrelTreasureConfigFields == null) {
                Logger.warn("Failed to get barrel treasure configuration " + schematicConfigField.getBarrelTreasureFilename() + " for schematic " + schematicConfigField.getFilename() + " ! Defaulting to the generator barrel treasure.");
            } else {
                barrelContents = schematicConfigField.getBarrelContents();
            }
        }
        if (valid)
            generatorConfigFields.getStructureTypes().forEach(structureType -> schematics.put(structureType, this));
    }

    public static void shutdown() {
        schematics.clear();
        BIOME_ID_CACHE.clear();
    }

    public static void updateOptionalPluginAvailability(boolean eliteMobs, boolean mythicMobs) {
        eliteMobsAvailable = eliteMobs;
        mythicMobsAvailable = mythicMobs;
    }

    public boolean isValidEnvironment(World.Environment environment) {
        return generatorConfigFields.getValidWorldEnvironments() == null ||
                generatorConfigFields.getValidWorldEnvironments().isEmpty() ||
                generatorConfigFields.getValidWorldEnvironments().contains(environment);
    }

    /**
     * Validates if a biome is in the list of valid biomes.
     *
     * @param biomeObj The biome to validate (a {@link Biome}; anything else is matched by name)
     * @return True if the biome is valid, false otherwise
     */
    public boolean isValidBiome(Object biomeObj) {
        if (generatorConfigFields.getValidBiomesNamespaces() == null) return true;
        if (generatorConfigFields.getValidBiomesNamespaces().isEmpty()) return true;

        // Extract biome identifier based on version
        String biomeString = getBiomeIdentifier(biomeObj);

        for (String validBiome : generatorConfigFields.getValidBiomesNamespaces()) {
            if (biomeString.equals(validBiome)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets a string identifier ("namespace:key") for a biome, cached per biome.
     *
     * @param biomeObj The biome to get an identifier for
     * @return A string identifier for the biome
     */
    private static String getBiomeIdentifier(Object biomeObj) {
        return BIOME_ID_CACHE.computeIfAbsent(biomeObj, SchematicContainer::computeBiomeIdentifier);
    }

    private static String computeBiomeIdentifier(Object biomeObj) {
        if (biomeObj instanceof Biome biome) {
            // 公開 API: Biome は Keyed で、NamespacedKey#toString() が "namespace:key" になる
            NamespacedKey key = biome.getKey();
            return key.toString();
        }
        // Biome ではないもの: 名前から既定の名前空間で作る
        String fallback = String.valueOf(biomeObj);
        if (fallback.contains(".")) fallback = fallback.substring(fallback.lastIndexOf('.') + 1);
        return "minecraft:" + fallback.toLowerCase(Locale.ROOT).trim();
    }

    public boolean isValidYLevel(int yLevel) {
        return generatorConfigFields.getLowestYLevel() <= yLevel && generatorConfigFields.getHighestYLevel() >= yLevel;
    }

    public boolean isValidWorld(String worldName) {
        return generatorConfigFields.getValidWorlds() == null ||
                generatorConfigFields.getValidWorlds().isEmpty() ||
                generatorConfigFields.getValidWorlds().contains(worldName);
    }
}
