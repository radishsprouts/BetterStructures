package com.magmaguy.betterstructures.util;

import com.magmaguy.betterstructures.runtime.ChunkKeys;
import com.magmaguy.betterstructures.runtime.FlowSettings;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 事前生成の流量・一時停止の判定とリングの列挙（Bukkit のサーバー無しで試験できる部分）。 */
class ChunkPregeneratorPacingTest {

    private static final long MS = 1_000_000L;

    // ---- リングの列挙 ----

    @Test
    void squareRingsCoverTheSquareExactlyOnce() {
        int radius = 6;
        Set<Long> seen = new HashSet<>();
        for (int r = 0; r <= radius; r++) {
            for (long key : ChunkPregenerator.ringKeys("SQUARE", 3, -2, r)) {
                assertTrue(seen.add(key), "重複: " + ChunkKeys.x(key) + "," + ChunkKeys.z(key));
                assertEquals(r, Math.max(Math.abs(ChunkKeys.x(key) - 3), Math.abs(ChunkKeys.z(key) + 2)));
            }
        }
        assertEquals((2 * radius + 1) * (2 * radius + 1), seen.size());
    }

    @Test
    void circleRingsMatchBruteForceMembership() {
        int radius = 9;
        Set<Long> seen = new HashSet<>();
        for (int r = 0; r <= radius; r++) {
            Set<Long> ring = new HashSet<>();
            for (long key : ChunkPregenerator.ringKeys("CIRCLE", -5, 4, r)) {
                assertTrue(ring.add(key), "リング内で重複");
                assertTrue(seen.add(key), "リング間で重複");
            }
            Set<Long> expected = new HashSet<>();
            for (int dx = -(radius + 1); dx <= radius + 1; dx++) {
                for (int dz = -(radius + 1); dz <= radius + 1; dz++) {
                    long d2 = (long) dx * dx + (long) dz * dz;
                    if (d2 >= (long) r * r && d2 < (long) (r + 1) * (r + 1)) {
                        expected.add(ChunkKeys.key(-5 + dx, 4 + dz));
                    }
                }
            }
            assertEquals(expected, ring, "半径 " + r);
        }
    }

    @Test
    void invalidShapeYieldsNothing() {
        assertEquals(0, ChunkPregenerator.ringKeys("TRIANGLE", 0, 0, 3).length);
    }

    // ---- 同時に飛ばす数 ----

    private static FlowSettings withLimits(int load, int perWorld, double pasteFraction) {
        FlowSettings d = FlowSettings.defaults();
        return new FlowSettings(d.scanSlots(), d.pasteSlots(), d.wfcSlots(), load, perWorld, pasteFraction,
                d.budgetMinMillis(), d.budgetRecoverMillis(), d.budgetBackoffFactor(), d.lagThresholdMillis(),
                d.wfcWindowChunks(), d.metricsEnabled(), d.metricsIntervalSeconds());
    }

    @Test
    void productionSettingsGiveHalfTheChunkLoadLimit() {
        // 本番: 事前生成 0.1、貼り付け 0.2、読み込み上限 8
        assertEquals(4, ChunkPregenerator.inFlightCap(withLimits(8, 8, 0.2), 0.1));
    }

    @Test
    void capStaysWithinOneAndTheHardLimits() {
        assertEquals(1, ChunkPregenerator.inFlightCap(withLimits(8, 8, 1.0), 0.01));
        assertEquals(8, ChunkPregenerator.inFlightCap(withLimits(8, 8, 0.01), 1.0));
        assertEquals(3, ChunkPregenerator.inFlightCap(withLimits(8, 3, 0.2), 0.2));
        assertEquals(1, ChunkPregenerator.inFlightCap(withLimits(1, 1, 0.2), 0.5));
    }

    // ---- 一時停止の判定 ----

    private static ChunkPregenerator.Pacer.Reason eval(ChunkPregenerator.Pacer pacer, long tick, long lagMs, int backlog, double tps) {
        return pacer.evaluate(tick, lagMs * MS, 100 * MS, backlog, tps, 12.0, 14.0);
    }

    @Test
    void runsWhenEverythingIsHealthy() {
        ChunkPregenerator.Pacer pacer = new ChunkPregenerator.Pacer();
        assertEquals(ChunkPregenerator.Pacer.Reason.NONE, eval(pacer, 1, 0, 0, 20.0));
    }

    @Test
    void lagPausesForCooldownThenResumes() {
        ChunkPregenerator.Pacer pacer = new ChunkPregenerator.Pacer();
        assertEquals(ChunkPregenerator.Pacer.Reason.LAG, eval(pacer, 10, 150, 0, Double.NaN));
        assertEquals(ChunkPregenerator.Pacer.Reason.LAG, eval(pacer, 10 + ChunkPregenerator.LAG_COOLDOWN_TICKS - 1, 0, 0, Double.NaN));
        assertEquals(ChunkPregenerator.Pacer.Reason.NONE, eval(pacer, 10 + ChunkPregenerator.LAG_COOLDOWN_TICKS, 0, 0, Double.NaN));
    }

    @Test
    void backlogPausesWithHysteresis() {
        ChunkPregenerator.Pacer pacer = new ChunkPregenerator.Pacer();
        assertEquals(ChunkPregenerator.Pacer.Reason.NONE, eval(pacer, 1, 0, ChunkPregenerator.BACKLOG_PAUSE, Double.NaN));
        assertEquals(ChunkPregenerator.Pacer.Reason.BACKLOG, eval(pacer, 2, 0, ChunkPregenerator.BACKLOG_PAUSE + 1, Double.NaN));
        // しきい値を下回っても、再開の水準まで減るまで止めたまま
        assertEquals(ChunkPregenerator.Pacer.Reason.BACKLOG, eval(pacer, 3, 0, ChunkPregenerator.BACKLOG_RESUME + 1, Double.NaN));
        assertEquals(ChunkPregenerator.Pacer.Reason.NONE, eval(pacer, 4, 0, ChunkPregenerator.BACKLOG_RESUME, Double.NaN));
    }

    @Test
    void tpsPausesAndResumesWithConfiguredThresholds() {
        ChunkPregenerator.Pacer pacer = new ChunkPregenerator.Pacer();
        assertEquals(ChunkPregenerator.Pacer.Reason.TPS, eval(pacer, 1, 0, 0, 11.9));
        assertEquals(ChunkPregenerator.Pacer.Reason.TPS, eval(pacer, 2, 0, 0, 13.9));
        assertEquals(ChunkPregenerator.Pacer.Reason.NONE, eval(pacer, 3, 0, 0, 14.0));
    }

    @Test
    void unknownTpsNeverPauses() {
        ChunkPregenerator.Pacer pacer = new ChunkPregenerator.Pacer();
        assertEquals(ChunkPregenerator.Pacer.Reason.TPS, eval(pacer, 1, 0, 0, 5.0));
        assertEquals(ChunkPregenerator.Pacer.Reason.NONE, eval(pacer, 2, 0, 0, Double.NaN));
    }
}
