package com.magmaguy.betterstructures.buildingfitter.util;

import com.magmaguy.betterstructures.util.PlatformScheduler;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps natural terrain scans from loading chunks or reading across Folia region boundaries.
 * Thread-safe via ThreadLocal so multiple Folia region threads can scan concurrently.
 */
public final class TerrainReads {

    private static final class ScanContext {
        final Set<Long> misses = new HashSet<>();
        boolean unownedAbort = false;
        World lastWorld;
        long lastChunk = Long.MIN_VALUE;
    }

    private static final ThreadLocal<ScanContext> CONTEXT = new ThreadLocal<>();

    private TerrainReads() {
    }

    /** Runs a scan with reads restricted to loaded, region-owned chunks and returns unloaded chunks it met. */
    public static Set<Long> collectMisses(Runnable scan) {
        if (CONTEXT.get() != null) throw new IllegalStateException("Terrain scans do not nest");
        ScanContext ctx = new ScanContext();
        CONTEXT.set(ctx);
        try {
            scan.run();
            // If the scan touched a chunk owned by a different Folia region thread, discard misses
            // so the scan safely aborts without retrying cross-region reads.
            if (ctx.unownedAbort) {
                ctx.misses.clear();
            }
            return ctx.misses;
        } finally {
            CONTEXT.remove();
        }
    }

    /** Whether the chunk at this location can be read safely on the current thread. */
    public static boolean readable(Location location) {
        ScanContext ctx = CONTEXT.get();
        if (ctx == null) return true;
        if (location == null || location.getWorld() == null) return false;

        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        long chunk = key(chunkX, chunkZ);

        if (world == ctx.lastWorld && chunk == ctx.lastChunk) return true;

        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            ctx.misses.add(chunk);
            return false;
        }

        // Folia safety: never read blocks from a chunk not owned by the current region thread
        if (PlatformScheduler.isFolia() && !PlatformScheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            ctx.unownedAbort = true;
            return false;
        }

        ctx.lastWorld = world;
        ctx.lastChunk = chunk;
        return true;
    }

    /** Whether the running scan has found a chunk it could not read, so its result must not be used. */
    public static boolean incomplete() {
        ScanContext ctx = CONTEXT.get();
        return ctx != null && (!ctx.misses.isEmpty() || ctx.unownedAbort);
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }
}
