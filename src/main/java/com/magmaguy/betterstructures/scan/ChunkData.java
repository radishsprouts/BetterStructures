package com.magmaguy.betterstructures.scan;

import org.bukkit.Material;
import org.bukkit.block.Biome;

/**
 * 取り込んだチャンク1つ分の読み取り専用の中身。本番は {@code ChunkSnapshot}（{@link SnapshotChunkData}）、
 * 試験では偽の実装を使う。座標はチャンク内の 0〜15。どのスレッドから呼んでも安全であること。
 */
public interface ChunkData {

    /** ブロックの種類。 */
    Material type(int localX, int y, int localZ);

    /** 水を含むブロックか（含水ブロック・昆布・海草・泡の柱）。高さマップ（MOTION_BLOCKING）の判定に使う。 */
    boolean containsWater(int localX, int y, int localZ);

    /** 列の最も高い非空気ブロックの Y（スナップショットの高さマップ）。空の列は最低 Y より下。 */
    int highestY(int localX, int localZ);

    /** バイオーム。Y が範囲外なら範囲内へ丸める（サーバーの噪音バイオームの取り方と同じ）。 */
    Biome biome(int localX, int y, int localZ);
}
