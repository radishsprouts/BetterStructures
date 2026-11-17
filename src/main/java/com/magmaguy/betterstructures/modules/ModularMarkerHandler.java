package com.magmaguy.betterstructures.modules;

import com.magmaguy.betterstructures.api.ChestFillEvent;
import com.magmaguy.betterstructures.chests.ChestContents;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.config.treasures.TreasureConfig;
import com.magmaguy.betterstructures.config.treasures.TreasureConfigFields;
import com.magmaguy.betterstructures.integration.EliteMobsHook;
import com.magmaguy.betterstructures.paste.MarkerHandler;
import com.magmaguy.betterstructures.schematic.model.StructureBlock;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * ダンジョンのモジュールの看板の目印を処理する {@link MarkerHandler}。PasteEngine が看板の段で、
 * その看板を所有するリージョンのスレッドから呼ぶ。
 *
 * <p>目印は {@code [spawn]}・{@code [chest]}・{@code [trapped_chest]}・{@code [exit]}・{@code [barrel]}・
 * {@code [elitemobs]}・{@code [pool:…]}・{@code [mythicmobs]}・{@code [custom]}。生成ワールドを作る設定
 * （{@code isWorldGeneration: true}）では、全部の目印を先に {@link ModularWorld#addSign} へ登録してから
 * 看板を空気に置き換える。目印でない看板は空気にする（{@link MarkerHandler.PlainSignPolicy#REMOVE}）。
 */
final class ModularMarkerHandler implements MarkerHandler {

    private static final List<String> MARKERS = List.of(
            "[spawn]", "[exit]", "[chest]", "[trapped_chest]", "[barrel]",
            "[elitemobs]", "[pool:", "[mythicmobs]", "[custom]");

    private final ModuleGeneratorsConfigFields generatorConfig;
    private final String spawnPoolSuffix;
    private final Location startLocation;
    /** 生成ワールドを作る設定の時だけ値を返す（それ以外は null を返す）。 */
    private final Supplier<ModularWorld> modularWorld;
    private final EliteMobsHook eliteMobs;
    private final ConcurrentHashMap<String, Optional<ChestContents>> treasureCache = new ConcurrentHashMap<>();
    private volatile boolean missingEliteMobsWarned;

    ModularMarkerHandler(ModuleGeneratorsConfigFields generatorConfig, String spawnPoolSuffix, Location startLocation,
                         Supplier<ModularWorld> modularWorld, EliteMobsHook eliteMobs) {
        this.generatorConfig = generatorConfig;
        this.spawnPoolSuffix = spawnPoolSuffix == null ? "" : spawnPoolSuffix;
        this.startLocation = startLocation;
        this.modularWorld = modularWorld;
        this.eliteMobs = eliteMobs;
    }

    /** 看板の文字に目印が1つでもあるか（大文字小文字は区別しない）。 */
    static boolean hasMarkerText(SignTextParser.ParsedSign sign) {
        if (sign == null) return false;
        for (int i = 0; i < 4; i++) {
            String line = sign.getFrontLine(i).toLowerCase(Locale.ROOT);
            for (String marker : MARKERS) if (line.contains(marker)) return true;
        }
        return false;
    }

    @Override
    public boolean isMarker(StructureBlock block) {
        if (block == null || !block.isSign()) return false;
        SignTextParser.ParsedSign sign = block.parsedSign();
        return sign.hasText() && hasMarkerText(sign);
    }

    @Override
    public PlainSignPolicy plainSignPolicy() {
        return PlainSignPolicy.REMOVE;
    }

    @Override
    public void handleMarker(World world, int x, int y, int z, StructureBlock block) {
        Block signBlock = world.getBlockAt(x, y, z);
        // 先に空気にする（リスナーや後続の処理が同じ位置にブロックを置けるように）
        signBlock.setType(Material.AIR, false);
        Block head = world.getBlockAt(x, y + 1, z);
        if (head.getType().isSolid()) head.setType(Material.AIR, false);

        SignTextParser.ParsedSign parsed = block.parsedSign();
        List<String> lines = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            String line = parsed.getFrontLine(i);
            if (!line.isBlank() && line.contains("[pool:")) line = line.replace("]", spawnPoolSuffix + "]");
            lines.add(line);
        }
        Location location = new Location(world, x, y, z);
        ModulePasting.InterpretedSign interpreted = new ModulePasting.InterpretedSign(location, lines);

        // 全部の目印を ModularWorld へ登録してから、個別の処理を行う
        ModularWorld generated = modularWorld.get();
        if (generated != null) {
            generated.addSign(interpreted);
            generated.spawnOtherEntitiesAt(interpreted);
        }

        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            if (lower.contains("[spawn]")) {
                spawnVanillaMob(world, location, lines, i);
                return;
            }
            if (lower.contains("[chest]")) {
                placeTreasureChest(signBlock, Material.CHEST);
                return;
            }
            if (lower.contains("[trapped_chest]")) {
                placeTreasureChest(signBlock, Material.TRAPPED_CHEST);
                return;
            }
        }
        if (generated == null) spawnSignBoss(interpreted);
    }

    private void spawnVanillaMob(World world, Location marker, List<String> lines, int markerLine) {
        String entityName = null;
        for (int j = markerLine + 1; j < lines.size(); j++) {
            String candidate = lines.get(j).replace("\"", "").trim();
            if (!candidate.isEmpty()) {
                entityName = candidate.toUpperCase(Locale.ROOT);
                break;
            }
        }
        // 敵の名前が無い [spawn] はプレイヤーの出現位置の目印（ModularWorld が持つ）
        if (entityName == null) return;
        if (entityName.equals("WITHER_CRYSTAL") || entityName.equals("ENDER_CRYSTAL")) entityName = "END_CRYSTAL";
        try {
            EntityType entityType = EntityType.valueOf(entityName);
            Entity entity = world.spawnEntity(ModulePasting.entitySpawnLocation(marker), entityType);
            entity.setPersistent(true);
            if (entity instanceof LivingEntity living) {
                living.setRemoveWhenFarAway(false);
                living.setNoDamageTicks(60);
                living.setFallDistance(0);
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            Logger.warn("Invalid entity type in sign: " + entityName + " (" + e.getMessage() + ")");
        }
    }

    private void placeTreasureChest(Block block, Material material) {
        block.setType(material, false);
        if (block.getState() instanceof Container container) fill(container, generatorConfig.getTreasureFile());
    }

    /** 生成ワールドを作らない設定では、ボスの看板で1回だけ出るボスを出す（通常の建造物の [elitemobs] と同じ）。 */
    private void spawnSignBoss(ModulePasting.InterpretedSign sign) {
        String bossFilename = ModulePasting.bossFilename(sign.text());
        if (bossFilename == null) return;
        if (!eliteMobs.isAvailable()) {
            if (!missingEliteMobsWarned) {
                Logger.warn("Modular structure at " + startLocation.getBlockX() + ", " + startLocation.getBlockY() + ", "
                        + startLocation.getBlockZ() + " has EliteMobs boss signs, but EliteMobs is not enabled. Its bosses were not spawned.");
                missingEliteMobsWarned = true;
            }
            return;
        }
        eliteMobs.spawnBoss(ModulePasting.entitySpawnLocation(sign.location()), bossFilename);
    }

    /** 宝の設定ファイルから中身を引く（ファイルごとに1回だけ読む。無ければ警告して null）。 */
    ChestContents contents(String filename) {
        if (filename == null || filename.isEmpty()) return null;
        return treasureCache.computeIfAbsent(filename, name -> {
            TreasureConfigFields config = TreasureConfig.getConfigFields(name);
            if (config == null) Logger.warn("Missing modular treasure configuration " + name);
            return Optional.ofNullable(config == null ? null : config.getChestContents());
        }).orElse(null);
    }

    private void fill(Container container, String filename) {
        ChestContents contents = contents(filename);
        if (contents == null) return;
        contents.rollChestContents(container);
        ChestFillEvent event = new ChestFillEvent(container, filename);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) container.update(true);
    }
}
