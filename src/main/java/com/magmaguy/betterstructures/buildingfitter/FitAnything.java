package com.magmaguy.betterstructures.buildingfitter;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.scan.PedestalSampler;
import com.magmaguy.betterstructures.scan.ScanCoordinator;
import com.magmaguy.betterstructures.scan.ScanRequest;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import lombok.Getter;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 地形走査が決めた1件の配置（どの図面を、どこに、どの台座材で置くか）。{@code BuildPlaceEvent} などで
 * 他のプラグインへ渡る、WorldEdit に依らないデータの入れ物。走査・貼り付けはここでは行わない
 * （走査は {@link ScanCoordinator}、貼り付けは PasteEngine）。
 *
 * <p>種類ごとの薄いサブクラス（{@link FitSurfaceBuilding} 等）は、他のプラグインが
 * {@code instanceof} で見分けるために残してある。
 */
public class FitAnything {
    @Getter
    protected final SchematicContainer schematicContainer;
    @Getter
    protected final Vector schematicOffset;
    @Getter
    protected final Location location;
    @Getter
    protected final GeneratorConfigFields.StructureType structureType;
    private final PedestalSampler.Samples pedestalSamples;
    private final Material defaultPedestalMaterial;

    public FitAnything(SchematicContainer schematicContainer, GeneratorConfigFields.StructureType structureType,
                       Location location, Vector schematicOffset, PedestalSampler.Samples pedestalSamples) {
        this.schematicContainer = schematicContainer;
        this.structureType = structureType;
        this.location = location;
        this.schematicOffset = schematicOffset;
        this.pedestalSamples = pedestalSamples == null ? PedestalSampler.Samples.EMPTY : pedestalSamples;
        this.defaultPedestalMaterial = defaultPedestal(schematicContainer, location);
    }

    /** 種類に合うサブクラスの入れ物を作る。 */
    public static FitAnything create(SchematicContainer schematicContainer, GeneratorConfigFields.StructureType structureType,
                                     Location location, Vector schematicOffset, PedestalSampler.Samples pedestalSamples) {
        return switch (structureType) {
            case SKY -> new FitAirBuilding(schematicContainer, location, schematicOffset, pedestalSamples);
            case LIQUID_SURFACE -> new FitLiquidBuilding(schematicContainer, location, schematicOffset, pedestalSamples);
            case SURFACE -> new FitSurfaceBuilding(schematicContainer, location, schematicOffset, pedestalSamples);
            default -> new FitUndergroundBuilding(schematicContainer, structureType, location, schematicOffset, pedestalSamples);
        };
    }

    /**
     * 指定のチャンクを原点に、指定の図面を指定の種類で置く（{@code /bs place} 等）。走査は
     * {@link ScanCoordinator} の流れ（流量制御・hold・予算）に乗り、結果を待たずに返る。
     */
    public static void commandBasedCreation(Chunk chunk, GeneratorConfigFields.StructureType structureType, SchematicContainer container) {
        ScanCoordinator.submit(new ScanRequest(chunk.getWorld(), chunk.getX(), chunk.getZ(), structureType, container, null));
    }

    private static Material defaultPedestal(SchematicContainer container, Location location) {
        Material configured = container == null || container.getSchematicConfigField() == null
                ? null : container.getSchematicConfigField().getPedestalMaterial();
        if (configured != null) return configured;
        if (location == null || location.getWorld() == null) return Material.STONE;
        return switch (location.getWorld().getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.STONE;
        };
    }

    /** 標本が無い時の台座材（図面の設定、無ければ環境に応じた石）。 */
    public Material getDefaultPedestalMaterial() {
        return defaultPedestalMaterial;
    }

    /**
     * 台座の1本の柱に使う材質。標本があれば出現数に比例して選び、無ければ {@link #getDefaultPedestalMaterial()}。
     *
     * @param isPedestalSurface 柱の上が空いている（地表に面している）か
     */
    public Material getPedestalMaterial(boolean isPedestalSurface) {
        Map<Material, Integer> samples = isPedestalSurface ? pedestalSamples.surface() : pedestalSamples.underground();
        if (samples.isEmpty()) return defaultPedestalMaterial;
        return getRandomMaterialBasedOnWeight(new HashMap<>(samples));
    }

    public Material getRandomMaterialBasedOnWeight(HashMap<Material, Integer> weightedMaterials) {
        int totalWeight = weightedMaterials.values().stream().mapToInt(Integer::intValue).sum();
        int randomNumber = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulativeWeight = 0;
        for (Map.Entry<Material, Integer> entry : weightedMaterials.entrySet()) {
            cumulativeWeight += entry.getValue();
            if (randomNumber < cumulativeWeight) {
                return entry.getKey();
            }
        }
        // 重みが全部正の空でない写しでは起こらない。
        throw new IllegalStateException("Weighted random selection failed.");
    }
}
