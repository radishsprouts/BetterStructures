package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.api.BuildCustomMarkerEvent;
import com.magmaguy.betterstructures.buildingfitter.FitAnything;
import com.magmaguy.betterstructures.config.spawnpools.SpawnPoolsConfig;
import com.magmaguy.betterstructures.config.spawnpools.SpawnPoolsConfigFields;
import com.magmaguy.betterstructures.integration.EliteMobsHook;
import com.magmaguy.betterstructures.schematic.model.StructureBlock;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import com.magmaguy.betterstructures.thirdparty.MythicMobs;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 建造物の看板の目印（{@code [spawn]}・{@code [pool:]}・{@code [elitemobs]}・{@code [mythicmobs]}・
 * {@code [custom]}・{@code [chest]}・{@code [trapped_chest]}）の処理。以前の {@code ChunkPasteTask} の看板処理をそのまま移した。
 * 目印でない看板は文字を復元して残す。
 *
 * <p>{@code [chest]} と {@code [trapped_chest]} は、その位置に容器を置く。中身は貼り付けエンジンが
 * 看板の段の後に、容器になっていればコンテナの段で埋める。
 */
public final class BuildingMarkerHandler implements MarkerHandler {

    private final FitAnything fitter;
    private final EliteMobsHook eliteMobs;

    /**
     * @param fitter    {@code BuildCustomMarkerEvent} に載せる建造物（null 可）
     * @param eliteMobs EliteMobs との連携（{@link EliteMobsHook#none()} 可）
     */
    public BuildingMarkerHandler(FitAnything fitter, EliteMobsHook eliteMobs) {
        this.fitter = fitter;
        this.eliteMobs = eliteMobs == null ? EliteMobsHook.none() : eliteMobs;
    }

    @Override
    public boolean isMarker(StructureBlock block) {
        if (block == null || !block.isSign()) return false;
        return isMarker(block.parsedSign());
    }

    /** 看板の文字に目印が含まれるか。 */
    public static boolean isMarker(SignTextParser.ParsedSign sign) {
        if (sign == null) return false;
        for (int i = 0; i < 4; i++) {
            String line = sign.getFrontLine(i).toLowerCase(Locale.ROOT).trim();
            if (line.contains("[spawn]") || line.contains("[elitemobs]")
                    || line.contains("[pool:") || line.contains("[mythicmobs]")
                    || line.contains("[custom]") || line.contains("[chest]")
                    || line.contains("[trapped_chest]")) {
                return true;
            }
        }
        return false;
    }

    /** {@code [elitemobs]} の目印に書かれたボスの設定ファイル名。目印でなければ null。 */
    public static String eliteMobsBossFilename(StructureBlock block) {
        if (block == null || !block.isSign()) return null;
        List<String> lines = lines(block.parsedSign());
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase(Locale.ROOT).contains("[elitemobs]")) {
                StringBuilder bossFile = new StringBuilder();
                for (int j = i + 1; j < lines.size(); j++) {
                    String part = lines.get(j).trim();
                    if (!part.isEmpty()) bossFile.append(part);
                }
                return bossFile.length() > 0 ? bossFile.toString() : null;
            }
        }
        return null;
    }

    @Override
    public PlainSignPolicy plainSignPolicy() {
        return PlainSignPolicy.KEEP_TEXT;
    }

    private static List<String> lines(SignTextParser.ParsedSign sign) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add(sign.getFrontLine(i).trim());
        }
        return lines;
    }

    @Override
    public void handleMarker(World world, int bx, int by, int bz, StructureBlock block) {
        SignTextParser.ParsedSign sign = block.parsedSign();
        if (sign == null) return;

        // 1. 目印の看板そのものを先に空気にする。
        world.getBlockAt(bx, by, bz).setType(Material.AIR, false);

        // 2. 窒息の防止: 頭の高さが固体なら空気にする。
        Block headBlock = world.getBlockAt(bx, by + 1, bz);
        if (headBlock.getType().isSolid()) {
            headBlock.setType(Material.AIR, false);
        }

        Location loc = new Location(world, bx + 0.5, by, bz + 0.5);
        List<String> lines = lines(sign);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);

            if (lower.contains("[spawn]")) {
                String entityName = null;
                for (int j = i + 1; j < lines.size(); j++) {
                    String candidate = lines.get(j).replace("\"", "").trim();
                    if (!candidate.isEmpty()) {
                        entityName = candidate.toUpperCase(Locale.ROOT);
                        break;
                    }
                }
                if (entityName != null) {
                    spawnVanillaMob(loc, entityName);
                } else {
                    Logger.warn("[spawn] の看板 (" + bx + "," + by + "," + bz + ") に生き物の種類がありません");
                }
                return;
            }

            if (lower.contains("[pool:")) {
                String poolName = SpawnPoolsConfig.extractPoolName(line);
                if (poolName != null) {
                    spawnPoolMob(loc, poolName);
                } else {
                    Logger.warn("スポーンプールの名前を読み取れません: " + line);
                }
                return;
            }

            if (lower.contains("[elitemobs]")) {
                String bossFile = eliteMobsBossFilename(block);
                if (bossFile != null) {
                    eliteMobs.spawnBoss(loc, bossFile);
                }
                return;
            }

            if (lower.contains("[mythicmobs]")) {
                String mob = (i + 1 < lines.size()) ? lines.get(i + 1).trim() : "";
                String level = (i + 2 < lines.size()) ? lines.get(i + 2).trim() : "";
                if (!mob.isEmpty()) {
                    MythicMobs.Spawn(loc, level.isEmpty() ? mob : mob + ":" + level);
                }
                return;
            }

            if (lower.contains("[custom]")) {
                List<String> args = new ArrayList<>();
                for (int j = i + 1; j < lines.size(); j++) args.add(lines.get(j).trim());
                Bukkit.getPluginManager().callEvent(new BuildCustomMarkerEvent(fitter, loc, args));
                return;
            }

            if (lower.contains("[chest]")) {
                world.getBlockAt(bx, by, bz).setType(Material.CHEST, false);
                return;
            }
            if (lower.contains("[trapped_chest]")) {
                world.getBlockAt(bx, by, bz).setType(Material.TRAPPED_CHEST, false);
                return;
            }
        }
    }

    private void spawnVanillaMob(Location loc, String entityName) {
        try {
            String cleanName = entityName.replace("\"", "").trim().toUpperCase(Locale.ROOT);
            if (cleanName.equals("WITHER_CRYSTAL") || cleanName.equals("ENDER_CRYSTAL")) {
                cleanName = "END_CRYSTAL";
            }
            EntityType type = EntityType.valueOf(cleanName);
            Class<? extends Entity> entityClass = type.getEntityClass();
            World world = loc.getWorld();
            if (entityClass != null && LivingEntity.class.isAssignableFrom(entityClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends LivingEntity> livingClass = (Class<? extends LivingEntity>) entityClass;
                world.spawn(loc, livingClass, living -> {
                    living.setRemoveWhenFarAway(false);
                    living.setPersistent(true);
                    living.setNoDamageTicks(60);
                    living.setFallDistance(0);
                });
            } else {
                Entity entity = world.spawnEntity(loc, type);
                entity.setPersistent(true);
                if (entity instanceof LivingEntity living) {
                    living.setRemoveWhenFarAway(false);
                    living.setNoDamageTicks(60);
                    living.setFallDistance(0);
                }
            }
        } catch (Exception e) {
            Logger.warn("看板から生き物を出せませんでした: " + entityName + " (" + e.getMessage() + ")");
        }
    }

    private void spawnPoolMob(Location loc, String poolName) {
        String poolFilename = poolName.endsWith(".yml") ? poolName : poolName + ".yml";
        SpawnPoolsConfigFields pool = SpawnPoolsConfig.getConfigFields(poolFilename);
        if (pool == null) {
            Logger.warn("スポーンプールが見つかりません: " + poolFilename);
            return;
        }
        String bossFilename = SpawnPoolsConfig.pickBossFilename(pool);
        if (bossFilename == null) {
            Logger.warn("スポーンプール " + poolFilename + " に項目がありません");
            return;
        }
        if (eliteMobs.isAvailable()) {
            eliteMobs.spawnBoss(loc, bossFilename);
        } else {
            String cleanName = bossFilename.replace(".yml", "").toUpperCase(Locale.ROOT);
            try {
                EntityType type = EntityType.valueOf(cleanName);
                spawnVanillaMob(loc, type.name());
            } catch (Exception ignored) {
                Logger.warn("EliteMobs が無効なため、プール " + poolFilename + " のボス " + bossFilename + " は出せません");
            }
        }
    }
}
