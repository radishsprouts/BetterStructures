package com.magmaguy.betterstructures.thirdparty;

import com.magmaguy.betterstructures.integration.RegionProtectionSelector;
import com.magmaguy.betterstructures.integration.worldguard.WorldGuardRegionProtection;
import com.magmaguy.betterstructures.modules.NaturalDungeonReservation;
import com.magmaguy.betterstructures.runtime.Services;
import com.magmaguy.elitemobs.api.EliteMobDeathEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * 従来の呼び出し元（{@code BetterStructures}・{@code FitAnything}・{@code NewChunkLoadEvent}）を
 * 壊さないための薄い窓口。WorldGuard の型はここには無く、実体は
 * {@code integration/worldguard/WorldGuardRegionProtection}（{@link Services#regionProtection()} 経由）。
 *
 * <p>リスナーとしての登録は従来どおり WorldGuard と EliteMobs が有効な時だけ（本体の onEnable）。
 * 登録された時だけ下のハンドラーが動き、その時は WorldGuard が有効なので実体を使える。
 *
 * @deprecated 呼び出し元が {@link Services#regionProtection()} と
 * {@link RegionProtectionSelector#initializeFlag()} を直接使うようになったら削除する。
 */
@Deprecated
public class WorldGuard implements Listener {

    public static void initializeFlag() {
        RegionProtectionSelector.initializeFlag();
    }

    public static void Protect(Location lowestCorner, Location highestCorner, String bossFilename, Location spawnLocation) {
        Services.regionProtection().protectBossArena(lowestCorner, highestCorner, bossFilename, spawnLocation);
    }

    public static boolean checkArea(Location location, Player player) {
        return Services.regionProtection().denyEdit(location, player);
    }

    /** 自然生成のダンジョンの範囲に、名前のある既存の領域が重なるか。 */
    public static boolean hasRegionOverlap(org.bukkit.World world, NaturalDungeonReservation.BlockBounds bounds) {
        return Services.regionProtection().overlapsRegion(
                world, bounds.minBlockX(), bounds.minBlockZ(), bounds.maxBlockX(), bounds.maxBlockZ());
    }

    @EventHandler
    public void onEliteDeath(EliteMobDeathEvent event) {
        if (Services.regionProtection() instanceof WorldGuardRegionProtection protection) {
            protection.handleEliteDeath(event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (checkArea(event.getBlock().getLocation(), event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (checkArea(event.getBlock().getLocation(), event.getPlayer())) event.setCancelled(true);
    }
}
