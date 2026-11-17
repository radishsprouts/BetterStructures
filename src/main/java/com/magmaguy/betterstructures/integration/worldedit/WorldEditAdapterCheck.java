package com.magmaguy.betterstructures.integration.worldedit;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Platform;

/**
 * WorldEdit がこのサーバーの版のアダプターを持っているかを確かめる。WorldEdit の型を使うので、
 * {@code WorldEditCompatibility} が WorldEdit の有効を確かめた分岐の中でだけ呼ぶ。
 */
public final class WorldEditAdapterCheck {

    private WorldEditAdapterCheck() {
    }

    /**
     * @return WorldEdit のデータ版。取得できない（プラットフォームが未準備・API が違う）時は null、
     * アダプターが無い時は -1。
     */
    public static Integer queryDataVersion() {
        try {
            Platform platform = WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.WORLD_EDITING);
            return platform.getDataVersion();
        } catch (RuntimeException | LinkageError exception) {
            return null;
        }
    }
}
