package com.magmaguy.betterstructures.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 専用スレッドプール: スレッド数・優先度・デーモン・結果と例外の受け渡し・停止後の拒否。 */
final class WorkerPoolTest {

    @Test
    void 既定のスレッド数はコア数の4分の1で最低1() {
        int expected = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        assertEquals(expected, WorkerPool.defaultThreadCount());
        assertTrue(WorkerPool.defaultThreadCount() >= 1);
    }

    @Test
    void スレッドは最低優先度のデーモンで名前が付く() throws Exception {
        WorkerPool pool = new WorkerPool(2);
        try {
            AtomicReference<Thread> seen = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            pool.execute(() -> {
                seen.set(Thread.currentThread());
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            Thread thread = seen.get();
            assertTrue(thread.isDaemon());
            assertEquals(Thread.MIN_PRIORITY, thread.getPriority());
            assertTrue(thread.getName().startsWith("BetterStructures-Worker-"));
            assertEquals(2, pool.threadCount());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void submitは結果を返し例外はfutureに入る() throws Exception {
        WorkerPool pool = new WorkerPool(1);
        try {
            assertEquals(42, pool.submit(() -> 42).get(5, TimeUnit.SECONDS));

            CompletableFuture<Integer> failing = pool.submit(() -> {
                throw new IllegalArgumentException("採点に失敗");
            });
            ExecutionException error = assertThrows(ExecutionException.class, () -> failing.get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IllegalArgumentException);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void 仕事が例外を投げてもワーカーは生き続ける() throws Exception {
        WorkerPool pool = new WorkerPool(1);
        try {
            pool.execute(() -> {
                throw new IllegalStateException("失敗する仕事");
            });
            assertEquals("ok", pool.submit(() -> "ok").get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void 停止後の依頼は例外で知らせる() {
        WorkerPool pool = new WorkerPool(1);
        pool.shutdown();
        assertTrue(pool.isShutdown());
        assertThrows(IllegalStateException.class, () -> pool.execute(() -> { }));
        assertTrue(pool.submit(() -> 1).isCompletedExceptionally());
    }

    @Test
    void 共有の本体は停止後に作り直される() {
        WorkerPool first = WorkerPool.instance();
        assertFalse(first.isShutdown());
        WorkerPool.shutdownInstance();
        assertTrue(first.isShutdown());
        WorkerPool second = WorkerPool.instance();
        try {
            assertFalse(second.isShutdown());
        } finally {
            WorkerPool.shutdownInstance();
        }
    }
}
