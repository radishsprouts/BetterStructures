package com.magmaguy.betterstructures.scan;

import org.bukkit.Material;
import org.bukkit.block.Biome;

/**
 * 地形走査の採点が読むブロックの供給元。{@link org.bukkit.World} の代わりに採点関数へ渡す。
 *
 * <p><b>呼び出すスレッド</b>: 採点は WorkerPool（専用スレッド）で行うので、実装は
 * どのスレッドから呼んでも安全で、ワールドへ触れてはいけない（リージョンの外のチャンクに
 * 同期で触らない）。呼び出し側は、読み込み済みのチャンクの範囲の座標だけを渡す。
 *
 * <p><b>実装するのは</b>: U1（地形走査）。{@code SnapshotBlockSource}（チャンクの鍵 → ChunkSnapshot）。
 * テストでは偽の実装を使う。採点側（U1 の {@code TopologyScorer} 等）がこの型だけを見る。
 *
 * <p>座標はすべてワールドのブロック座標。取り込んでいない範囲を読んだ時の挙動は実装が決めるが、
 * 例外を投げず、空気（{@link Material#AIR}）として扱うのを標準とする。
 */
public interface BlockSource {

    /** 取得できる最低の Y（含む）。{@code World#getMinHeight()} と同じ意味。 */
    int minY();

    /** 取得できる最高の Y（含まない）。{@code World#getMaxHeight()} と同じ意味。 */
    int maxY();

    /** 指定位置のブロックの種類。 */
    Material getType(int x, int y, int z);

    /**
     * 列の最も高い非空気ブロックの Y。{@code World#getHighestBlockYAt(x, z)} と同じ値を返すこと
     * （高さマップの種類・+1 のずれを含めて。U1 が確かめて合わせる）。
     */
    int getHighestBlockY(int x, int z);

    /** 指定位置のバイオーム。 */
    Biome getBiome(int x, int y, int z);
}
