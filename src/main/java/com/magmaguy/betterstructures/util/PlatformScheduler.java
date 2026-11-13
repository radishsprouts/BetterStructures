package com.magmaguy.betterstructures.util;

import com.magmaguy.betterstructures.MetadataHandler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Provides a unified abstraction layer for scheduling tasks on both Folia (region-based)
 * and Paper/Spigot (single main thread) environments.
 */
public final class PlatformScheduler {

    private static final boolean IS_FOLIA;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException ignored) {
            folia = false;
        }
        IS_FOLIA = folia;
    }

    private PlatformScheduler() {
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

    // --- Global Tasks ---

    public static TaskHandle runGlobal(Runnable runnable) {
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getGlobalRegionScheduler().run(plugin(), scheduledTask -> runnable.run());
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTask(plugin(), runnable);
        return task::cancel;
    }

    public static TaskHandle runGlobalLater(Runnable runnable, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getGlobalRegionScheduler().runDelayed(plugin(), scheduledTask -> runnable.run(), delay);
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin(), runnable, delay);
        return task::cancel;
    }

    public static TaskHandle runGlobalTimer(Runnable runnable, long delayTicks, long periodTicks) {
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin(), scheduledTask -> runnable.run(), delay, period);
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin(), runnable, delay, period);
        return task::cancel;
    }

    // --- Region Tasks (by Location) ---

    public static TaskHandle runRegion(Location location, Runnable runnable) {
        if (location == null || location.getWorld() == null) {
            return runGlobal(runnable);
        }
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getRegionScheduler().run(plugin(), location, scheduledTask -> runnable.run());
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTask(plugin(), runnable);
        return task::cancel;
    }

    public static TaskHandle runRegionLater(Location location, Runnable runnable, long delayTicks) {
        if (location == null || location.getWorld() == null) {
            return runGlobalLater(runnable, delayTicks);
        }
        long delay = Math.max(1L, delayTicks);
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getRegionScheduler().runDelayed(plugin(), location, scheduledTask -> runnable.run(), delay);
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin(), runnable, delay);
        return task::cancel;
    }

    public static TaskHandle runRegionTimer(Location location, Runnable runnable, long delayTicks, long periodTicks) {
        if (location == null || location.getWorld() == null) {
            return runGlobalTimer(runnable, delayTicks, periodTicks);
        }
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getRegionScheduler().runAtFixedRate(plugin(), location, scheduledTask -> runnable.run(), delay, period);
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin(), runnable, delay, period);
        return task::cancel;
    }

    // --- Region Tasks (by World and Chunk Coordinates) ---

    public static TaskHandle runRegion(World world, int chunkX, int chunkZ, Runnable runnable) {
        if (world == null) {
            return runGlobal(runnable);
        }
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getRegionScheduler().run(plugin(), world, chunkX, chunkZ, scheduledTask -> runnable.run());
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTask(plugin(), runnable);
        return task::cancel;
    }

    public static TaskHandle runRegionLater(World world, int chunkX, int chunkZ, Runnable runnable, long delayTicks) {
        if (world == null) {
            return runGlobalLater(runnable, delayTicks);
        }
        long delay = Math.max(1L, delayTicks);
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getRegionScheduler().runDelayed(plugin(), world, chunkX, chunkZ, scheduledTask -> runnable.run(), delay);
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin(), runnable, delay);
        return task::cancel;
    }

    public static TaskHandle runRegionTimer(World world, int chunkX, int chunkZ, Runnable runnable, long delayTicks, long periodTicks) {
        if (world == null) {
            return runGlobalTimer(runnable, delayTicks, periodTicks);
        }
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getRegionScheduler().runAtFixedRate(plugin(), world, chunkX, chunkZ, scheduledTask -> runnable.run(), delay, period);
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin(), runnable, delay, period);
        return task::cancel;
    }

    // --- Entity Tasks ---

    public static TaskHandle runEntity(Entity entity, Runnable runnable) {
        if (entity == null || !entity.isValid()) {
            return () -> {};
        }
        if (IS_FOLIA) {
            ScheduledTask task = entity.getScheduler().run(plugin(), scheduledTask -> runnable.run(), null);
            return task != null ? task::cancel : () -> {};
        }
        BukkitTask task = Bukkit.getScheduler().runTask(plugin(), runnable);
        return task::cancel;
    }

    public static TaskHandle runEntityLater(Entity entity, Runnable runnable, long delayTicks) {
        if (entity == null || !entity.isValid()) {
            return () -> {};
        }
        long delay = Math.max(1L, delayTicks);
        if (IS_FOLIA) {
            ScheduledTask task = entity.getScheduler().runDelayed(plugin(), scheduledTask -> runnable.run(), null, delay);
            return task != null ? task::cancel : () -> {};
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin(), runnable, delay);
        return task::cancel;
    }

    // --- Async Tasks ---

    public static TaskHandle runAsync(Runnable runnable) {
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getAsyncScheduler().runNow(plugin(), scheduledTask -> runnable.run());
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskAsynchronously(plugin(), runnable);
        return task::cancel;
    }

    public static TaskHandle runAsyncLater(Runnable runnable, long delayTicks) {
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getAsyncScheduler().runDelayed(plugin(), scheduledTask -> runnable.run(), delayTicks * 50L, TimeUnit.MILLISECONDS);
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin(), runnable, delayTicks);
        return task::cancel;
    }

    public static TaskHandle runAsyncTimer(Runnable runnable, long delayTicks, long periodTicks) {
        if (IS_FOLIA) {
            ScheduledTask task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin(), scheduledTask -> runnable.run(),
                    delayTicks * 50L, periodTicks * 50L, TimeUnit.MILLISECONDS);
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin(), runnable, delayTicks, periodTicks);
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

    public static void cancelAllTasks() {
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin());
            Bukkit.getAsyncScheduler().cancelTasks(plugin());
        } else {
            Bukkit.getScheduler().cancelTasks(plugin());
        }
    }
}
