package com.magmaguy.betterstructures.runtime;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * スレッドごとの 50ms 窓の予算。このプラグインがリージョンスレッド（Paper ではメインスレッド）で行う
 * 仕事（スナップショットの取得・ブロックの書き込み・ブロック NBT・エンティティ・台座と木の処理）は
 * すべてこの予算を共有する。
 *
 * <p>Folia ではリージョンの tick 番号を公開 API で安定して取れないので、スレッドごとに
 * 「窓の開始時刻」と「この窓で使ったナノ秒」を持ち、50ms 経ったら窓を切り替える。
 * 窓あたりの上限は {@code 50ms × percentageOfTickUsedForPasting}（本番 0.2 なら 10ms）。
 *
 * <p>遅れを見て絞る（AIMD）: {@code runRegionLater(…, 1L)} で予約した時刻と実際に動いた時刻の差
 * （{@link #observeLag(long)}）が閾値（既定 100ms）を超えたら、そのスレッドの上限を係数（既定 0.5）倍に絞る
 * （下限あり）。遅れの無い窓が続けば窓ごとに一定量（既定 0.25ms）ずつ元の上限まで戻す。
 * Folia で挙動が未確認の TPS 取得には頼らない。
 *
 * <p>呼び出すスレッド: 予算を使うスレッド自身（リージョンスレッド）。状態はスレッドごとなので
 * 他のスレッドと共有されず、ロックしない。
 */
public final class ThreadTickBudget {

    private static final ThreadLocal<ThreadTickBudget> CURRENT = ThreadLocal.withInitial(
            () -> new ThreadTickBudget(System::nanoTime, RuntimeSettings::get));

    private final LongSupplier clock;
    private final Supplier<FlowSettings> settings;

    private boolean started;
    private long windowStart;
    private long usedNanos;
    /** 現在の上限（絞られていれば最大値より小さい）。-1 は未初期化。 */
    private long limitNanos = -1;
    private boolean lagInWindow;

    /** 現在のスレッドの予算。 */
    public static ThreadTickBudget current() {
        return CURRENT.get();
    }

    /** 「今の時刻 + delayTicks 個ぶんの tick」。{@link #observeLag(long)} に渡す予約時刻を作る。 */
    public static long expectedRunNanos(long delayTicks) {
        return System.nanoTime() + Math.max(1L, delayTicks) * FlowSettings.TICK_NANOS;
    }

    /** 試験用。時計と設定を差し替えられる。通常は {@link #current()} を使う。 */
    public ThreadTickBudget(LongSupplier clock, Supplier<FlowSettings> settings) {
        this.clock = clock;
        this.settings = settings;
    }

    /** まだ予算が残っているか。窓が終わっていれば先に切り替える。 */
    public boolean hasBudget() {
        rollWindow();
        return usedNanos < limitNanos;
    }

    /** この窓の残りナノ秒（0 以上）。 */
    public long remainingNanos() {
        rollWindow();
        return Math.max(0L, limitNanos - usedNanos);
    }

    /** 使った時間を計上する。 */
    public void consume(long nanos) {
        rollWindow();
        if (nanos > 0) usedNanos += nanos;
    }

    /** {@code startNanos}（{@code System.nanoTime()} 相当）から今までの時間を計上し、その値を返す。 */
    public long consumeSince(long startNanos) {
        long elapsed = Math.max(0L, clock.getAsLong() - startNanos);
        consume(elapsed);
        return elapsed;
    }

    /** 計測の開始時刻。{@link #consumeSince(long)} と組で使う。 */
    public long now() {
        return clock.getAsLong();
    }

    /**
     * 予約した時刻と実際に動いた時刻の差を見て、予算を絞る。タスクの先頭で呼ぶ。
     *
     * @param expectedRunNanos {@link #expectedRunNanos(long)} で作った、動くはずの時刻
     * @return 観測した遅れ（ナノ秒。早く動いたら 0）
     */
    public long observeLag(long expectedRunNanos) {
        rollWindow();
        long lag = Math.max(0L, clock.getAsLong() - expectedRunNanos);
        FlowSettings s = settings.get();
        if (lag > s.lagThresholdNanos()) {
            lagInWindow = true;
            long reduced = (long) (limitNanos * s.budgetBackoffFactor());
            limitNanos = Math.max(s.minBudgetNanos(), Math.min(reduced, limitNanos));
        }
        return lag;
    }

    /** 現在の上限（ナノ秒）。計測ログと試験用。 */
    public long limitNanos() {
        rollWindow();
        return limitNanos;
    }

    private void rollWindow() {
        FlowSettings s = settings.get();
        long max = s.maxBudgetNanos();
        long now = clock.getAsLong();
        if (!started) {
            started = true;
            windowStart = now;
            usedNanos = 0;
            limitNanos = max;
            lagInWindow = false;
            return;
        }
        if (now - windowStart >= FlowSettings.TICK_NANOS) {
            // 窓が切り替わる。遅れの無かった窓なら上限を少し戻す。
            if (!lagInWindow && limitNanos < max) {
                limitNanos = Math.min(max, limitNanos + s.recoverNanos());
            }
            windowStart = now;
            usedNanos = 0;
            lagInWindow = false;
        }
        // 設定の再読み込みで最大値が下がった場合に追従する。
        if (limitNanos > max) limitNanos = max;
        long min = s.minBudgetNanos();
        if (limitNanos < min) limitNanos = min;
    }
}
