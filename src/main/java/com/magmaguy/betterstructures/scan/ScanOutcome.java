package com.magmaguy.betterstructures.scan;

/** 走査1件の結末。{@code /bs place} が実行者へ伝えるのに使う。 */
public enum ScanOutcome {
    /** 合う場所を見つけ、貼り付けを始めた。 */
    PLACED("placed"),
    /** 合う場所が無かった（近くの候補が全部、基準に届かなかった）。 */
    NO_FIT("no fit"),
    /** その位置・条件に合う図面が無かった。 */
    NO_SCHEMATIC("no schematic"),
    /** {@code BuildPlaceEvent} が取り消された。 */
    CANCELLED("cancelled by a listener"),
    /** 再読み込みで中断した（自然生成は再読み込みの後でやり直す）。 */
    INTERRUPTED("interrupted by a reload"),
    /** 読み込みの失敗・貼り付けエンジンの失敗など。 */
    FAILED("failed");

    private final String description;

    ScanOutcome(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
