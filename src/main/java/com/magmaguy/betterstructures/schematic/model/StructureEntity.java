package com.magmaguy.betterstructures.schematic.model;

import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;

/**
 * Represents an entity within a schematic (such as an armor stand, item frame, or painting).
 */
public record StructureEntity(
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String entityId,
        SimpleNbt.CompoundTag nbt
) {
}
