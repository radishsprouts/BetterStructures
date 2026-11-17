package com.magmaguy.betterstructures.paste;

import org.bukkit.Location;

import java.util.List;

/**
 * 貼り付け1件の終わりの報告。{@link PasteRequest#onFinished()} と {@link PasteEngine.PasteHandle#completion()} が返す。
 *
 * @param outcome    終わり方
 * @param minCorner  貼り付けた範囲の最小の角（{@link Outcome#COMPLETED} 以外は null のことがある）
 * @param maxCorner  貼り付けた範囲の最大の角（同上）
 * @param containers 宝箱・樽等の位置（同上は空）
 */
public record PasteResult(Outcome outcome, Location minCorner, Location maxCorner, List<Location> containers) {

    /** 貼り付けの終わり方。 */
    public enum Outcome {
        /** 全部のチャンクが DONE になった。 */
        COMPLETED,
        /** 呼び出し側の取り消し、またはワールドの消滅。 */
        CANCELLED,
        /** 読み込みの再試行が上限を超えた等で、警告ログを出してセッションを終えた。 */
        FAILED
    }

    public PasteResult {
        containers = containers == null ? List.of() : List.copyOf(containers);
    }

    public static PasteResult of(Outcome outcome) {
        return new PasteResult(outcome, null, null, List.of());
    }
}
