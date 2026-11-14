package com.magmaguy.betterstructures.schematic.model;

import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Independent in-memory model of a Minecraft structure/schematic (.schem).
 * Contains all block states, tile entities, sign texts, entities, and pre-indexed markers.
 */
public final class StructureSchematic {

    @Getter
    private final short width;
    @Getter
    private final short height;
    @Getter
    private final short length;
    @Getter
    private final int offsetX;
    @Getter
    private final int offsetY;
    @Getter
    private final int offsetZ;

    private final BlockData[] blocks;
    private final Map<Integer, SimpleNbt.CompoundTag> blockEntities;
    private final Map<Integer, SignTextParser.ParsedSign> parsedSigns;
    @Getter
    private final List<StructureEntity> entities;

    // Pre-indexed marker locations relative to schematic origin [0..width, 0..height, 0..length]
    @Getter
    private final List<Vector> chestLocations = new ArrayList<>();
    @Getter
    private final Map<Vector, EntityType> vanillaSpawns = new HashMap<>();
    @Getter
    private final Map<Vector, String> eliteMobsSpawns = new HashMap<>();
    @Getter
    private final Map<Vector, String> mythicMobsSpawns = new HashMap<>();
    @Getter
    private final Map<Vector, List<String>> customMarkers = new HashMap<>();
    @Getter
    private final Map<Vector, Material> chestMarkers = new HashMap<>();

    public StructureSchematic(
            short width,
            short height,
            short length,
            int offsetX,
            int offsetY,
            int offsetZ,
            BlockData[] blocks,
            Map<Integer, SimpleNbt.CompoundTag> blockEntities,
            Map<Integer, SignTextParser.ParsedSign> parsedSigns,
            List<StructureEntity> entities
    ) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.blocks = blocks != null ? blocks : new BlockData[width * height * length];
        this.blockEntities = blockEntities != null ? blockEntities : new HashMap<>();
        this.parsedSigns = parsedSigns != null ? parsedSigns : new HashMap<>();
        this.entities = entities != null ? entities : new ArrayList<>();

        indexMarkers();
    }

    public int index(int x, int y, int z) {
        return (y * length + z) * width + x;
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < length;
    }

    public BlockData getBlockData(int x, int y, int z) {
        if (!inBounds(x, y, z)) return null;
        return blocks[index(x, y, z)];
    }

    public SimpleNbt.CompoundTag getBlockEntity(int x, int y, int z) {
        if (!inBounds(x, y, z)) return null;
        return blockEntities.get(index(x, y, z));
    }

    public SignTextParser.ParsedSign getParsedSign(int x, int y, int z) {
        if (!inBounds(x, y, z)) return null;
        return parsedSigns.get(index(x, y, z));
    }

    public StructureBlock getBlock(int x, int y, int z) {
        if (!inBounds(x, y, z)) return null;
        int idx = index(x, y, z);
        return new StructureBlock(x, y, z, blocks[idx], blockEntities.get(idx), parsedSigns.get(idx));
    }

    private void indexMarkers() {
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int idx = index(x, y, z);
                    BlockData data = blocks[idx];
                    if (data == null) continue;
                    Material mat = data.getMaterial();

                    // 1. Containers: Chest, Trapped Chest, Barrel, Shulker Box
                    if (mat == Material.CHEST || mat == Material.TRAPPED_CHEST || mat == Material.BARREL
                            || isShulkerBox(mat)) {
                        chestLocations.add(new Vector(x, y, z));
                    }

                    // 2. Signs
                    SignTextParser.ParsedSign sign = parsedSigns.get(idx);
                    if (sign != null) {
                        processSignMarker(x, y, z, sign);
                    }
                }
            }
        }
    }

    private void processSignMarker(int x, int y, int z, SignTextParser.ParsedSign sign) {
        Vector pos = new Vector(x, y, z);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add(sign.getFrontLine(i).trim());
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);

            if (lower.contains("[spawn]")) {
                String entityName = null;
                for (int j = i + 1; j < lines.size(); j++) {
                    String candidate = lines.get(j).replace("\"", "").trim();
                    if (!candidate.isEmpty()) {
                        entityName = candidate.toUpperCase(Locale.ROOT);
                        break;
                    }
                }
                if (entityName != null) {
                    EntityType entityType;
                    try {
                        entityType = EntityType.valueOf(entityName);
                    } catch (IllegalArgumentException ex) {
                        entityType = (entityName.equalsIgnoreCase("WITHER_CRYSTAL") || entityName.equalsIgnoreCase("ENDER_CRYSTAL")) ? EntityType.END_CRYSTAL : null;
                    }
                    if (entityType != null) {
                        vanillaSpawns.put(pos, entityType);
                    }
                }
                return;
            }

            if (lower.contains("[elitemobs]")) {
                StringBuilder bossFilename = new StringBuilder();
                for (int j = i + 1; j < lines.size(); j++) {
                    String part = lines.get(j).trim();
                    if (!part.isEmpty()) bossFilename.append(part);
                }
                if (bossFilename.length() > 0) {
                    eliteMobsSpawns.put(pos, bossFilename.toString());
                }
                return;
            }

            if (lower.contains("[mythicmobs]")) {
                String mob = (i + 1 < lines.size()) ? lines.get(i + 1).trim() : "";
                String level = (i + 2 < lines.size()) ? lines.get(i + 2).trim() : "";
                if (!mob.isEmpty()) {
                    mythicMobsSpawns.put(pos, level.isEmpty() ? mob : mob + ":" + level);
                }
                return;
            }

            if (lower.contains("[custom]")) {
                List<String> customLines = new ArrayList<>();
                for (int j = i + 1; j < lines.size(); j++) {
                    customLines.add(lines.get(j).trim());
                }
                if (customLines.stream().anyMatch(s -> !s.isEmpty())) {
                    customMarkers.put(pos, List.copyOf(customLines));
                }
                return;
            }

            if (lower.contains("[chest]")) {
                chestMarkers.put(pos, Material.CHEST);
                return;
            }

            if (lower.contains("[trapped_chest]")) {
                chestMarkers.put(pos, Material.TRAPPED_CHEST);
                return;
            }
        }
    }

    private static boolean isShulkerBox(Material material) {
        return material.name().endsWith("SHULKER_BOX") && !material.name().startsWith("LEGACY_");
    }

    /**
     * Creates a rotated copy of this schematic by the given degrees (0, 90, 180, 270).
     */
    public StructureSchematic rotate(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized == 0) return this;

        StructureRotation rotation = switch (normalized) {
            case 90 -> StructureRotation.CLOCKWISE_90;
            case 180 -> StructureRotation.CLOCKWISE_180;
            case 270 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException("Only 0, 90, 180, 270 degree rotations are supported: " + degrees);
        };

        short newWidth = (normalized == 90 || normalized == 270) ? length : width;
        short newHeight = height;
        short newLength = (normalized == 90 || normalized == 270) ? width : length;

        BlockData[] newBlocks = new BlockData[newWidth * newHeight * newLength];
        Map<Integer, SimpleNbt.CompoundTag> newBlockEntities = new HashMap<>();
        Map<Integer, SignTextParser.ParsedSign> newParsedSigns = new HashMap<>();

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int oldIdx = index(x, y, z);
                    BlockData oldData = blocks[oldIdx];
                    if (oldData == null) continue;

                    int[] rotatedPos = rotateCoordinates(x, y, z, normalized);
                    int nx = rotatedPos[0];
                    int ny = rotatedPos[1];
                    int nz = rotatedPos[2];

                    int newIdx = (ny * newLength + nz) * newWidth + nx;

                    // Rotate BlockData using Bukkit's native rotation
                    BlockData rotatedData = oldData.clone();
                    try {
                        rotatedData.rotate(rotation);
                    } catch (Exception ignored) {
                    }
                    newBlocks[newIdx] = rotatedData;

                    // Tile Entities
                    SimpleNbt.CompoundTag tileTag = blockEntities.get(oldIdx);
                    if (tileTag != null) {
                        newBlockEntities.put(newIdx, tileTag);
                    }

                    // Signs
                    SignTextParser.ParsedSign sign = parsedSigns.get(oldIdx);
                    if (sign != null) {
                        newParsedSigns.put(newIdx, sign);
                    }
                }
            }
        }

        // Entities rotation
        List<StructureEntity> newEntities = new ArrayList<>();
        for (StructureEntity entity : entities) {
            double[] rotPos = rotateDoubleCoordinates(entity.x(), entity.y(), entity.z(), normalized);
            float newYaw = (entity.yaw() + normalized) % 360.0f;
            newEntities.add(new StructureEntity(rotPos[0], rotPos[1], rotPos[2], newYaw, entity.pitch(), entity.entityId(), entity.nbt()));
        }

        // Offsets rotation
        int[] rotOffset = rotateCoordinates(offsetX, offsetY, offsetZ, normalized);

        return new StructureSchematic(
                newWidth, newHeight, newLength,
                rotOffset[0], rotOffset[1], rotOffset[2],
                newBlocks, newBlockEntities, newParsedSigns, newEntities
        );
    }

    private int[] rotateCoordinates(int x, int y, int z, int deg) {
        return switch (deg) {
            case 90 -> new int[]{length - 1 - z, y, x};
            case 180 -> new int[]{width - 1 - x, y, length - 1 - z};
            case 270 -> new int[]{z, y, width - 1 - x};
            default -> new int[]{x, y, z};
        };
    }

    private double[] rotateDoubleCoordinates(double x, double y, double z, int deg) {
        return switch (deg) {
            case 90 -> new double[]{length - z, y, x};
            case 180 -> new double[]{width - x, y, length - z};
            case 270 -> new double[]{z, y, width - x};
            default -> new double[]{x, y, z};
        };
    }
}
