package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** {@link StructureSchematic}（回転前の図面）を {@link SchematicShape} として見せる。読み取りだけで、どのスレッドからも安全。 */
public final class StructureSchematicShape implements SchematicShape {
    private final StructureSchematic schematic;

    public StructureSchematicShape(StructureSchematic schematic) {
        this.schematic = schematic;
    }

    @Override
    public int width() {
        return schematic.getWidth();
    }

    @Override
    public int height() {
        return schematic.getHeight();
    }

    @Override
    public int depth() {
        return schematic.getLength();
    }

    @Override
    public int offsetX() {
        return schematic.getOffsetX();
    }

    @Override
    public int offsetY() {
        return schematic.getOffsetY();
    }

    @Override
    public int offsetZ() {
        return schematic.getOffsetZ();
    }

    @Override
    public int sample(int x, int y, int z) {
        BlockData data = schematic.getBlockData(x, y, z);
        Material material = data != null ? data.getMaterial() : Material.AIR;
        if (material.isAir()) return AIR;
        if (material == Material.WATER || material == Material.LAVA) return LIQUID;
        return OTHER;
    }
}
