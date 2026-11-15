package com.magmaguy.betterstructures.listeners;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.buildingfitter.FitAnything;
import com.magmaguy.betterstructures.buildingfitter.util.TerrainReads;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Runs terrain scans of new structure grid chunks with bounded concurrency and minimal chunk ticket retention.
 */
final class TerrainScanQueue {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int TICKET_RADIUS = 2;
    private static final int MAX_ACTIVE_JOBS = 2;
    private static final int MAX_IN_FLIGHT_CHUNK_LOADS = 2;

    private static final Set<NewChunkLoadEvent.LoadingChunkKey> knownKeys = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<PendingJob> pendingQueue = new ConcurrentLinkedQueue<>();
    private static final Map<NewChunkLoadEvent.LoadingChunkKey, Job> activeJobs = new ConcurrentHashMap<>();
    private static final AtomicInteger promotingCount = new AtomicInteger(0);
    private static final AtomicInteger inFlightChunkLoads = new AtomicInteger(0);

    private static PlatformScheduler.TaskHandle driver;
    private static boolean warned;
    private static long lastWarning;
    private static int suppressedWarnings;

    private TerrainScanQueue() {
    }

    private record PendingJob(
            NewChunkLoadEvent.LoadingChunkKey key,
            World world,
            int gridX,
            int gridZ,
            List<Supplier<FitAnything>> scans
    ) {
    }

    /** Queues a new grid chunk scan without holding chunk tickets while waiting in the queue. */
    static void enqueue(NewChunkLoadEvent.LoadingChunkKey key, Chunk grid, List<Supplier<FitAnything>> scans) {
        if (scans.isEmpty() || !knownKeys.add(key)) return;
        pendingQueue.add(new PendingJob(key, grid.getWorld(), grid.getX(), grid.getZ(), scans));
        ensureDriver();
    }

    private static synchronized void ensureDriver() {
        if (driver == null && MetadataHandler.PLUGIN != null && MetadataHandler.PLUGIN.isEnabled()) {
            driver = PlatformScheduler.runGlobalTimer(TerrainScanQueue::tick, 1L, 1L);
        }
    }

    /** Whether an active scan asked for this chunk, so it does not trigger recursive structure scans. */
    static boolean requested(Chunk chunk) {
        if (activeJobs.isEmpty()) return false;
        World world = chunk.getWorld();
        long key = TerrainReads.key(chunk.getX(), chunk.getZ());
        for (Job job : activeJobs.values()) {
            if (job.world == world && job.requested.contains(key)) return true;
        }
        return false;
    }

    /** Drops every scan and its chunk holds. Unstarted scans are passed to replay when given. */
    static synchronized void clear(Consumer<NewChunkLoadEvent.LoadingChunkKey> replay) {
        PendingJob pending;
        while ((pending = pendingQueue.poll()) != null) {
            knownKeys.remove(pending.key);
            if (replay != null) replay.accept(pending.key);
        }
        for (Job job : activeJobs.values()) {
            knownKeys.remove(job.key);
            if (replay != null && !job.started) replay.accept(job.key);
            job.close();
        }
        activeJobs.clear();
        knownKeys.clear();
        promotingCount.set(0);
        inFlightChunkLoads.set(0);
        if (driver != null) {
            driver.cancel();
            driver = null;
        }
    }

    private static void tick() {
        promotePendingJobs();

        for (Job job : new ArrayList<>(activeJobs.values())) {
            if (job.closed.get() || job.running.get()) continue;
            if (Bukkit.getWorld(job.world.getUID()) != job.world) {
                finish(job);
                continue;
            }
            if (!job.ready()) {
                if (System.nanoTime() - job.waitingSince > TIMEOUT_NANOS) {
                    warnTimedOut(job);
                    finish(job);
                }
                continue;
            }
            if (!job.running.compareAndSet(false, true)) continue;

            PlatformScheduler.runRegion(job.world, job.gridX, job.gridZ, () -> {
                try {
                    if (job.closed.get()) return;
                    if (!job.world.isChunkLoaded(job.gridX, job.gridZ)) {
                        job.waitingSince = System.nanoTime();
                        return;
                    }
                    job.runScanStep();
                    if (job.waiting.isEmpty()) {
                        finish(job);
                        return;
                    }
                    job.waitingSince = System.nanoTime();
                    job.ready();
                } catch (Throwable t) {
                    MetadataHandler.PLUGIN.getLogger().log(Level.WARNING,
                            "Unexpected error in terrain scan job at " + job.key, t);
                    finish(job);
                } finally {
                    job.running.set(false);
                }
            });
        }

        if (activeJobs.isEmpty() && pendingQueue.isEmpty() && promotingCount.get() == 0) {
            synchronized (TerrainScanQueue.class) {
                if (activeJobs.isEmpty() && pendingQueue.isEmpty() && promotingCount.get() == 0 && driver != null) {
                    driver.cancel();
                    driver = null;
                }
            }
        }
    }

    private static void promotePendingJobs() {
        while (activeJobs.size() + promotingCount.get() < MAX_ACTIVE_JOBS) {
            PendingJob pending = pendingQueue.poll();
            if (pending == null) break;

            if (Bukkit.getWorld(pending.world.getUID()) != pending.world) {
                knownKeys.remove(pending.key);
                continue;
            }

            promotingCount.incrementAndGet();
            // Hold the grid chunk only once promoted to an active job
            PluginChunkHolds.hold(pending.world, pending.gridX, pending.gridZ);

            pending.world.getChunkAtAsync(pending.gridX, pending.gridZ, true).whenComplete((chunk, ex) -> {
                try {
                    if (ex != null || chunk == null || !chunk.isLoaded()
                            || Bukkit.getWorld(pending.world.getUID()) != pending.world) {
                        PluginChunkHolds.release(pending.world, pending.gridX, pending.gridZ);
                        knownKeys.remove(pending.key);
                        return;
                    }
                    Job job = new Job(pending.key, pending.world, pending.gridX, pending.gridZ, pending.scans);
                    activeJobs.put(pending.key, job);
                } finally {
                    promotingCount.decrementAndGet();
                }
            });
        }
    }

    private static void finish(Job job) {
        activeJobs.remove(job.key);
        knownKeys.remove(job.key);
        job.close();
    }

    private static void warnTimedOut(Job job) {
        long now = System.nanoTime();
        if (warned && now - lastWarning < WARNING_INTERVAL_NANOS) {
            suppressedWarnings++;
            return;
        }
        List<String> missing = new ArrayList<>();
        for (long chunk : job.awaited) {
            int x = TerrainReads.chunkX(chunk), z = TerrainReads.chunkZ(chunk);
            if (!job.world.isChunkLoaded(x, z)) missing.add("(" + x + ", " + z + ")");
        }
        Logger.warn("Skipped structure placement at chunk " + job.gridX + ", " + job.gridZ + " in "
                + job.world.getName() + " because chunks " + String.join(", ", missing)
                + " did not finish loading within 60 seconds."
                + (suppressedWarnings > 0 ? " " + suppressedWarnings + " more scans were skipped the same way since the last warning." : ""));
        warned = true;
        lastWarning = now;
        suppressedWarnings = 0;
    }

    private static final class Job {
        private final NewChunkLoadEvent.LoadingChunkKey key;
        private final World world;
        private final int gridX;
        private final int gridZ;
        private List<Supplier<FitAnything>> scans;
        private final List<FitAnything> waiting = Collections.synchronizedList(new ArrayList<>());
        private final Set<Long> awaited = ConcurrentHashMap.newKeySet();
        private final Set<Long> requested = ConcurrentHashMap.newKeySet();
        private final Set<Long> held = ConcurrentHashMap.newKeySet();
        private volatile long waitingSince = System.nanoTime();
        private volatile boolean started;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean running = new AtomicBoolean(false);

        private Job(NewChunkLoadEvent.LoadingChunkKey key, World world, int gridX, int gridZ, List<Supplier<FitAnything>> scans) {
            this.key = key;
            this.world = world;
            this.gridX = gridX;
            this.gridZ = gridZ;
            this.scans = scans;
        }

        private void runScanStep() {
            started = true;
            List<Supplier<FitAnything>> attempts = new ArrayList<>();
            if (scans != null) {
                attempts.addAll(scans);
                scans = null;
            }
            synchronized (waiting) {
                for (FitAnything fit : waiting) {
                    attempts.add(() -> {
                        fit.rescan();
                        return fit;
                    });
                }
                waiting.clear();
            }
            awaited.clear();
            requested.clear();

            for (Supplier<FitAnything> attempt : attempts) {
                List<FitAnything> fit = new ArrayList<>(1);
                Set<Long> misses;
                try {
                    misses = TerrainReads.collectMisses(() -> fit.add(attempt.get()));
                } catch (Throwable failure) {
                    MetadataHandler.PLUGIN.getLogger().log(Level.WARNING, "Failed chunk scan at "
                            + world.getName() + " " + gridX + ", " + gridZ, failure);
                    continue;
                }
                if (misses.isEmpty() || fit.isEmpty() || fit.get(0) == null) continue;
                waiting.add(fit.get(0));
                awaited.addAll(misses);
            }
        }

        private boolean ready() {
            boolean allLoaded = true;
            for (long chunk : awaited) {
                int x = TerrainReads.chunkX(chunk), z = TerrainReads.chunkZ(chunk);
                if (world.isChunkLoaded(x, z)) {
                    holdChunkKey(chunk);
                } else {
                    allLoaded = false;
                    requestChunk(chunk);
                }
            }
            return allLoaded;
        }

        private void requestChunk(long chunk) {
            if (withinGridTicket(chunk) || requested.contains(chunk)) return;
            // Throttle concurrent async chunk loads across scans to avoid disk I/O spikes
            if (inFlightChunkLoads.get() >= MAX_IN_FLIGHT_CHUNK_LOADS) return;
            if (!requested.add(chunk)) return;

            inFlightChunkLoads.incrementAndGet();
            int x = TerrainReads.chunkX(chunk);
            int z = TerrainReads.chunkZ(chunk);

            world.getChunkAtAsync(x, z, true).whenComplete((loaded, failure) -> {
                try {
                    if (closed.get()) return;
                    if (failure == null && loaded != null && loaded.isLoaded()) {
                        holdChunkKey(chunk);
                    } else {
                        requested.remove(chunk);
                    }
                } finally {
                    inFlightChunkLoads.decrementAndGet();
                }
            });
        }

        private void holdChunkKey(long chunk) {
            if (withinGridTicket(chunk) || !held.add(chunk)) return;
            PluginChunkHolds.hold(world, TerrainReads.chunkX(chunk), TerrainReads.chunkZ(chunk));
        }

        private boolean withinGridTicket(long chunk) {
            return Math.abs(TerrainReads.chunkX(chunk) - gridX) <= TICKET_RADIUS
                    && Math.abs(TerrainReads.chunkZ(chunk) - gridZ) <= TICKET_RADIUS;
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) return;
            PluginChunkHolds.release(world, gridX, gridZ);
            for (long chunk : held) {
                PluginChunkHolds.release(world, TerrainReads.chunkX(chunk), TerrainReads.chunkZ(chunk));
            }
            held.clear();
        }
    }
}
