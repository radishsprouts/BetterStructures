package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.schematic.model.StructureBlock;
import org.bukkit.World;

/**
 * 図面の看板に書かれた目印（{@code [spawn]}・{@code [chest]} 等）の扱い。PasteEngine が看板の段で呼ぶ。
 *
 * <p><b>呼び出すスレッド</b>: そのブロックを所有するリージョンのスレッド（Paper では
 * メインスレッド）だけ。実装は待たない・ブロックしない（{@code join}・{@code get}・{@code sleep} を書かない）。
 * 重い処理は WorkerPool へ回すこと。1回の呼び出しは軽く保つ（予算は PasteEngine が管理する）。
 *
 * <p><b>実装するのは</b>:
 * <ul>
 *   <li>{@code BuildingMarkerHandler}: U2（建造物。今の ChunkPasteTask の看板処理）</li>
 *   <li>{@code ModularMarkerHandler}: U3（ダンジョンのモジュール。ModularWorld.addSign へ登録する）</li>
 * </ul>
 */
public interface MarkerHandler {

    /** 目印でない看板を、貼り付けでどう扱うか。 */
    enum PlainSignPolicy {
        /** 図面の文字をそのまま復元して残す（建造物）。 */
        KEEP_TEXT,
        /** 空気に置き換える（ダンジョンのモジュール）。文字のない壁以外の看板は常に空気。 */
        REMOVE
    }

    /** この看板が目印か。看板でないブロック・文字のない看板は常に false を返すこと。 */
    boolean isMarker(StructureBlock block);

    /**
     * 目印を処理する。目印のブロックを空気にする責任もこのメソッドが持つ
     * （リスナーが同じ位置にブロックを置けるよう、先に空気にしてからイベントを出す）。
     *
     * @param world 貼り付け先のワールド
     * @param x     目印の世界座標 X
     * @param y     目印の世界座標 Y
     * @param z     目印の世界座標 Z
     * @param block 図面側の看板ブロック（BlockData・パース済みの看板の文字を持つ）
     */
    void handleMarker(World world, int x, int y, int z, StructureBlock block);

    /** 目印でない看板の扱い。 */
    PlainSignPolicy plainSignPolicy();
}
