package com.magmaguy.betterstructures.integration;

import com.magmaguy.betterstructures.integration.worldguard.WorldGuardRegionProtection;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;

/**
 * 使える領域保護の実装を選ぶ。WorldGuard の型は {@code integration/worldguard/} の中だけにあり、
 * ここでは WorldGuard が有効だと確かめた分岐の中でしかその実装を生成しない
 * （WorldGuard が無いサーバーでこのクラスが読み込めるようにするため）。
 *
 * <p>onEnable の配線（統合時に本体が行う）:
 * {@code Services.setRegionProtection(RegionProtectionSelector.select())}。
 */
public final class RegionProtectionSelector {

    private RegionProtectionSelector() {
    }

    /** WorldGuard が有効ならその実装、無ければ {@link RegionProtection#none()}。 */
    public static RegionProtection select() {
        return select(Bukkit.getPluginManager().isPluginEnabled("WorldGuard"));
    }

    /** {@link #select()} の判定を外から渡す版（サーバー無しの試験用）。 */
    public static RegionProtection select(boolean worldGuardEnabled) {
        if (!worldGuardEnabled) return RegionProtection.none();
        try {
            return new WorldGuardRegionProtection();
        } catch (RuntimeException | LinkageError e) {
            Logger.warn("WorldGuard を使えませんでした。WorldGuard の版を確認してください。領域の保護は行いません: " + e);
            return RegionProtection.none();
        }
    }

    /**
     * 独自フラグ {@code betterstructures-protect} を登録する。WorldGuard が有効になる前の
     * {@code onLoad} で呼ぶ必要がある。WorldGuard が無ければ何もしない。
     */
    public static void initializeFlag() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return;
        try {
            WorldGuardRegionProtection.initializeFlag();
        } catch (RuntimeException | LinkageError e) {
            Logger.warn("WorldGuard のフラグを登録できませんでした。WorldGuard の版を確認してください: " + e);
        }
    }
}
