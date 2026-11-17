package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.util.SurfaceMaterials;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.function.Function;

/**
 * 建造物の下の柱を台座材で埋める。貼り付けの後に、所有リージョンで実物のワールドを読んで行う。
 * 深さの上限は10（今の経路と同じ）。1列ごとに呼ぶので、呼び出し側が列ごとに予算を確かめられる。
 */
public final class PedestalFiller {

    /** 台座の深さの上限。 */
    public static final int MAX_DEPTH = 10;

    private PedestalFiller() {
    }

    /**
     * 1列を埋める。
     *
     * @param baseY    図面の最下層の世界 Y
     * @param provider 台座材（引数は「下側（地表でない）か」の反対: 上が固体でなければ true=地表）
     * @return 置いたブロックの数
     */
    public static int fill(World world, int x, int z, int baseY, Function<Boolean, Material> provider) {
        if (provider == null) return 0;
        if (world.getBlockAt(x, baseY, z).getType().isAir()) return 0;
        int placed = 0;
        for (int offset = 1; offset <= MAX_DEPTH; offset++) {
            Block block = world.getBlockAt(x, baseY - offset, z);
            if (!SurfaceMaterials.ignorable(block.getType())) break;
            boolean surface = !world.getBlockAt(x, baseY - offset + 1, z).getType().isSolid();
            Material material = provider.apply(surface);
            if (material == null) break;
            block.setType(material, false);
            placed++;
        }
        return placed;
    }
}
