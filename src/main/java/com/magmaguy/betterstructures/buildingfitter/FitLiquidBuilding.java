package com.magmaguy.betterstructures.buildingfitter;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.scan.PedestalSampler;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/** 液体の上の構造物の配置。走査は {@code scan/} が行う。 */
public class FitLiquidBuilding extends FitAnything {
    public FitLiquidBuilding(SchematicContainer schematicContainer, Location location, Vector schematicOffset,
                             PedestalSampler.Samples pedestalSamples) {
        super(schematicContainer, GeneratorConfigFields.StructureType.LIQUID_SURFACE, location, schematicOffset, pedestalSamples);
    }
}
