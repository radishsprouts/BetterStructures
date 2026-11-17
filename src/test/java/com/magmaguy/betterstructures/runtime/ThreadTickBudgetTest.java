package com.magmaguy.betterstructures.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 50ms 窓の予算。時計を差し替えて、窓の切り替え・予算超過・遅れによる絞りと回復（AIMD）を確かめる。
 */
final class ThreadTickBudgetTest {
    private static final long MS = 1_000_000L;

    private final long[] now = {0L};
    private FlowSettings settings = FlowSettings.defaults();

    private ThreadTickBudget budget() {
        return new ThreadTickBudget(() -> now[0], () -> settings);
    }

    @Test
    void 既定の上限は50msに割合を掛けた値になる() {
        ThreadTickBudget budget = budget();
        assertEquals(10 * MS, budget.limitNanos());
        assertEquals(10 * MS, budget.remainingNanos());
        assertTrue(budget.hasBudget());
    }

    @Test
    void 上限を使い切ると窓が切り替わるまで予算がない() {
        ThreadTickBudget budget = budget();
        budget.consume(10 * MS);
        assertFalse(budget.hasBudget());
        assertEquals(0L, budget.remainingNanos());

        now[0] = 49 * MS;
        assertFalse(budget.hasBudget(), "49ms 時点はまだ同じ窓");

        now[0] = 50 * MS;
        assertTrue(budget.hasBudget(), "50ms で窓が切り替わり、使用量が 0 に戻る");
        assertEquals(10 * MS, budget.remainingNanos());
    }

    @Test
    void 超過した分も窓の中では計上され残りは0を下回らない() {
        ThreadTickBudget budget = budget();
        budget.consume(25 * MS);
        assertEquals(0L, budget.remainingNanos());
        assertFalse(budget.hasBudget());
    }

    @Test
    void consumeSinceは開始時刻からの経過を計上する() {
        ThreadTickBudget budget = budget();
        long start = budget.now();
        now[0] = 4 * MS;
        assertEquals(4 * MS, budget.consumeSince(start));
        assertEquals(6 * MS, budget.remainingNanos());
    }

    @Test
    void 閾値を超える遅れで予算が半分になり下限で止まる() {
        ThreadTickBudget budget = budget();
        now[0] = 200 * MS;
        assertEquals(200 * MS, budget.observeLag(0L));
        assertEquals(5 * MS, budget.limitNanos());

        budget.observeLag(0L);
        assertEquals(2_500_000L, budget.limitNanos());
        for (int i = 0; i < 10; i++) budget.observeLag(0L);
        assertEquals(settings.minBudgetNanos(), budget.limitNanos(), "下限 0.5ms より下がらない");
    }

    @Test
    void 閾値以下の遅れでは絞らない() {
        ThreadTickBudget budget = budget();
        now[0] = 100 * MS;
        assertEquals(100 * MS, budget.observeLag(0L));
        assertEquals(10 * MS, budget.limitNanos(), "ちょうど 100ms は遅れとみなさない");
        assertEquals(0L, budget.observeLag(now[0] + 5 * MS), "早く動いた場合の遅れは 0");
    }

    @Test
    void 遅れの無い窓が続くと0_25msずつ元の上限まで戻る() {
        ThreadTickBudget budget = budget();
        now[0] = 200 * MS;
        budget.observeLag(0L);
        assertEquals(5 * MS, budget.limitNanos());

        // 遅れがあった窓が終わる時は戻さない。
        now[0] += 50 * MS;
        assertEquals(5 * MS, budget.limitNanos());
        // 以後、遅れの無い窓ごとに 0.25ms。
        now[0] += 50 * MS;
        assertEquals(5 * MS + 250_000L, budget.limitNanos());
        now[0] += 50 * MS;
        assertEquals(5 * MS + 500_000L, budget.limitNanos());

        for (int i = 0; i < 100; i++) {
            now[0] += 50 * MS;
            budget.limitNanos();
        }
        assertEquals(10 * MS, budget.limitNanos(), "元の上限を超えて戻らない");
    }

    @Test
    void 設定の割合を下げると上限が追従する() {
        ThreadTickBudget budget = budget();
        assertEquals(10 * MS, budget.limitNanos());
        settings = new FlowSettings(4, 2, 1, 8, 8, 0.1, 0.5, 0.25, 0.5, 100.0, 4, false, 60);
        assertEquals(5 * MS, budget.limitNanos());
    }

    @Test
    void 範囲外の割合は0_01から1に丸める() {
        assertEquals(0.01, new FlowSettings(4, 2, 1, 8, 8, 0.0, 0.5, 0.25, 0.5, 100.0, 4, false, 60).pasteTickFraction());
        assertEquals(1.0, new FlowSettings(4, 2, 1, 8, 8, 5.0, 0.5, 0.25, 0.5, 100.0, 4, false, 60).pasteTickFraction());
        assertEquals(0.2, new FlowSettings(4, 2, 1, 8, 8, Double.NaN, 0.5, 0.25, 0.5, 100.0, 4, false, 60).pasteTickFraction());
    }
}
