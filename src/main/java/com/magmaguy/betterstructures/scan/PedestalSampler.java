package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.util.SurfaceMaterials;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.Map;

/**
 * 台座の材質の標本を、貼り付け先の地形から取る（旧 {@code FitAnything.Preparation} の標本取り）。
 * 地形は走査の段で取ったスナップショット（{@link BlockSource}）から読むので、ワールドには触れない。
 *
 * <p>読むのは図面の幅・奥行きの格子点（{@link CandidateSelector#SCAN_STEP} 間隔）の列だけで、採点が読んだ
 * チャンクの中に収まる。
 */
public final class PedestalSampler {
    private PedestalSampler() {
    }

    /** 台座材の標本（材質 → 出現数）。作った後は変更しない。 */
    public record Samples(Map<Material, Integer> underground, Map<Material, Integer> surface) {
        public static final Samples EMPTY = new Samples(Map.of(), Map.of());

        public Samples {
            underground = Map.copyOf(underground);
            surface = Map.copyOf(surface);
        }
    }

    /**
     * @param source  地形
     * @param shape   図面
     * @param cornerX 図面の最小の角の X（貼り付け位置 + オフセット）
     * @param cornerY 同 Y
     * @param cornerZ 同 Z
     */
    public static Samples sample(BlockSource source, SchematicShape shape, int cornerX, int cornerY, int cornerZ) {
        int step = CandidateSelector.SCAN_STEP;
        Map<Material, Integer> underground = new EnumMap<>(Material.class);
        Map<Material, Integer> surface = new EnumMap<>(Material.class);
        for (int dx = 0; dx < shape.width(); dx += step) {
            for (int dz = 0; dz < shape.depth(); dz += step) {
                int x = cornerX + dx;
                int z = cornerZ + dz;
                // 地中: 上にも固いブロックがある固いブロック
                for (int dy = 0; dy < shape.height(); dy += step) {
                    Material ground = source.getType(x, cornerY + dy, z);
                    if (source.getType(x, cornerY + dy + 1, z).isSolid() && ground.isSolid()
                            && !SurfaceMaterials.ignorable(ground)) {
                        underground.merge(ground, 1, Integer::sum);
                    }
                }
                // 地表: 図面の頂上の上が固いなら上向き、そうでなければ下向きに、上が空いた固いブロックを探す（最大20）
                boolean up = source.getType(x, cornerY + shape.height(), z).isSolid();
                for (int offset = 0; offset < 20; offset++) {
                    int y = cornerY + (up ? offset : -offset);
                    Material ground = source.getType(x, y, z);
                    if (!source.getType(x, y + 1, z).isSolid() && ground.isSolid()) {
                        surface.merge(ground, 1, Integer::sum);
                        break;
                    }
                }
            }
        }
        return new Samples(underground, surface);
    }
}
