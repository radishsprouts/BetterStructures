package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import org.bukkit.World;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 地形走査の依頼1件。{@link ScanCoordinator#submit} に渡す。ワールドには触れずに作れる。
 *
 * @param world    走査するワールド
 * @param chunkX   原点チャンクの X
 * @param chunkZ   原点チャンクの Z
 * @param kind     構造物の種類（地表・浅い地下・深い地下・空・液体）
 * @param forced   指定の図面（{@code /bs place}）。null なら位置から選ぶ
 * @param listener 結末の通知（null 可）。任意のスレッドで呼ばれる
 */
public record ScanRequest(World world, int chunkX, int chunkZ, StructureType kind, SchematicContainer forced,
                          Consumer<ScanOutcome> listener) {
    public ScanRequest {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(kind, "kind");
    }

    /** 自然生成の依頼。 */
    public static ScanRequest natural(World world, int chunkX, int chunkZ, StructureType kind) {
        return new ScanRequest(world, chunkX, chunkZ, kind, null, null);
    }

    void notifyOutcome(ScanOutcome outcome) {
        if (listener == null) return;
        try {
            listener.accept(outcome);
        } catch (RuntimeException failure) {
            com.magmaguy.magmacore.util.Logger.warn("Scan listener failed: " + failure);
        }
    }
}
