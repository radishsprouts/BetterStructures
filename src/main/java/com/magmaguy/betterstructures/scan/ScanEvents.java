package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.api.BuildPasteCompleteEvent;
import com.magmaguy.betterstructures.api.BuildPlaceEvent;
import com.magmaguy.betterstructures.buildingfitter.FitAnything;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.List;

/**
 * 走査・貼り付けが出す Bukkit のイベント。試験ではサーバーが無いので、偽の実装に差し替える。
 * どちらも貼り付け先のリージョンのスレッドで呼ぶ。
 */
public interface ScanEvents {

    /** {@link BuildPlaceEvent} を出す。取り消されたら true。 */
    boolean firePlace(FitAnything fit);

    /** {@link BuildPasteCompleteEvent} を出す。 */
    void fireComplete(FitAnything fit, Location lowestCorner, Location highestCorner, List<Location> containers);

    /** 本番の実装。 */
    ScanEvents BUKKIT = new ScanEvents() {
        @Override
        public boolean firePlace(FitAnything fit) {
            BuildPlaceEvent event = new BuildPlaceEvent(fit);
            Bukkit.getServer().getPluginManager().callEvent(event);
            return event.isCancelled();
        }

        @Override
        public void fireComplete(FitAnything fit, Location lowestCorner, Location highestCorner, List<Location> containers) {
            Bukkit.getServer().getPluginManager().callEvent(
                    new BuildPasteCompleteEvent(fit, lowestCorner, highestCorner, containers));
        }
    };
}
