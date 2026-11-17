package com.magmaguy.betterstructures.modules;

import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfig;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.integration.EliteMobsHook;
import com.magmaguy.betterstructures.paste.PasteJournal;
import com.magmaguy.betterstructures.paste.PasteRequest;
import com.magmaguy.betterstructures.runtime.RuntimeSettings;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 停止をまたぐ貼り付けの再開（PasteJournal）で、ダンジョンのモジュールの依頼を作り直す。
 *
 * <p>記録の図面名には、{@link ModulePasting} が {@link #encode} で「ダンジョンの設定名・生成の接尾辞・
 * モジュールの図面名」を入れてある。ここでそれを読み、モジュールの登録（{@link ModulesContainer}）から図面を引き、
 * 回転と整形を同じ手順でやり直す。ワールドには触れないので WorkerPool のスレッドから呼べる。
 *
 * <p>制約: 生成ワールド（{@code isWorldGeneration: true}）の続きでは、完了の通知先（ModularWorld）が
 * 失われているので通知しない。目印の処理・宝箱の中身・ボスは再開後も行う。
 */
public final class ModuleResume {
    static final String PREFIX = "dungeon|";
    private static final String SEPARATOR = "|";

    private ModuleResume() {
    }

    /** 記録の図面名を作る。ダンジョンの設定名が分からない時は、モジュールの図面名そのまま。 */
    static String encode(String generatorFilename, String spawnPoolSuffix, String clipboardFilename) {
        if (generatorFilename == null || generatorFilename.isEmpty()) return clipboardFilename;
        return PREFIX + generatorFilename + SEPARATOR + (spawnPoolSuffix == null ? "" : spawnPoolSuffix)
                + SEPARATOR + clipboardFilename;
    }

    /** ダンジョンのモジュールの記録か。 */
    public static boolean handles(PasteJournal.Entry entry) {
        return entry != null && entry.schematicName != null && entry.schematicName.startsWith(PREFIX);
    }

    /**
     * @return 作り直した依頼。ダンジョンの設定・モジュールの図面のどちらかが今は無ければ null（記録は残る）
     */
    public static PasteRequest rebuild(PasteJournal.Entry entry, EliteMobsHook eliteMobs) {
        if (!handles(entry)) return null;
        String[] parts = entry.schematicName.substring(PREFIX.length()).split("\\|", 3);
        if (parts.length != 3) return null;
        String generatorFilename = parts[0];
        String suffix = parts[1];
        String clipboardFilename = parts[2];

        ModuleGeneratorsConfigFields generator = ModuleGeneratorsConfig.getConfigFields(generatorFilename);
        ModulesContainer module = ModulesContainer.getModulesContainers()
                .get(clipboardFilename + "_rotation_" + entry.rotationDegrees);
        if (generator == null || module == null || module.getStructureSchematic() == null || entry.worldId == null) {
            return null;
        }
        World world = Bukkit.getWorld(UUID.fromString(entry.worldId));
        if (world == null) return null;

        Location base = new Location(world, entry.baseX, entry.baseY, entry.baseZ);
        // 全体の起点は記録に無いので、このモジュールの位置で代える（ボスの警告の文言にだけ使う）。
        ModularMarkerHandler markers = new ModularMarkerHandler(generator, suffix, base, () -> null, eliteMobs);
        StructureSchematic rotated = module.getStructureSchematic().rotate(entry.rotationDegrees);
        StructureSchematic prepared = ModulePasting.prepare(rotated, generator.isWorldGeneration());
        return PasteRequest.builder(world, base, prepared, markers)
                .schematicOffset(new Vector(entry.offsetX, entry.offsetY, entry.offsetZ))
                .schematicName(entry.schematicName)
                .rotationDegrees(entry.rotationDegrees)
                .contents(markers.contents(generator.getTreasureFile()),
                        ModulePasting.barrelContents(markers, generator, module.getModulesConfigField()))
                .bedrockReplacement(entry.bedrockReplacement == null ? Material.STONE : material(entry.bedrockReplacement))
                .windowChunks(entry.windowChunks > 0 ? entry.windowChunks : RuntimeSettings.get().wfcWindowChunks())
                .announce(false)
                .build();
    }

    private static Material material(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null) {
            Logger.warn("貼り付けの記録の岩盤の置き換え先 " + name + " を解釈できないため STONE を使います。");
            return Material.STONE;
        }
        return material;
    }
}
