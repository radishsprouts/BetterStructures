package com.magmaguy.betterstructures.runtime;

import com.magmaguy.betterstructures.runtime.GenerationGovernor.Lane;
import com.magmaguy.betterstructures.runtime.GenerationGovernor.Ticket;
import com.magmaguy.betterstructures.runtime.GenerationGovernor.Work;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流量制御: 枠の上限を超えない・何も捨てない・古い順・ワールド間で公平・重複を除く。
 */
final class GenerationGovernorTest {
    private static final UUID WORLD_A = new UUID(0L, 1L);
    private static final UUID WORLD_B = new UUID(0L, 2L);

    private final List<String> started = new ArrayList<>();
    private final List<Ticket> tickets = new ArrayList<>();

    private static FlowSettings slots(int scan, int paste, int wfc) {
        return new FlowSettings(scan, paste, wfc, 8, 8, 0.2, 0.5, 0.25, 0.5, 100.0, 4, false, 60);
    }

    private Work work(Lane lane, UUID world, Object key, String name) {
        return new Work(lane, world, key, name, ticket -> {
            started.add(name);
            tickets.add(ticket);
        });
    }

    @Test
    void 枠の数を超えて同時に始めない() {
        GenerationGovernor governor = new GenerationGovernor(() -> slots(2, 2, 1));
        for (int i = 1; i <= 5; i++) governor.submit(work(Lane.SCAN, WORLD_A, null, "s" + i));

        governor.tick();
        assertEquals(List.of("s1", "s2"), started);
        assertEquals(2, governor.running(Lane.SCAN));
        assertEquals(3, governor.waiting(Lane.SCAN));

        governor.tick();
        assertEquals(2, started.size(), "枠が埋まっている間は進まない");
    }

    @Test
    void 枠が空けば待っていた仕事が古い順に始まり何も捨てない() {
        GenerationGovernor governor = new GenerationGovernor(() -> slots(1, 1, 1));
        for (int i = 1; i <= 4; i++) governor.submit(work(Lane.SCAN, WORLD_A, null, "s" + i));

        for (int round = 0; round < 4; round++) {
            governor.tick();
            tickets.get(round).release();
        }
        governor.tick();

        assertEquals(List.of("s1", "s2", "s3", "s4"), started);
        assertEquals(0, governor.waiting(Lane.SCAN));
        assertEquals(0, governor.running(Lane.SCAN));
    }

    @Test
    void 枠は種類ごとに独立している() {
        GenerationGovernor governor = new GenerationGovernor(() -> slots(1, 1, 1));
        governor.submit(work(Lane.SCAN, WORLD_A, null, "scan"));
        governor.submit(work(Lane.PASTE, WORLD_A, null, "paste"));
        governor.submit(work(Lane.WFC, WORLD_A, null, "wfc"));
        governor.submit(work(Lane.SCAN, WORLD_A, null, "scan2"));

        governor.tick();
        assertEquals(List.of("scan", "paste", "wfc"), started);
        assertEquals(1, governor.waiting(Lane.SCAN));
    }

    @Test
    void ワールドを順番に回して公平に取り出す() {
        GenerationGovernor governor = new GenerationGovernor(() -> slots(1, 1, 1));
        governor.submit(work(Lane.SCAN, WORLD_A, null, "a1"));
        governor.submit(work(Lane.SCAN, WORLD_A, null, "a2"));
        governor.submit(work(Lane.SCAN, WORLD_A, null, "a3"));
        governor.submit(work(Lane.SCAN, WORLD_B, null, "b1"));

        for (int round = 0; round < 4; round++) {
            governor.tick();
            tickets.get(round).release();
        }

        assertEquals(List.of("a1", "b1", "a2", "a3"), started);
    }

    @Test
    void 同じ鍵は待ち中も実行中も二重に入れず終われば入れ直せる() {
        GenerationGovernor governor = new GenerationGovernor(() -> slots(1, 1, 1));
        assertTrue(governor.submit(work(Lane.SCAN, WORLD_A, "cell:3:4", "first")));
        assertFalse(governor.submit(work(Lane.SCAN, WORLD_A, "cell:3:4", "dup-waiting")));

        governor.tick();
        assertFalse(governor.submit(work(Lane.SCAN, WORLD_A, "cell:3:4", "dup-running")));
        assertTrue(governor.submit(work(Lane.SCAN, WORLD_B, "cell:3:4", "other-world")), "別ワールドは別の鍵");
        assertTrue(governor.submit(work(Lane.PASTE, WORLD_A, "cell:3:4", "other-lane")), "別の枠は別の鍵");

        tickets.get(0).release();
        assertTrue(governor.submit(work(Lane.SCAN, WORLD_A, "cell:3:4", "again")));
        assertEquals(List.of("first"), started.subList(0, 1));
    }

    @Test
    void releaseを二重に呼んでも枠は1回分しか戻らない() {
        GenerationGovernor governor = new GenerationGovernor(() -> slots(1, 1, 1));
        governor.submit(work(Lane.SCAN, WORLD_A, null, "s1"));
        governor.submit(work(Lane.SCAN, WORLD_A, null, "s2"));
        governor.tick();

        tickets.get(0).release();
        tickets.get(0).release();
        assertEquals(0, governor.running(Lane.SCAN));
        governor.tick();
        assertEquals(1, governor.running(Lane.SCAN), "二重に返しても s2 の1件しか始まらない");
    }

    @Test
    void 開始が例外で失敗しても枠は戻り次の仕事へ進む() {
        GenerationGovernor governor = new GenerationGovernor(() -> slots(1, 1, 1));
        governor.submit(new Work(Lane.SCAN, WORLD_A, null, null, ticket -> {
            throw new IllegalStateException("開始に失敗");
        }));
        governor.submit(work(Lane.SCAN, WORLD_A, null, "next"));

        governor.tick();
        assertEquals(List.of("next"), started);
    }

    @Test
    void 設定の枠の数の変更は次のtickから効く() {
        FlowSettings[] current = {slots(1, 1, 1)};
        GenerationGovernor governor = new GenerationGovernor(() -> current[0]);
        for (int i = 1; i <= 4; i++) governor.submit(work(Lane.SCAN, WORLD_A, null, "s" + i));

        governor.tick();
        assertEquals(1, started.size());
        current[0] = slots(3, 1, 1);
        governor.tick();
        assertEquals(3, started.size());
    }

    @Test
    void 待っている仕事の写しを取れてclearで空にできる() {
        GenerationGovernor governor = new GenerationGovernor(() -> slots(1, 1, 1));
        governor.submit(work(Lane.SCAN, WORLD_A, "k1", "s1"));
        governor.submit(work(Lane.SCAN, WORLD_A, "k2", "s2"));
        governor.submit(work(Lane.SCAN, WORLD_B, "k3", "s3"));
        governor.tick();

        assertEquals(2, governor.pendingSnapshot(Lane.SCAN).size());
        assertEquals(2, governor.clear());
        assertEquals(0, governor.waiting(Lane.SCAN));
        assertTrue(governor.submit(work(Lane.SCAN, WORLD_B, "k3", "s3-again")), "clear は重複除去の鍵も外す");
    }
}
