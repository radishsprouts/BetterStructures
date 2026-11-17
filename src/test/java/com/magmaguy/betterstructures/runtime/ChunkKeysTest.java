package com.magmaguy.betterstructures.runtime;

import org.bukkit.Chunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** チャンクの鍵は {@code Chunk#getChunkKey} と同じ式で、負の座標でも往復できる。 */
final class ChunkKeysTest {

    @Test
    void BukkitのgetChunkKeyと一致する() {
        int[] values = {0, 1, -1, 15, -16, 1875000, -1875000, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int x : values) {
            for (int z : values) {
                assertEquals(Chunk.getChunkKey(x, z), ChunkKeys.key(x, z), "x=" + x + " z=" + z);
            }
        }
    }

    @Test
    void 鍵から座標へ往復できる() {
        int[] values = {0, 7, -7, 123456, -123456};
        for (int x : values) {
            for (int z : values) {
                long key = ChunkKeys.key(x, z);
                assertEquals(x, ChunkKeys.x(key));
                assertEquals(z, ChunkKeys.z(key));
            }
        }
    }

    @Test
    void xとzを入れ替えた座標は別の鍵になる() {
        assertNotEquals(ChunkKeys.key(1, 2), ChunkKeys.key(2, 1));
    }
}
