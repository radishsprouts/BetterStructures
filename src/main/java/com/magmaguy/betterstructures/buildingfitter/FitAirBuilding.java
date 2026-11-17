package com.magmaguy.betterstructures.buildingfitter;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.scan.PedestalSampler;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/** 空の構造物の配置。走査は {@code scan/} が行う。 */
public class FitAirBuilding extends FitAnything {
    public FitAirBuilding(SchematicContainer schematicContainer, Location location, Vector schematicOffset,
                          PedestalSampler.Samples pedestalSamples) {
        super(schematicContainer, GeneratorConfigFields.StructureType.SKY, location, schematicOffset, pedestalSamples);
    }
}
