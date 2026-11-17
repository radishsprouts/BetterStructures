package com.magmaguy.betterstructures.buildingfitter;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.scan.PedestalSampler;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/** 地下（浅い・深い）の構造物の配置。走査は {@code scan/} が行う。 */
public class FitUndergroundBuilding extends FitAnything {
    public FitUndergroundBuilding(SchematicContainer schematicContainer, GeneratorConfigFields.StructureType structureType,
                                  Location location, Vector schematicOffset, PedestalSampler.Samples pedestalSamples) {
        super(schematicContainer, structureType, location, schematicOffset, pedestalSamples);
    }
}
