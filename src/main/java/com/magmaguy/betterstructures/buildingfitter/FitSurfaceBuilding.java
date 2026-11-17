package com.magmaguy.betterstructures.buildingfitter;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.scan.PedestalSampler;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/** 地表の構造物の配置。走査は {@code scan/} が行う。 */
public class FitSurfaceBuilding extends FitAnything {
    public FitSurfaceBuilding(SchematicContainer schematicContainer, Location location, Vector schematicOffset,
                              PedestalSampler.Samples pedestalSamples) {
        super(schematicContainer, GeneratorConfigFields.StructureType.SURFACE, location, schematicOffset, pedestalSamples);
    }
}
