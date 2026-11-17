package com.magmaguy.betterstructures.paste;

/**
 * 1チャンクの貼り付けの段。上から順に進み、{@link #DONE} になって初めてそのチャンクの hold を外す（04章 G3）。
 */
public enum PasteStage {
    /** 通常のブロック（2周目に回すもの以外）を Y の昇順に置く。 */
    BLOCKS,
    /** 支えが要るブロック（光源・向き付き・レール等）を後から置く。 */
    SECOND_PASS,
    /** 看板: 目印の処理と、普通の看板の文字の復元。 */
    SIGNS,
    /** ブロックエンティティ（スポナー・コマンドブロック・頭・旗・書見台・ジュークボックス・容器の中身）。 */
    BLOCK_NBT,
    /** 宝箱・樽・シュルカーボックスの中身を戦利品で埋める。 */
    CONTAINERS,
    /** エンティティ（額縁・絵画を含む）。 */
    ENTITIES,
    /** 台座列の充填と、地表の木の除去。 */
    POST,
    /** このチャンクは終わり。 */
    DONE;

    public PasteStage next() {
        PasteStage[] all = values();
        return all[Math.min(all.length - 1, ordinal() + 1)];
    }
}
