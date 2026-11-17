package com.magmaguy.betterstructures.integration;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 領域保護（WorldGuard）との連携の窓口。WorldGuard の型はこのインターフェースに現れない。
 *
 * <p><b>呼び出すスレッド</b>: 保護の追加は貼り付けの完了時にそのリージョンのスレッドから、
 * 重なり判定は新チャンクのイベント（所有リージョン）から呼ぶ。実装は待たない・ブロックしない。
 *
 * <p><b>実装するのは</b>: U4（{@code integration/worldguard/}。WorldGuard が入っている時だけ生成する。
 * 入っていなければ {@link #none()}）。利用するのは U2（完了時の保護）・U1（重なり判定）。
 */
public interface RegionProtection {

    /** WorldGuard が無い時の何もしない実装。 */
    static RegionProtection none() {
        return NoRegionProtection.INSTANCE;
    }

    /** 実際に保護を行える実装か（{@link #none()} は false）。 */
    boolean isAvailable();

    /**
     * EliteMobs の闘技場を保護する領域を作る。
     *
     * @param lowestCorner  範囲の最小の角
     * @param highestCorner 範囲の最大の角
     * @param bossFilename  ボスの設定ファイル名（領域 ID に使う）
     * @param spawnLocation ボスの出現位置
     */
    void protectBossArena(Location lowestCorner, Location highestCorner, String bossFilename, Location spawnLocation);

    /**
     * 指定の柱（Y 全域）に、名前のある既存の領域が重なるか。重なれば生成を避ける。
     * 領域の管理が取得できない時は安全側として true を返してよい。
     */
    boolean overlapsRegion(World world, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ);

    /** 保護された領域の編集を止める。止めた時は理由をプレイヤーへ送り true を返す。 */
    boolean denyEdit(Location location, Player player);
}

/** {@link RegionProtection#none()} の実体。同じファイルに置いて外へ見せない。 */
final class NoRegionProtection implements RegionProtection {
    static final NoRegionProtection INSTANCE = new NoRegionProtection();

    private NoRegionProtection() {
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void protectBossArena(Location lowestCorner, Location highestCorner, String bossFilename, Location spawnLocation) {
    }

    @Override
    public boolean overlapsRegion(World world, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        return false;
    }

    @Override
    public boolean denyEdit(Location location, Player player) {
        return false;
    }
}
