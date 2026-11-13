package com.magmaguy.betterstructures.schematic.model;

import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import org.bukkit.block.data.BlockData;

/**
 * Represents a single block entry inside a schematic with relative position, BlockData, and tile data.
 */
public record StructureBlock(
        int x,
        int y,
        int z,
        BlockData blockData,
        SimpleNbt.CompoundTag blockEntityData,
        SignTextParser.ParsedSign parsedSign
) {
    public boolean isSign() {
        return parsedSign != null;
    }
}
