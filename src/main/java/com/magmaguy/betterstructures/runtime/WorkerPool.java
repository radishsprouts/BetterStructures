package com.magmaguy.betterstructures.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 重い計算（採点・貼り付けの段取り・WFC の配置計算）を行う専用スレッドプール。
 *
 * <p>固定のスレッド数 {@code max(1, コア数/4)}、優先度は最低、デーモン。Folia の AsyncScheduler は
 * スレッド数に上限がなく、重い計算でリージョンスレッドの CPU を奪うので、重い計算には使わない。
 * 待ち行列は無制限だが、上流の流量制御（GenerationGovernor の枠）が件数を抑える。
 *
 * <p>呼び出すスレッド: どのスレッドからでも依頼できる（依頼は待たずに返る）。ここで動く処理は
 * ワールドに触れてはいけない（スナップショットや純粋なデータだけを扱う）。
 */
public final class WorkerPool {

    private static volatile WorkerPool instance;

    private final ThreadPoolExecutor executor;

    /** 既定のスレッド数: コア数の 1/4、最低 1。 */
    public static int defaultThreadCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    }

    public WorkerPool(int threads) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "BetterStructures-Worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.setUncaughtExceptionHandler((t, e) -> RuntimeLog.warn("ワーカースレッドで例外: " + e));
            return thread;
        };
        int n = Math.max(1, threads);
        this.executor = new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
    }

    /** プラグイン全体で共有する本体。止めた後に呼べば作り直す。 */
    public static WorkerPool instance() {
        WorkerPool local = instance;
        if (local == null || local.isShutdown()) {
            synchronized (WorkerPool.class) {
                local = instance;
                if (local == null || local.isShutdown()) {
                    local = new WorkerPool(defaultThreadCount());
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 停止時に呼ぶ。実行中の仕事も含めて中断する。 */
    public static void shutdownInstance() {
        WorkerPool local = instance;
        instance = null;
        if (local != null) local.shutdown();
    }

    /** 結果を返さない仕事を依頼する。止まった後の依頼は捨てずに例外で知らせる。 */
    public void execute(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    RuntimeLog.warn("ワーカースレッドの仕事が失敗しました: " + t);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("WorkerPool は停止しています", e);
        }
    }

    /** 結果を返す仕事を依頼する。例外は future に入る。待たずに {@code thenAccept} 等でつなぐこと。 */
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public int threadCount() {
        return executor.getCorePoolSize();
    }

    /** 待っている仕事の数。 */
    public int queuedCount() {
        return executor.getQueue().size();
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
