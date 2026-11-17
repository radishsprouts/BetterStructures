package com.magmaguy.betterstructures.integration;

import org.bukkit.Location;

/**
 * EliteMobs との連携の窓口。EliteMobs の型はこのインターフェースに現れず、EliteMobs が無い時は
 * 実装クラスが読み込まれない。
 *
 * <p><b>呼び出すスレッド</b>: 目印の処理（所有リージョンのスレッド）から呼ぶ。実装は待たない・ブロックしない。
 *
 * <p><b>実装するのは</b>: U3（{@code integration/elitemobs/}。EliteMobs が入っている時だけ生成し、
 * 入っていなければ {@link #none()}）。利用するのは U2（建造物の看板）・U3（モジュールの看板・ModularWorld）。
 * 項目が足りなければ U3 が追加してよい（追加のみ。既存の署名は変えない）。
 */
public interface EliteMobsHook {

    /** EliteMobs が無い時の何もしない実装。 */
    static EliteMobsHook none() {
        return NoEliteMobsHook.INSTANCE;
    }

    /** EliteMobs が使える状態か。 */
    boolean isAvailable();

    /**
     * ボスを出現させる。
     *
     * @param location     出現位置
     * @param bossFilename ボスの設定ファイル名
     * @return 出現させられたか（設定が見つからない等は false）
     */
    boolean spawnBoss(Location location, String bossFilename);

    /** そのボスが、プレイヤーごとに別の実体を出す「インスタンス型」か。不明・EliteMobs 無しは false。 */
    boolean isInstancedBoss(String bossFilename);
}

/** {@link EliteMobsHook#none()} の実体。同じファイルに置いて外へ見せない。 */
final class NoEliteMobsHook implements EliteMobsHook {
    static final NoEliteMobsHook INSTANCE = new NoEliteMobsHook();

    private NoEliteMobsHook() {
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean spawnBoss(Location location, String bossFilename) {
        return false;
    }

    @Override
    public boolean isInstancedBoss(String bossFilename) {
        return false;
    }
}
