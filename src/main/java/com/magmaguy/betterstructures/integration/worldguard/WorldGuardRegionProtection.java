package com.magmaguy.betterstructures.integration.worldguard;

import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.integration.RegionProtection;
import com.magmaguy.elitemobs.api.EliteMobDeathEvent;
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity;
import com.magmaguy.elitemobs.mobconstructor.custombosses.RegionalBossEntity;
import com.magmaguy.magmacore.util.Logger;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * WorldGuard による領域保護。WorldGuard が有効な時だけ
 * {@link com.magmaguy.betterstructures.integration.RegionProtectionSelector} が生成する。
 * このパッケージの外には WorldGuard の型を出さない。
 *
 * <p>呼び出すスレッドは {@link RegionProtection} のとおり（貼り付け完了時・新チャンクのイベントのリージョンスレッド）。
 * 領域の追加・参照はメモリ上の操作で、待たない。
 */
public final class WorldGuardRegionProtection implements RegionProtection {
    private static final String FLAG_NAME = "betterstructures-protect";
    private static StateFlag betterStructuresProtected = null;
    private static final StateFlag.State allow = StateFlag.State.ALLOW;

    /** 独自フラグを登録する。{@code onLoad}（WorldGuard が有効になる前）で呼ぶ。 */
    public static void initializeFlag() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return;

        FlagRegistry registry;
        try {
            registry = com.sk89q.worldguard.WorldGuard.getInstance().getFlagRegistry();
        } catch (Exception ex) {
            Logger.warn("Something went wrong while loading WorldGuard. Are you using the right WorldGuard version?");
            return;
        }

        if (betterStructuresProtected != null) {
            Logger.info("Flag " + FLAG_NAME + " is already registered, this is normal if the plugin or server have just been reloaded.");
            return;
        }

        Logger.info("Enabling flags:");
        try {
            betterStructuresProtected = new StateFlag(FLAG_NAME, false);
            registry.register(betterStructuresProtected);
            Logger.info(" - " + FLAG_NAME);
        } catch (FlagConflictException | IllegalStateException e) {
            Logger.warn("Warning: flag " + FLAG_NAME + " already exists! This is normal if you've just now reloaded BetterStructures.");
            betterStructuresProtected = (StateFlag) registry.get(FLAG_NAME);
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void protectBossArena(Location lowestCorner, Location highestCorner, String bossFilename, Location spawnLocation) {
        if (spawnLocation == null || spawnLocation.getWorld() == null) return;
        RegionContainer regionContainer = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regionManager = regionContainer.get(BukkitAdapter.adapt(spawnLocation.getWorld()));
        if (regionManager == null) return;
        BlockVector3 min = BlockVector3.at(lowestCorner.getX(), lowestCorner.getY(), lowestCorner.getZ());
        BlockVector3 max = BlockVector3.at(highestCorner.getX(), highestCorner.getY(), highestCorner.getZ());
        ProtectedRegion region = new ProtectedCuboidRegion(regionIdFor(bossFilename, spawnLocation), min, max);
        region.setFlag(betterStructuresProtected, allow);
        region.setFlag(Flags.PASSTHROUGH, allow);
        regionManager.addRegion(region);
    }

    @Override
    public boolean overlapsRegion(World world, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        RegionContainer regionContainer = com.sk89q.worldguard.WorldGuard.getInstance()
                .getPlatform().getRegionContainer();
        RegionManager regionManager = regionContainer.get(BukkitAdapter.adapt(world));
        // 領域の管理が取得できない時は安全側（生成を避ける）
        if (regionManager == null) return true;

        ProtectedRegion probe = new ProtectedCuboidRegion(
                "__betterstructures_natural_generation_probe__",
                BlockVector3.at(minBlockX, world.getMinHeight(), minBlockZ),
                BlockVector3.at(maxBlockX, world.getMaxHeight() - 1, maxBlockZ));
        for (ProtectedRegion region : regionManager.getApplicableRegions(probe))
            if (!ProtectedRegion.GLOBAL_REGION.equals(region.getId())) return true;
        return false;
    }

    @Override
    public boolean denyEdit(Location location, Player player) {
        com.sk89q.worldedit.util.Location wgLocation = BukkitAdapter.adapt(location);
        RegionContainer container = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
        ApplicableRegionSet set = container.createQuery().getApplicableRegions(wgLocation);
        if (set.testState(null, betterStructuresProtected)) {
            player.sendMessage(DefaultConfig.getRegionProtectedMessage());
            return true;
        }
        return false;
    }

    /** ボスが死んだら闘技場の保護を外す（{@code removeAfterDeath} の時だけ）。 */
    private static void unprotect(CustomBossEntity customBossEntity) {
        if (!customBossEntity.getCustomBossesConfigFields().isRemoveAfterDeath()) return;
        RegionManager regionManager = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(customBossEntity.getLocation().getWorld()));
        if (regionManager == null) return;
        String regionId = regionIdFor(
                customBossEntity.getCustomBossesConfigFields().getFilename(),
                customBossEntity.getSpawnLocation());
        if (regionManager.getRegion(regionId) == null) return;
        regionManager.removeRegion(regionId);
    }

    private static String regionIdFor(String bossFilename, Location spawnLocation) {
        return "betterstructures_autoprotected_" + bossFilename.replace(".yml", "") + "_"
                + spawnLocation.getBlockX() + "_" + spawnLocation.getBlockY() + "_" + spawnLocation.getBlockZ();
    }

    /**
     * EliteMobs のボスの死亡イベントを受けて闘技場の保護を外す。呼び出し元は
     * {@code thirdparty.WorldGuard} のリスナー（WorldGuard と EliteMobs が有効な時だけ登録される）。
     */
    public void handleEliteDeath(EliteMobDeathEvent event) {
        if (event.getEliteEntity() instanceof RegionalBossEntity) unprotect((CustomBossEntity) event.getEliteEntity());
    }
}
