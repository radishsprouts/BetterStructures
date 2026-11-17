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

    // ---- ここから U3 が追加した項目（追加のみ。既存の署名は変えていない）。すべて既定実装を持つ ----

    /** そのボスの設定ファイルが EliteMobs に存在するか。EliteMobs 無しは false。 */
    default boolean bossExists(String bossFilename) {
        return false;
    }

    /**
     * スポーンプールが選んだ、インスタンス型でないボスをその場に出す（ダンジョンの看板用）。
     * 出現させられたら true。EliteMobs 無し・設定が無い時は false。
     */
    default boolean spawnPoolBoss(Location location, String bossFilename) {
        return false;
    }

    /**
     * インスタンス型のボスを出す。戻り値は EliteMobs の {@code InstancedBossEntity}
     * （EliteMobs の型をこのインターフェースに出さないため {@link Object}）。
     * 呼び出し側（{@code ModularWorld}）は公開 API の戻り値としてそのまま渡す。出せなければ null。
     *
     * @param location          出現位置
     * @param level             ボスのレベル
     * @param difficultyId      その位置の難易度の識別子
     * @param originalSpawnPool 元のスポーンプールのファイル名（ボスの付随データに残す）
     */
    default Object spawnInstancedBoss(Location location, String bossFilename, int level,
                                      String difficultyId, String originalSpawnPool) {
        return null;
    }
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
