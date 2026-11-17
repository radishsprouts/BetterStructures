package com.magmaguy.betterstructures.modules;

import com.magmaguy.betterstructures.paste.MarkerHandler;
import com.magmaguy.betterstructures.schematic.model.StructureBlock;
import org.bukkit.World;

/**
 * 看板の目印を持たない図面（出口の部品・デバッグ表示）を貼る時の {@link MarkerHandler}。
 * 看板は図面の文字のまま残す。
 */
final class NoMarkerHandler implements MarkerHandler {

    static final NoMarkerHandler INSTANCE = new NoMarkerHandler();

    private NoMarkerHandler() {
    }

    @Override
    public boolean isMarker(StructureBlock block) {
        return false;
    }

    @Override
    public void handleMarker(World world, int x, int y, int z, StructureBlock block) {
    }

    @Override
    public PlainSignPolicy plainSignPolicy() {
        return PlainSignPolicy.KEEP_TEXT;
    }
}
