package com.magmaguy.betterstructures.scan;

/**
 * 採点が見る図面の形。ブロックの種類のうち、採点に要る「空気か・液体か」だけを返す
 * （試験では配列から作った偽の実装を使う）。座標は図面内の 0 始まり。
 */
public interface SchematicShape {
    int OTHER = 0;
    int AIR = 1;
    int LIQUID = 2;

    int width();

    int height();

    int depth();

    int offsetX();

    int offsetY();

    int offsetZ();

    /** {@link #OTHER}・{@link #AIR}・{@link #LIQUID}（水・溶岩）のどれか。 */
    int sample(int x, int y, int z);
}
