package com.magmaguy.betterstructures.schematic.reader;

import com.magmaguy.betterstructures.integration.SchematicImport;
import com.magmaguy.betterstructures.schematic.model.StructureEntity;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Pure Java reader for Sponge Schematic files (Version 1, 2, and 3).
 * Requires zero WorldEdit or NMS classes.
 */
public final class SchematicReader {

    private SchematicReader() {
    }

    public static StructureSchematic read(File file) throws IOException {
        return read(file, SchematicImport.ConversionReport.NONE);
    }

    public static StructureSchematic read(File file, SchematicImport.ConversionReport report) throws IOException {
        SimpleNbt.CompoundTag root = SimpleNbt.readFile(file);
        return parse(root, file.getName(), report);
    }

    public static StructureSchematic read(InputStream inputStream, String schematicName) throws IOException {
        return read(inputStream, schematicName, SchematicImport.ConversionReport.NONE);
    }

    public static StructureSchematic read(InputStream inputStream, String schematicName,
                                          SchematicImport.ConversionReport report) throws IOException {
        SimpleNbt.CompoundTag root = SimpleNbt.read(inputStream);
        return parse(root, schematicName, report);
    }

    public static StructureSchematic parse(SimpleNbt.CompoundTag root, String schematicName) throws IOException {
        return parse(root, schematicName, SchematicImport.ConversionReport.NONE);
    }

    public static StructureSchematic parse(SimpleNbt.CompoundTag root, String schematicName,
                                           SchematicImport.ConversionReport report) throws IOException {
        if (root.containsKey("Schematic")) {
            SimpleNbt.CompoundTag schematicCompound = root.getCompound("Schematic");
            if (schematicCompound != null) {
                root = schematicCompound;
            }
        }
        int version = root.getInt("Version", 1);

        short width = root.getShort("Width");
        short height = root.getShort("Height");
        short length = root.getShort("Length");

        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("Invalid schematic dimensions: " + width + "x" + height + "x" + length + " in " + schematicName);
        }

        int[] offset = root.getIntArray("Offset");
        int offsetX = (offset != null && offset.length >= 3) ? offset[0] : 0;
        int offsetY = (offset != null && offset.length >= 3) ? offset[1] : 0;
        int offsetZ = (offset != null && offset.length >= 3) ? offset[2] : 0;

        // 1. Resolve Palette and BlockData (v1/v2 vs v3)
        SimpleNbt.CompoundTag paletteTag = root.getCompound("Palette");
        byte[] blockDataBytes = root.getByteArray("BlockData");
        SimpleNbt.ListTag blockEntitiesList = root.getList("BlockEntities");

        if (version >= 3) {
            SimpleNbt.CompoundTag blocksContainer = root.getCompound("Blocks");
            if (blocksContainer != null) {
                if (paletteTag == null) paletteTag = blocksContainer.getCompound("Palette");
                if (blockDataBytes == null) blockDataBytes = blocksContainer.getByteArray("Data");
                if (blockEntitiesList == null) blockEntitiesList = blocksContainer.getList("BlockEntities");
            }
        }

        if (paletteTag == null) {
            throw new IOException("Missing Palette in schematic: " + schematicName);
        }
        if (blockDataBytes == null) {
            throw new IOException("Missing BlockData in schematic: " + schematicName);
        }

        // 2. Decode Palette to Bukkit BlockData
        Map<Integer, BlockData> paletteMap = new HashMap<>(paletteTag.keySet().size());
        BlockData airBlockData = Material.AIR.createBlockData();
        Map<Integer, String> unresolvedStates = new HashMap<>();

        for (String stateString : paletteTag.keySet()) {
            int paletteId = paletteTag.getInt(stateString, -1);
            if (paletteId < 0) continue;

            BlockData blockData = createBlockDataOrNull(stateString);
            if (blockData == null) {
                unresolvedStates.put(paletteId, stateString);
                continue;
            }
            paletteMap.put(paletteId, blockData);
        }

        // 古い版の図面: 直接解釈できなかった項目だけを、元の DataVersion から今の版へ変換する
        if (!unresolvedStates.isEmpty()) {
            int dataVersion = root.getInt("DataVersion", -1);
            Map<String, BlockData> upgraded = PaletteUpgrader.upgrade(dataVersion, unresolvedStates.values());
            Iterator<Map.Entry<Integer, String>> iterator = unresolvedStates.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, String> entry = iterator.next();
                BlockData data = upgraded.get(entry.getValue());
                if (data != null) {
                    paletteMap.put(entry.getKey(), data);
                    iterator.remove();
                }
            }
        }

        // 3. Decode BlockData bytes (VarInt array) into BlockData[width * height * length]
        int totalBlocks = width * height * length;
        BlockData[] blocks = new BlockData[totalBlocks];
        int byteIndex = 0;

        for (int blockIndex = 0; blockIndex < totalBlocks; blockIndex++) {
            if (byteIndex >= blockDataBytes.length) {
                blocks[blockIndex] = airBlockData;
                continue;
            }

            int paletteId = 0;
            int bitShift = 0;
            while (true) {
                if (byteIndex >= blockDataBytes.length) break;
                byte b = blockDataBytes[byteIndex++];
                paletteId |= (b & 0x7F) << bitShift;
                if ((b & 0x80) == 0) break;
                bitShift += 7;
            }

            BlockData data = paletteMap.get(paletteId);
            blocks[blockIndex] = data != null ? data : airBlockData;
            if (data == null) {
                String unresolved = unresolvedStates.get(paletteId);
                if (unresolved != null) report.unresolvedBlock(schematicName, blockName(unresolved));
            }
        }

        // 4. Decode Block Entities
        Map<Integer, SimpleNbt.CompoundTag> blockEntities = new HashMap<>();
        Map<Integer, SignTextParser.ParsedSign> parsedSigns = new HashMap<>();

        if (blockEntitiesList != null) {
            for (SimpleNbt.Tag tag : blockEntitiesList) {
                if (!(tag instanceof SimpleNbt.CompoundTag tileTag)) continue;

                int x = -1, y = -1, z = -1;
                int[] pos = tileTag.getIntArray("Pos");
                if (pos != null && pos.length >= 3) {
                    x = pos[0];
                    y = pos[1];
                    z = pos[2];
                } else if (tileTag.containsKey("x") && tileTag.containsKey("y") && tileTag.containsKey("z")) {
                    x = tileTag.getInt("x");
                    y = tileTag.getInt("y");
                    z = tileTag.getInt("z");
                }

                if (x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < length) {
                    int blockIdx = (y * length + z) * width + x;
                    SimpleNbt.CompoundTag dataTag = tileTag.getCompound("Data");
                    blockEntities.put(blockIdx, dataTag != null ? dataTag : tileTag);

                    // Check if block at this position is a sign
                    BlockData blockData = blocks[blockIdx];
                    if (blockData != null && isSign(blockData.getMaterial())) {
                        SignTextParser.ParsedSign parsedSign = SignTextParser.parse(tileTag);
                        parsedSigns.put(blockIdx, parsedSign);
                    }
                }
            }
        }

        // 5. Decode Entities
        List<StructureEntity> entities = new ArrayList<>();
        SimpleNbt.ListTag entitiesList = root.getList("Entities");
        if (entitiesList != null) {
            for (SimpleNbt.Tag tag : entitiesList) {
                if (!(tag instanceof SimpleNbt.CompoundTag entityTag)) continue;

                SimpleNbt.CompoundTag dataTag = entityTag.getCompound("Data");
                String id = entityTag.getString("Id", "");
                if (id.isEmpty()) id = entityTag.getString("id", "");
                if (id.isEmpty() && dataTag != null) {
                    id = dataTag.getString("id", "");
                    if (id.isEmpty()) id = dataTag.getString("Id", "");
                }

                double x = 0, y = 0, z = 0;
                SimpleNbt.ListTag posList = entityTag.getList("Pos");
                if ((posList == null || posList.size() < 3) && dataTag != null) {
                    posList = dataTag.getList("Pos");
                }
                if (posList != null && posList.size() >= 3) {
                    x = ((Number) posList.get(0).getValue()).doubleValue();
                    y = ((Number) posList.get(1).getValue()).doubleValue();
                    z = ((Number) posList.get(2).getValue()).doubleValue();
                }

                float yaw = 0, pitch = 0;
                SimpleNbt.ListTag rotList = entityTag.getList("Rotation");
                if ((rotList == null || rotList.size() < 2) && dataTag != null) {
                    rotList = dataTag.getList("Rotation");
                }
                if (rotList != null && rotList.size() >= 2) {
                    yaw = ((Number) rotList.get(0).getValue()).floatValue();
                    pitch = ((Number) rotList.get(1).getValue()).floatValue();
                }

                entities.add(new StructureEntity(x, y, z, yaw, pitch, id, entityTag));
            }
        }

        StructureSchematic schematic = new StructureSchematic(
                width, height, length,
                offsetX, offsetY, offsetZ,
                blocks, blockEntities, parsedSigns, entities
        );
        // 図面の元の DataVersion（貼り付けの記録から図面を引き直す時などに使う）。無ければ 0（不明）。
        schematic.setDataVersion(Math.max(0, root.getInt("DataVersion", 0)));
        return schematic;
    }

    /** 名前空間が無ければ minecraft: を補って解釈する。解釈できなければ null。 */
    private static BlockData createBlockDataOrNull(String stateString) {
        try {
            return Bukkit.createBlockData(stateString);
        } catch (Exception e) {
            if (stateString.contains(":")) return null;
            try {
                return Bukkit.createBlockData("minecraft:" + stateString);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** {@code minecraft:foo[a=b]} から {@code minecraft:foo} を取り出す（報告用）。 */
    private static String blockName(String stateString) {
        int bracket = stateString.indexOf('[');
        return bracket < 0 ? stateString : stateString.substring(0, bracket);
    }

    private static boolean isSign(Material material) {
        String name = material.name();
        return name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN") || name.endsWith("_HANGING_SIGN");
    }
}
