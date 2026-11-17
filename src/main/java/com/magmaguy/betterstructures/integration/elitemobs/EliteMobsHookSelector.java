package com.magmaguy.betterstructures.integration.elitemobs;

import com.magmaguy.betterstructures.integration.EliteMobsHook;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;

/**
 * EliteMobs が使えるなら実体、無ければ {@link EliteMobsHook#none()} を返す。EliteMobs の型を
 * 一切書かないクラスで、{@link EliteMobsHookImpl} は EliteMobs が有効な時だけ読み込まれる。
 */
public final class EliteMobsHookSelector {

    private static volatile EliteMobsHook enabledInstance;

    private EliteMobsHookSelector() {
    }

    /** 呼ぶたびに EliteMobs の有効・無効を見る（実体は使い回す）。 */
    public static EliteMobsHook select() {
        if (!Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) return EliteMobsHook.none();
        EliteMobsHook local = enabledInstance;
        if (local != null) return local;
        try {
            local = new EliteMobsHookImpl();
        } catch (LinkageError error) {
            Logger.warn("EliteMobs is enabled but its API could not be linked (" + error + "). Bosses will not spawn.");
            return EliteMobsHook.none();
        }
        enabledInstance = local;
        return local;
    }
}
