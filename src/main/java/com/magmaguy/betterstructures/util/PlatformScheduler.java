package com.magmaguy.betterstructures.util;

import com.magmaguy.betterstructures.MetadataHandler;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Provides a unified abstraction layer for scheduling tasks on both Folia (region-based)
 * and Paper/Spigot (single main thread) environments.
 *
 * <p>全タスクは「ライフサイクル世代」に紐づく。{@link #cancelAllTasks()}（停止時）で世代が進み、
 * 古い世代のタスクは、取り消しをすり抜けて走り出しても先頭で何もせず終わる。
 * Folia ではリージョンのタスクも追跡して、停止時にまとめて取り消す。
 *
 * <p>{@code /bs reload} をまたいで走り続けるべき仕事（走っている貼り付け。04章 G5）を止めないよう、
 * 再読み込み用の世代 {@link #reloadGeneration()} は別に持つ。再読み込みで古い内容を使い続けては
 * いけない呼び出し側だけが、自分で取得した世代と見比べる（{@link #isCurrentReloadGeneration(long)}）。
 */
public final class PlatformScheduler {

    private static final boolean IS_FOLIA = detectFolia();

    /** 停止（onDisable）のたびに進む。全タスクが予約時の値を持ち、先頭で見比べる。 */
    private static final AtomicLong LIFECYCLE_GENERATION = new AtomicLong();
    /** {@code /bs reload} のたびに進む。見比べるかどうかは呼び出し側が決める。 */
    private static final AtomicLong RELOAD_GENERATION = new AtomicLong();

    /** 取り消しに備えて追跡する Folia のタスク。終わったものは自分で外れ、溜まった分は間引く。 */
    private static final Set<ScheduledTask> TRACKED = ConcurrentHashMap.newKeySet();
    private static final int PURGE_INTERVAL = 1024;

    private PlatformScheduler() {
    }

    /**
     * 公開 API（{@link ServerBuildInfo}）で Folia かを判定する。サーバーのブランドが
     * {@code papermc:folia} と互換なら Folia。{@code ServerBuildInfo} が無い古いサーバーや
     * サーバーが無いテストでは、{@link Server#getName()} の名前で見る。
     */
    private static boolean detectFolia() {
        try {
            if (ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"))) {
                return true;
            }
        } catch (Throwable ignored) {
            // ServerBuildInfo が使えない環境。名前の判定へ進む。
        }
        try {
            Server server = Bukkit.getServer();
            return server != null && "Folia".equalsIgnoreCase(server.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    @FunctionalInterface
    public interface TaskHandle {
        void cancel();
    }

    private static Plugin plugin() {
        return MetadataHandler.PLUGIN;
    }

    // --- Generation ---

    /** 停止のたびに進むライフサイクル世代。 */
    public static long lifecycleGeneration() {
        return LIFECYCLE_GENERATION.get();
    }

    /** {@code /bs reload} のたびに進む世代。 */
    public static long reloadGeneration() {
        return RELOAD_GENERATION.get();
    }

    /** 再読み込みの開始で呼ぶ。以後、古い世代を持つ仕事は {@link #isCurrentReloadGeneration} が false を返す。 */
    public static long advanceReloadGeneration() {
        return RELOAD_GENERATION.incrementAndGet();
    }

    public static boolean isCurrentReloadGeneration(long generation) {
        return RELOAD_GENERATION.get() == generation;
    }

    /** 予約時のライフサイクル世代を束ねる。世代が進んでいたら何もせず終わる。 */
    private static Runnable bind(Runnable runnable) {
        long generation = LIFECYCLE_GENERATION.get();
        return () -> {
            if (LIFECYCLE_GENERATION.get() != generation) return;
            runnable.run();
        };
    }

    /** Folia のタスクを追跡して、取り消しの取っ手を返す。 */
    private static TaskHandle track(ScheduledTask task) {
        if (task == null) return () -> {
        };
        if (isFinished(task)) return task::cancel;
        TRACKED.add(task);
        if (TRACKED.size() % PURGE_INTERVAL == 0) {
            TRACKED.removeIf(PlatformScheduler::isFinished);
        }
        return task::cancel;
    }

    /** 一回きりのタスク。終わったら追跡から外れる（溜め込まない）。 */
    private static Consumer<ScheduledTask> once(Runnable runnable) {
        return task -> {
            try {
                runnable.run();
            } finally {
                TRACKED.remove(task);
            }
        };
    }

    private static boolean isFinished(ScheduledTask task) {
        return task.isCancelled() || task.getExecutionState() == ScheduledTask.ExecutionState.FINISHED;
    }

    // --- Global Tasks ---

    public static TaskHandle runGlobal(Runnable runnable) {
        Runnable bound = bind(runnable);
        if (IS_FOLIA) {
            return track(Bukkit.getGlobalRegionScheduler().run(plugin(), once(bound)));
        }
        BukkitTask task = Bukkit.getScheduler().runTask(plugin(), bound);
        return task::cancel;
    }

    public static TaskHandle runGlobalLater(Runnable runnable, long delayTicks) {
        Runnable bound = bind(runnable);
        long delay = Math.max(1L, delayTicks);
        if (IS_FOLIA) {
            return track(Bukkit.getGlobalRegionScheduler().runDelayed(plugin(), once(bound), delay));
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin(), bound, delay);
        return task::cancel;
    }

    public static TaskHandle runGlobalTimer(Runnable runnable, long delayTicks, long periodTicks) {
        Runnable bound = bind(runnable);
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (IS_FOLIA) {
            return track(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin(), scheduledTask -> bound.run(), delay, period));
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin(), bound, delay, period);
        return task::cancel;
    }

    // --- Region Tasks (by Location) ---

    public static TaskHandle runRegion(Location location, Runnable runnable) {
        if (location == null || location.getWorld() == null) {
            return runGlobal(runnable);
        }
        Runnable bound = bind(runnable);
        if (IS_FOLIA) {
            return track(Bukkit.getRegionScheduler().run(plugin(), location, once(bound)));
        }
        BukkitTask task = Bukkit.getScheduler().runTask(plugin(), bound);
        return task::cancel;
    }

    public static TaskHandle runRegionLater(Location location, Runnable runnable, long delayTicks) {
        if (location == null || location.getWorld() == null) {
            return runGlobalLater(runnable, delayTicks);
        }
        Runnable bound = bind(runnable);
        long delay = Math.max(1L, delayTicks);
        if (IS_FOLIA) {
            return track(Bukkit.getRegionScheduler().runDelayed(plugin(), location, once(bound), delay));
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin(), bound, delay);
        return task::cancel;
    }

    public static TaskHandle runRegionTimer(Location location, Runnable runnable, long delayTicks, long periodTicks) {
        if (location == null || location.getWorld() == null) {
            return runGlobalTimer(runnable, delayTicks, periodTicks);
        }
        Runnable bound = bind(runnable);
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (IS_FOLIA) {
            return track(Bukkit.getRegionScheduler().runAtFixedRate(plugin(), location, scheduledTask -> bound.run(), delay, period));
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin(), bound, delay, period);
        return task::cancel;
    }

    // --- Region Tasks (by World and Chunk Coordinates) ---

    public static TaskHandle runRegion(World world, int chunkX, int chunkZ, Runnable runnable) {
        if (world == null) {
            return runGlobal(runnable);
        }
        Runnable bound = bind(runnable);
        if (IS_FOLIA) {
            return track(Bukkit.getRegionScheduler().run(plugin(), world, chunkX, chunkZ, once(bound)));
        }
        BukkitTask task = Bukkit.getScheduler().runTask(plugin(), bound);
        return task::cancel;
    }

    public static TaskHandle runRegionLater(World world, int chunkX, int chunkZ, Runnable runnable, long delayTicks) {
        if (world == null) {
            return runGlobalLater(runnable, delayTicks);
        }
        Runnable bound = bind(runnable);
        long delay = Math.max(1L, delayTicks);
        if (IS_FOLIA) {
            return track(Bukkit.getRegionScheduler().runDelayed(plugin(), world, chunkX, chunkZ, once(bound), delay));
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin(), bound, delay);
        return task::cancel;
    }

    public static TaskHandle runRegionTimer(World world, int chunkX, int chunkZ, Runnable runnable, long delayTicks, long periodTicks) {
        if (world == null) {
            return runGlobalTimer(runnable, delayTicks, periodTicks);
        }
        Runnable bound = bind(runnable);
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (IS_FOLIA) {
            return track(Bukkit.getRegionScheduler().runAtFixedRate(plugin(), world, chunkX, chunkZ, scheduledTask -> bound.run(), delay, period));
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin(), bound, delay, period);
        return task::cancel;
    }

    // --- Entity Tasks ---

    public static TaskHandle runEntity(Entity entity, Runnable runnable) {
        if (entity == null || !entity.isValid()) {
            return () -> {};
        }
        Runnable bound = bind(runnable);
        if (IS_FOLIA) {
            return track(entity.getScheduler().run(plugin(), once(bound), null));
        }
        BukkitTask task = Bukkit.getScheduler().runTask(plugin(), bound);
        return task::cancel;
    }

    public static TaskHandle runEntityLater(Entity entity, Runnable runnable, long delayTicks) {
        if (entity == null || !entity.isValid()) {
            return () -> {};
        }
        Runnable bound = bind(runnable);
        long delay = Math.max(1L, delayTicks);
        if (IS_FOLIA) {
            return track(entity.getScheduler().runDelayed(plugin(), once(bound), null, delay));
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin(), bound, delay);
        return task::cancel;
    }

    // --- Async Tasks ---

    public static TaskHandle runAsync(Runnable runnable) {
        Runnable bound = bind(runnable);
        if (IS_FOLIA) {
            return track(Bukkit.getAsyncScheduler().runNow(plugin(), once(bound)));
        }
        BukkitTask task = Bukkit.getScheduler().runTaskAsynchronously(plugin(), bound);
        return task::cancel;
    }

    public static TaskHandle runAsyncLater(Runnable runnable, long delayTicks) {
        Runnable bound = bind(runnable);
        if (IS_FOLIA) {
            return track(Bukkit.getAsyncScheduler().runDelayed(plugin(), once(bound), delayTicks * 50L, TimeUnit.MILLISECONDS));
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin(), bound, delayTicks);
        return task::cancel;
    }

    public static TaskHandle runAsyncTimer(Runnable runnable, long delayTicks, long periodTicks) {
        Runnable bound = bind(runnable);
        if (IS_FOLIA) {
            return track(Bukkit.getAsyncScheduler().runAtFixedRate(plugin(), scheduledTask -> bound.run(),
                    delayTicks * 50L, periodTicks * 50L, TimeUnit.MILLISECONDS));
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin(), bound, delayTicks, periodTicks);
        return task::cancel;
    }

    // --- Thread Checks ---

    public static boolean isOwnedByCurrentRegion(Location location) {
        if (IS_FOLIA) {
            return location != null && location.getWorld() != null && Bukkit.isOwnedByCurrentRegion(location);
        }
        return Bukkit.isPrimaryThread();
    }

    public static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
        if (IS_FOLIA) {
            return world != null && Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
        }
        return Bukkit.isPrimaryThread();
    }

    public static boolean isGlobalTickThread() {
        if (IS_FOLIA) {
            return Bukkit.isGlobalTickThread() || Bukkit.isPrimaryThread();
        }
        return Bukkit.isPrimaryThread();
    }

    // --- Task Cancellation ---

    /**
     * 停止時に呼ぶ。ライフサイクル世代を進め（走り出していた古いタスクは先頭で何もせず終わる）、
     * 追跡している Folia のリージョン・エンティティのタスクも含めて全部取り消す。
     */
    public static void cancelAllTasks() {
        LIFECYCLE_GENERATION.incrementAndGet();
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin());
            Bukkit.getAsyncScheduler().cancelTasks(plugin());
            for (ScheduledTask task : TRACKED) {
                task.cancel();
            }
            TRACKED.clear();
        } else {
            Bukkit.getScheduler().cancelTasks(plugin());
        }
    }
}
