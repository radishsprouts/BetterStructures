package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 額縁・絵画の位置と向きの書き換え（0/90/180/270 度）。サーバー無しで確かめられる純粋な変換。
 */
final class EntityPlacerTest {

    @Test
    void 額縁のFacingは時計回りに回る() {
        // NORTH2 → EAST5 → SOUTH3 → WEST4 → NORTH2
        assertEquals(2, EntityPlacer.rotateFrameFacing(2, 0));
        assertEquals(5, EntityPlacer.rotateFrameFacing(2, 90));
        assertEquals(3, EntityPlacer.rotateFrameFacing(2, 180));
        assertEquals(4, EntityPlacer.rotateFrameFacing(2, 270));
        assertEquals(2, EntityPlacer.rotateFrameFacing(2, 360));
        assertEquals(3, EntityPlacer.rotateFrameFacing(5, 90));
        assertEquals(4, EntityPlacer.rotateFrameFacing(3, 90));
        assertEquals(2, EntityPlacer.rotateFrameFacing(4, 90));
    }

    @Test
    void 額縁の上と下は回転しても変わらない() {
        for (int degrees : new int[]{0, 90, 180, 270}) {
            assertEquals(0, EntityPlacer.rotateFrameFacing(0, degrees), "DOWN");
            assertEquals(1, EntityPlacer.rotateFrameFacing(1, degrees), "UP");
        }
    }

    @Test
    void 絵画のfacingは時計回りに回る() {
        // SOUTH0 WEST1 NORTH2 EAST3: NORTH2 → EAST3 → SOUTH0 → WEST1 → NORTH2
        assertEquals(3, EntityPlacer.rotatePaintingFacing(2, 90));
        assertEquals(0, EntityPlacer.rotatePaintingFacing(2, 180));
        assertEquals(1, EntityPlacer.rotatePaintingFacing(2, 270));
        assertEquals(2, EntityPlacer.rotatePaintingFacing(2, 0));
        assertEquals(1, EntityPlacer.rotatePaintingFacing(0, 90));
    }

    @Test
    void 水平の向きは4方向を巡り範囲外の値は変えない() {
        assertEquals(BlockFace.EAST, EntityPlacer.rotate(BlockFace.NORTH, 90));
        assertEquals(BlockFace.NORTH, EntityPlacer.rotate(BlockFace.WEST, 90));
        assertEquals(BlockFace.UP, EntityPlacer.rotate(BlockFace.UP, 90));
        assertEquals(BlockFace.SOUTH, EntityPlacer.rotate(BlockFace.NORTH, -180));
        assertEquals(9, EntityPlacer.rotateFrameFacing(9, 90));
    }

    @Test
    void 額縁のNBTは位置とblock_posと向きが回転後の値に書き換わり元は変わらない() {
        SimpleNbt.CompoundTag original = new SimpleNbt.CompoundTag();
        original.put("Facing", new SimpleNbt.ByteTag((byte) 2));
        original.put("TileX", new SimpleNbt.IntTag(0));
        original.put("TileY", new SimpleNbt.IntTag(0));
        original.put("TileZ", new SimpleNbt.IntTag(0));
        original.put("UUID", new SimpleNbt.IntArrayTag(new int[]{1, 2, 3, 4}));
        original.put("Pos", new SimpleNbt.ListTag(SimpleNbt.TAG_DOUBLE, List.of(new SimpleNbt.DoubleTag(0.5), new SimpleNbt.DoubleTag(0.5), new SimpleNbt.DoubleTag(0.5))));

        SimpleNbt.CompoundTag adjusted = EntityPlacer.adjust(original, "minecraft:item_frame", 12.5, 70.5, -3.5, 90);

        assertEquals(5, adjusted.getByte("Facing"), "NORTH を時計回り90度で EAST");
        assertEquals(12, adjusted.getInt("TileX"));
        assertEquals(70, adjusted.getInt("TileY"));
        assertEquals(-4, adjusted.getInt("TileZ"), "-3.5 の床は -4");
        assertFalse(adjusted.containsKey("UUID"), "UUID は引き継がない（重複の元）");
        SimpleNbt.ListTag pos = adjusted.getList("Pos");
        assertEquals(12.5, ((SimpleNbt.DoubleTag) pos.get(0)).asDouble());
        assertEquals(-3.5, ((SimpleNbt.DoubleTag) pos.get(2)).asDouble());
        assertEquals("minecraft:item_frame", adjusted.getString("id"));
        assertEquals(2, original.getByte("Facing"), "元の NBT は変えない");
        assertEquals(0, original.getInt("TileX"));
        assertTrue(original.containsKey("UUID"));
    }

    @Test
    void 新しい形のblock_posと文字列のfacingも書き換わる() {
        SimpleNbt.CompoundTag original = new SimpleNbt.CompoundTag();
        original.put("block_pos", new SimpleNbt.IntArrayTag(new int[]{0, 0, 0}));
        original.put("facing", new SimpleNbt.StringTag("north"));

        SimpleNbt.CompoundTag adjusted = EntityPlacer.adjust(original, "painting", 4.5, 65.0, 8.5, 180);

        assertEquals("south", adjusted.getString("facing"));
        assertEquals(List.of(4, 65, 8), List.of(adjusted.getIntArray("block_pos")[0], adjusted.getIntArray("block_pos")[1], adjusted.getIntArray("block_pos")[2]));
        assertEquals("minecraft:painting", adjusted.getString("id"));
        assertEquals(BlockFace.SOUTH, EntityPlacer.facingOf(adjusted, "painting"));
    }

    @Test
    void 向きの無いNBTからは向きを引かない() {
        assertNull(EntityPlacer.facingOf(new SimpleNbt.CompoundTag(), "item_frame"));
        assertTrue(EntityPlacer.isHanging("minecraft:glow_item_frame"));
        assertTrue(EntityPlacer.isHanging("PAINTING"));
        assertFalse(EntityPlacer.isHanging("minecraft:armor_stand"));
        assertFalse(EntityPlacer.isHanging(null));
    }

    @Test
    void SNBTは型の接尾辞と引用符のエスケープを持つ() {
        SimpleNbt.CompoundTag tag = new SimpleNbt.CompoundTag();
        tag.put("n", new SimpleNbt.StringTag("a\"b\\c"));
        tag.put("b", new SimpleNbt.ByteTag((byte) 3));
        tag.put("p", new SimpleNbt.ListTag(SimpleNbt.TAG_DOUBLE, List.of(new SimpleNbt.DoubleTag(1.5))));
        tag.put("i", new SimpleNbt.IntArrayTag(new int[]{1, 2}));

        String snbt = SimpleNbt.toSnbt(tag);

        assertTrue(snbt.contains("\"n\":\"a\\\"b\\\\c\""), snbt);
        assertTrue(snbt.contains("\"b\":3b"), snbt);
        assertTrue(snbt.contains("\"p\":[1.5d]"), snbt);
        assertTrue(snbt.contains("\"i\":[I;1,2]"), snbt);
    }
}
