package com.magmaguy.betterstructures.buildingfitter.util;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 候補の図面から、位置の条件（ワールド・環境・バイオーム・高さ）に合うものを重みつきで1つ選ぶ。
 * ワールドにも Bukkit のスレッドにも触れない（WorkerPool で呼べる）。乱数は引数で受け取る。
 */
public class SchematicPicker {
    private static boolean warnedNullPick = false;

    /**
     * @param candidates  その種類の図面の写し（呼び出し側が所有リージョンで取った、登録簿から独立した一覧）
     * @param worldName   ワールド名
     * @param environment ワールドの環境
     * @param biome       仮の位置のバイオーム（取れなければ null）
     * @param blockY      仮の位置の Y
     * @param random      乱数
     * @return 選んだ図面。条件に合うものが無ければ null
     */
    public static SchematicContainer pick(List<SchematicContainer> candidates, String worldName, World.Environment environment,
                                          Object biome, int blockY, GeneratorConfigFields.StructureType structureType,
                                          RandomGenerator random) {
        List<SchematicContainer> schematicContainers = new ArrayList<>(candidates);
        if (schematicContainers.isEmpty()) return null;
        schematicContainers.removeIf(schematicContainer ->
                !schematicContainer.isValidWorld(worldName) ||
                        !schematicContainer.isValidEnvironment(environment) ||
                        !schematicContainer.isValidBiome(biome) ||
                        !schematicContainer.isValidYLevel(blockY));
        if (schematicContainers.isEmpty()) return null;
        double[] weights = new double[schematicContainers.size()];
        for (int i = 0; i < weights.length; i++)
            weights[i] = schematicContainers.get(i).getSchematicConfigField().getWeight();
        int pickedIndex = pickWeightedIndex(weights, random);
        if (pickedIndex < 0) {
            //Only possible with broken weights (all zero/negative); guard instead of NPE-ing
            if (!warnedNullPick) {
                warnedNullPick = true;
                Logger.warn("Could not pick a schematic for structure type " + structureType + " ! Check that the schematic configuration weights are positive numbers. This will only be warned about once.");
            }
            return null;
        }
        return schematicContainers.get(pickedIndex);
    }

    /**
     * 重みに比例して添字を選ぶ。{@code WeighedProbability#pickWeightedProbability} と同じ計算（最大値で割って
     * 桁あふれを避ける）で、乱数だけを引数にしたもの。選べなければ -1。
     */
    static int pickWeightedIndex(double[] weights, RandomGenerator random) {
        double maximum = 0;
        for (double weight : weights) {
            if (!Double.isFinite(weight) || weight < 0) return -1;
            maximum = Math.max(maximum, weight);
        }
        if (maximum == 0) return -1;
        double totalWeight = 0;
        for (double weight : weights) totalWeight += weight / maximum;
        double value = random.nextDouble(totalWeight);
        int lastPositive = -1;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] == 0) continue;
            lastPositive = i;
            value -= weights[i] / maximum;
            if (value < 0) return i;
        }
        return lastPositive;
    }
}
