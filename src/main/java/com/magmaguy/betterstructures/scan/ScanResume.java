package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.buildingfitter.FitAnything;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType;
import com.magmaguy.betterstructures.paste.MarkerHandler;
import com.magmaguy.betterstructures.paste.PasteJournal;
import com.magmaguy.betterstructures.paste.PasteRequest;
import com.magmaguy.betterstructures.paste.PasteResult;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.function.Function;

/**
 * 停止をまたぐ貼り付けの再開（PasteJournal）で、地形走査が始めた構造物の依頼を作り直す。
 * {@link PasteHandoff#start} が作る依頼と同じ中身（目印の処理・宝箱の中身・完了イベント）にする。
 * ただし {@code BuildPlaceEvent} は最初の貼り付けで出し済みなので出し直さない。
 *
 * <p>記録に無い台座材の標本は空として扱い、台座は図面の設定（無ければ環境に応じた石）で埋める。
 * ワールドには触れないので WorkerPool のスレッドから呼べる。
 */
public final class ScanResume {
    private ScanResume() {
    }

    /**
     * @param markers 看板の目印の扱いを作る関数（{@link ScanCoordinator#setMarkerHandlerFactory} と同じもの）
     * @return 作り直した依頼。図面（設定名）か世界が今は無ければ null（記録は残る）
     */
    public static PasteRequest rebuild(PasteJournal.Entry entry, Function<FitAnything, MarkerHandler> markers,
                                       ScanEvents events) {
        if (entry == null || entry.worldId == null || entry.schematicName == null) return null;
        SchematicContainer container = SchematicContainer.findByConfigFilename(entry.schematicName);
        if (container == null || container.getStructureSchematic() == null) return null;
        World world = Bukkit.getWorld(UUID.fromString(entry.worldId));
        if (world == null) return null;

        StructureSchematic schematic = container.getStructureSchematic();
        if (entry.rotationDegrees != 0) schematic = schematic.rotate(entry.rotationDegrees);

        Location base = new Location(world, entry.baseX, entry.baseY, entry.baseZ);
        Vector offset = new Vector(entry.offsetX, entry.offsetY, entry.offsetZ);
        StructureType kind = inferKind(entry, container);
        FitAnything fit = FitAnything.create(container, kind, base, offset, PedestalSampler.Samples.EMPTY);

        PasteRequest.Builder builder = PasteRequest.builder(world, base.clone(), schematic, markers.apply(fit))
                .schematicOffset(offset)
                .schematicName(entry.schematicName)
                .rotationDegrees(entry.rotationDegrees)
                .pedestalMaterialProvider(kind == StructureType.SKY ? null : fit::getPedestalMaterial)
                .bedrockReplacement(entry.bedrockReplacement == null
                        ? fit.getDefaultPedestalMaterial() : materialOrDefault(entry.bedrockReplacement, fit))
                .fillPedestal(entry.fillPedestal)
                .clearTrees(entry.clearTrees)
                .windowChunks(entry.windowChunks)
                .announce(entry.announce)
                .contents(container.getChestContents(), container.getBarrelContents())
                .onFinished(result -> {
                    if (result == null || result.outcome() != PasteResult.Outcome.COMPLETED) return;
                    events.fireComplete(fit, result.minCorner(), result.maxCorner(), result.containers());
                });
        return builder.build();
    }

    /**
     * 記録には種類が無いので、貼り付けの設定から決める。木の除去をしていれば地表、台座を作らなければ
     * 空中か水面（図面が対応する種類から選ぶ）、どちらでもなければ地中。
     */
    static StructureType inferKind(PasteJournal.Entry entry, SchematicContainer container) {
        var types = container.getGeneratorConfigFields() == null
                ? java.util.List.<StructureType>of() : container.getGeneratorConfigFields().getStructureTypes();
        if (entry.clearTrees) return StructureType.SURFACE;
        if (!entry.fillPedestal) {
            if (types.contains(StructureType.LIQUID_SURFACE)) return StructureType.LIQUID_SURFACE;
            return StructureType.SKY;
        }
        for (StructureType type : types) {
            if (type == StructureType.UNDERGROUND_DEEP || type == StructureType.UNDERGROUND_SHALLOW) return type;
        }
        return StructureType.UNDERGROUND_SHALLOW;
    }

    private static Material materialOrDefault(String name, FitAnything fit) {
        Material material = Material.matchMaterial(name);
        return material == null ? fit.getDefaultPedestalMaterial() : material;
    }
}
