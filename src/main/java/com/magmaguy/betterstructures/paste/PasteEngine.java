package com.magmaguy.betterstructures.paste;

import java.util.concurrent.CompletableFuture;

/**
 * 建造物とダンジョンのモジュールを貼る、唯一の貼り付けエンジン。WorldEdit/FAWE が入っていても
 * 貼り付けは常にこのエンジンで行う（社長決定・2026-11-18）。
 *
 * <p><b>呼び出すスレッド</b>: どのスレッドからでも呼べる（Governor の振り分け・リージョン・
 * WorkerPool）。{@link #start} はキューに入れて即座に返り、ブロックしない。ブロックの書き込みは
 * 所有リージョンのスレッドで、ThreadTickBudget の予算の範囲で行い、予算が切れたら
 * {@code runRegionLater(…, 1L)} で続きから再開する。
 *
 * <p><b>実装するのは</b>: U2。利用するのは U1（建造物の決定段）・U3（モジュール、{@code windowChunks}）・
 * U5（{@link #shutdown()} の呼び出し）。
 *
 * <p>千切れ防止の約束（04章 G1〜G8）: 書き込むチャンクのチケットは hold から DONE まで
 * 常に 1 以上。時間切れでは捨てない。{@code /bs reload} で走っている貼り付けを取り消さない。
 */
public interface PasteEngine {

    /**
     * 貼り付けを依頼する。依頼は捨てられない（流量制御は待たせるだけ）。
     *
     * @return 取り消しと完了の待ち受けに使う取っ手
     */
    PasteHandle start(PasteRequest request);

    /** 実行中・待機中の貼り付けの件数。 */
    int pendingCount();

    /**
     * プラグイン停止（onDisable）で呼ぶ。新しい依頼を受けず、PasteJournal へ最後の1回を
     * <b>同期で</b>書いて、次の起動で続きから再開できるようにする。この呼び出しはスレッドを
     * 待たせるが、停止処理の中でだけ許す。
     */
    void shutdown();

    /** 貼り付け1件の取っ手。 */
    interface PasteHandle {
        /** 取り消す。hold は DONE でないチャンクの分も含めて解放する。 */
        void cancel();

        /** 終わったか（完了・取り消し・失敗のいずれか）。 */
        boolean isDone();

        /** 終わった時に完了する。待たずに {@code thenAccept} 等でつなぐこと。 */
        CompletableFuture<PasteResult> completion();
    }
}
