package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.buildingfitter.FitAnything;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType;
import com.magmaguy.betterstructures.paste.MarkerHandler;
import com.magmaguy.betterstructures.paste.PasteRequest;
import com.magmaguy.betterstructures.paste.PasteResult;
import com.magmaguy.betterstructures.runtime.ChunkKeys;
import com.magmaguy.betterstructures.runtime.Services;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.function.Function;

/**
 * 走査が決めた配置を、{@code BuildPlaceEvent} を出して PasteEngine へ渡す（決定の段）。
 * 貼り付け先の原点チャンクの所有リージョンのスレッドで呼ぶ。待たない。
 *
 * <p>走査が取ったチャンクの hold は、貼り付けの範囲に要るものだけを残して {@code heldChunks} として
 * エンジンへ渡す（エンジンは先に自分の持ち主で hold してから走査の分を release するので、途切れない）。
 */
public final class PasteHandoff {
    private PasteHandoff() {
    }

    /**
     * @param holds   走査が取った hold（エンジンへ引き継ぐ。取り消された・失敗した時は呼び出し側が release する）
     * @param markers 看板の目印の扱いを作る関数（U2 の {@code BuildingMarkerHandler}）
     * @return 貼り付けを始めたら {@link ScanOutcome#PLACED}、イベントが取り消したら {@link ScanOutcome#CANCELLED}
     */
    public static ScanOutcome start(World world, ScanJob.Plan plan, StructureSchematic schematic,
                                    PluginChunkHolds.HoldSet holds, Function<FitAnything, MarkerHandler> markers,
                                    ScanEvents events) {
        SchematicShape shape = plan.shape();
        Vector offset = new Vector(shape.offsetX(), shape.offsetY(), shape.offsetZ());
        Location base = new Location(world, plan.x(), plan.y(), plan.z());
        FitAnything fit = FitAnything.create(plan.container(), plan.kind(), base, offset, plan.pedestal());

        if (events.firePlace(fit)) return ScanOutcome.CANCELLED;

        StructureType kind = plan.kind();
        PasteRequest.Builder builder = PasteRequest.builder(world, base.clone(), schematic, markers.apply(fit))
                .schematicOffset(offset)
                .schematicName(plan.container() == null ? "" : plan.container().getConfigFilename())
                .rotationDegrees(0)
                .pedestalMaterialProvider(kind == StructureType.SKY ? null : fit::getPedestalMaterial)
                .bedrockReplacement(fit.getDefaultPedestalMaterial())
                // 台座は地面に埋まる/面する種類だけ。木の除去は地表だけ（旧 FitAnything.Finishing と同じ）。
                .fillPedestal(kind != StructureType.SKY && kind != StructureType.LIQUID_SURFACE)
                .clearTrees(kind == StructureType.SURFACE)
                .heldChunks(holds)
                .announce(DefaultConfig.isNewBuildingWarn())
                .onFinished(result -> {
                    if (result == null || result.outcome() != PasteResult.Outcome.COMPLETED) return;
                    events.fireComplete(fit, result.minCorner(), result.maxCorner(), result.containers());
                });
        if (plan.container() != null) {
            builder.contents(plan.container().getChestContents(), plan.container().getBarrelContents());
        }

        trimHolds(holds, plan);
        Services.pasteEngine().start(builder.build());
        return ScanOutcome.PLACED;
    }

    /** 貼り付けの範囲のチャンクだけを残して、走査が余分に取ったもの（採用しなかった候補のチャンク）を手放す。 */
    private static void trimHolds(PluginChunkHolds.HoldSet holds, ScanJob.Plan plan) {
        if (holds == null) return;
        SchematicShape shape = plan.shape();
        int minX = plan.x() + shape.offsetX();
        int minZ = plan.z() + shape.offsetZ();
        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(minX + shape.width() - 1, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(minZ + shape.depth() - 1, 16);
        for (long key : holds.keys()) {
            int cx = ChunkKeys.x(key);
            int cz = ChunkKeys.z(key);
            if (cx < minChunkX || cx > maxChunkX || cz < minChunkZ || cz > maxChunkZ) holds.release(cx, cz);
        }
    }
}
