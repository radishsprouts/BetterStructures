package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.util.SurfaceMaterials;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * 建造物の上に突き出る木・葉などを取り除く。貼り付けの後に、所有リージョンで実物のワールドを読んで行う。
 * 図面の天井の1つ上から最大31ブロックまで、取り除ける素材が続く間だけ消す（今の経路と同じ）。
 */
public final class TreeClearer {

    /** 見る高さの上限。 */
    public static final int MAX_HEIGHT = 31;

    private TreeClearer() {
    }

    /**
     * 1列を片付ける。
     *
     * @param topY 図面の天井（最上層の1つ上）の世界 Y。今の経路と同じく、その1つ上から見る
     * @return 取り除いたブロックの数
     */
    public static int clear(World world, int x, int z, int topY) {
        int removed = 0;
        for (int offset = 1; offset <= MAX_HEIGHT; offset++) {
            Block block = world.getBlockAt(x, topY + offset, z);
            Material type = block.getType();
            if (type.isAir() || !SurfaceMaterials.ignorable(type)) break;
            block.setType(Material.AIR, false);
            removed++;
        }
        return removed;
    }
}
