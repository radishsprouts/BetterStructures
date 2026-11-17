package com.magmaguy.betterstructures;

import com.magmaguy.betterstructures.paste.PasteEngine;
import com.magmaguy.betterstructures.paste.PasteRequest;
import com.magmaguy.betterstructures.runtime.GenerationGovernor;
import com.magmaguy.betterstructures.runtime.Services;
import com.magmaguy.betterstructures.runtime.WorkerPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldEdit / FAWE がクラスパスに無くても、停止処理（実行時基盤の後片付け）と計測ログの組み立てが
 * NoClassDefFoundError を出さず、貼り付けエンジンの停止・待ち行列の破棄・ワーカーの停止まで行えること。
 */
class RuntimeShutdownNoWorldEditTest {

    @AfterEach
    void cleanUp() {
        Services.clear();
        GenerationGovernor.shutdown();
        WorkerPool.shutdownInstance();
    }

    /** 停止の呼び出しだけを数える貼り付けエンジン。 */
    private static class CountingEngine implements PasteEngine {
        final AtomicInteger shutdowns = new AtomicInteger();

        @Override
        public PasteHandle start(PasteRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int pendingCount() {
            return 3;
        }

        @Override
        public void shutdown() {
            shutdowns.incrementAndGet();
        }
    }

    @Test
    void shutdownStopsEngineClearsQueuesAndStopsWorkers() {
        CountingEngine engine = new CountingEngine();
        Services.setPasteEngine(engine);
        GenerationGovernor governor = GenerationGovernor.instance();
        AtomicInteger started = new AtomicInteger();
        // タイマーは動いていない（サーバー無し）ので、待ち行列に残ったまま。
        governor.submit(new GenerationGovernor.Work(GenerationGovernor.Lane.SCAN, UUID.randomUUID(), "k", null,
                ticket -> started.incrementAndGet()));
        WorkerPool pool = WorkerPool.instance();
        assertEquals(1, governor.waiting(GenerationGovernor.Lane.SCAN));

        BetterStructures.shutdownRuntimeServices();

        assertEquals(1, engine.shutdowns.get(), "貼り付けエンジンの停止処理が1回呼ばれる");
        assertEquals(0, governor.waiting(GenerationGovernor.Lane.SCAN), "待ち行列は空になる");
        assertEquals(0, started.get(), "停止後に仕事は始まらない");
        assertTrue(pool.isShutdown(), "ワーカーは止まる");
        assertNull(Services.pasteEngineOrNull(), "受け渡し口は空になる");
    }

    @Test
    void shutdownWorksWithoutEngine() {
        BetterStructures.shutdownRuntimeServices();
        assertNull(Services.pasteEngineOrNull());
    }

    @Test
    void shutdownContinuesWhenEngineShutdownFails() {
        Services.setPasteEngine(new CountingEngine() {
            @Override
            public void shutdown() {
                throw new IllegalStateException("fixture");
            }
        });
        WorkerPool pool = WorkerPool.instance();

        BetterStructures.shutdownRuntimeServices();

        assertTrue(pool.isShutdown(), "エンジンの停止が失敗しても残りの後片付けは続く");
        assertNull(Services.pasteEngineOrNull());
    }

    @Test
    void metricsLineReportsPendingCountOrDash() {
        String without = BetterStructures.metricsLine();
        assertTrue(without.contains("貼り付け中=-"), without);

        Services.setPasteEngine(new CountingEngine());
        String with = BetterStructures.metricsLine();
        assertTrue(with.contains("貼り付け中=3"), with);
        assertFalse(with.isBlank());
    }
}
