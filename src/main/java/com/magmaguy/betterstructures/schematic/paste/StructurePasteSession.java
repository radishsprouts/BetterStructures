package com.magmaguy.betterstructures.schematic.paste;

import com.magmaguy.betterstructures.api.BuildPasteCompleteEvent;
import com.magmaguy.betterstructures.buildingfitter.FitAnything;
import com.magmaguy.betterstructures.chests.ChestContents;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.schematic.model.StructureBlock;
import com.magmaguy.betterstructures.schematic.model.StructureEntity;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import com.magmaguy.betterstructures.worldedit.PasteBudget;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Manages the entire asynchronous, region-distributed paste lifecycle of a structure.
 * Guarantees zero main-thread locking, bounded tick work budgets, and complete generation.
 */
public final class StructurePasteSession {

    private static final Set<StructurePasteSession> activeSessions = ConcurrentHashMap.newKeySet();

    @Getter
    private final World world;
    @Getter
    private final Location baseLocation;
    @Getter
    private final Vector schematicOffset;
    @Getter
    private final StructureSchematic schematic;
    @Getter
    private final FitAnything fitter;
    @Getter
    private final Function<Boolean, Material> pedestalMaterialProvider;
    private final Runnable onComplete;
    private final ChestContents chestContents;
    private final ChestContents barrelContents;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final Set<Chunk> heldChunks = ConcurrentHashMap.newKeySet();
    private final AtomicInteger remainingChunks = new AtomicInteger(0);

    @Getter
    private final long maxNanosPerTick;

    public StructurePasteSession(
            World world,
            Location baseLocation,
            Vector schematicOffset,
            StructureSchematic schematic,
            FitAnything fitter,
            Function<Boolean, Material> pedestalMaterialProvider,
            ChestContents chestContents,
            ChestContents barrelContents,
            Runnable onComplete
    ) {
        this.world = world;
        this.baseLocation = baseLocation;
        this.schematicOffset = schematicOffset != null ? schematicOffset : new Vector(0, 0, 0);
        this.schematic = schematic;
        this.fitter = fitter;
        this.pedestalMaterialProvider = pedestalMaterialProvider;
        this.chestContents = chestContents;
        this.barrelContents = barrelContents;
        this.onComplete = onComplete;

        // Bounded per-tick work budget (2ms - 5ms by default, respecting DefaultConfig)
        double configuredShare = DefaultConfig.getPercentageOfTickUsedForPasting();
        long calculatedNanos = PasteBudget.nanosPerTick(configuredShare);
        // Bound between 2ms and 5ms to ensure regional threads never spike or freeze
        this.maxNanosPerTick = Math.max(2_000_000L, Math.min(calculatedNanos, 5_000_000L));
    }

    public static StructurePasteSession start(
            World world,
            Location baseLocation,
            Vector schematicOffset,
            StructureSchematic schematic,
            FitAnything fitter,
            Function<Boolean, Material> pedestalMaterialProvider,
            ChestContents chestContents,
            ChestContents barrelContents,
            Runnable onComplete
    ) {
        StructurePasteSession session = new StructurePasteSession(
                world, baseLocation, schematicOffset, schematic, fitter,
                pedestalMaterialProvider, chestContents, barrelContents, onComplete
        );
        activeSessions.add(session);
        session.dispatch();
        return session;
    }

    public static void shutdownAll() {
        for (StructurePasteSession session : activeSessions) {
            session.cancel();
        }
        activeSessions.clear();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            releaseAllHeldChunks();
            activeSessions.remove(this);
        }
    }

    public ChestContents getChestContents(boolean isBarrel) {
        return isBarrel ? barrelContents : chestContents;
    }

    private void dispatch() {
        if (isCancelled()) return;

        int baseX = baseLocation.getBlockX() + schematicOffset.getBlockX();
        int baseY = baseLocation.getBlockY() + schematicOffset.getBlockY();
        int baseZ = baseLocation.getBlockZ() + schematicOffset.getBlockZ();

        // 1. Group blocks by chunk
        Map<Long, List<ChunkPasteTask.StructureBlockEntry>> blocksByChunk = new HashMap<>();

        for (int y = 0; y < schematic.getHeight(); y++) {
            for (int z = 0; z < schematic.getLength(); z++) {
                for (int x = 0; x < schematic.getWidth(); x++) {
                    StructureBlock block = schematic.getBlock(x, y, z);
                    if (block == null || block.blockData() == null) continue;

                    int wx = baseX + x;
                    int wy = baseY + y;
                    int wz = baseZ + z;

                    int cx = wx >> 4;
                    int cz = wz >> 4;
                    long chunkKey = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);

                    blocksByChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>())
                            .add(new ChunkPasteTask.StructureBlockEntry(wx, wy, wz, block, new Vector(x, y, z)));
                }
            }
        }

        // 2. Group entities by chunk
        Map<Long, List<ChunkPasteTask.StructureEntityEntry>> entitiesByChunk = new HashMap<>();
        for (StructureEntity entity : schematic.getEntities()) {
            double wx = baseX + entity.x();
            double wy = baseY + entity.y();
            double wz = baseZ + entity.z();

            int cx = ((int) Math.floor(wx)) >> 4;
            int cz = ((int) Math.floor(wz)) >> 4;
            long chunkKey = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);

            entitiesByChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>())
                    .add(new ChunkPasteTask.StructureEntityEntry(wx, wy, wz, entity));
        }

        Set<Long> allChunkKeys = new HashSet<>(blocksByChunk.keySet());
        allChunkKeys.addAll(entitiesByChunk.keySet());

        if (allChunkKeys.isEmpty()) {
            finishSession();
            return;
        }

        remainingChunks.set(allChunkKeys.size());

        // 3. For each chunk, request async load and dispatch to region scheduler upon load
        for (long chunkKey : allChunkKeys) {
            int cx = (int) (chunkKey >> 32);
            int cz = (int) chunkKey;

            List<ChunkPasteTask.StructureBlockEntry> chunkBlocks = blocksByChunk.getOrDefault(chunkKey, Collections.emptyList());
            List<ChunkPasteTask.StructureEntityEntry> chunkEntities = entitiesByChunk.getOrDefault(chunkKey, Collections.emptyList());

            ChunkPasteTask task = new ChunkPasteTask(
                    this, world, cx, cz, chunkBlocks, chunkEntities, pedestalMaterialProvider
            );

            // Paper / Folia async chunk load
            world.getChunkAtAsync(cx, cz, true).thenAccept(chunk -> {
                if (isCancelled() || chunk == null || !chunk.isLoaded()) {
                    onChunkComplete(cx, cz);
                    return;
                }
                // Hold chunk ticket so it won't unload while pasting
                heldChunks.add(chunk);
                PluginChunkHolds.hold(chunk);

                // Dispatch to region thread
                PlatformScheduler.runRegion(world, cx, cz, task);
            }).exceptionally(ex -> {
                Logger.warn("Failed to load chunk " + cx + ", " + cz + " for schematic paste: " + ex.getMessage());
                onChunkComplete(cx, cz);
                return null;
            });
        }
    }

    void onChunkComplete(int chunkX, int chunkZ) {
        if (remainingChunks.decrementAndGet() <= 0) {
            finishSession();
        }
    }

    private void finishSession() {
        if (finished.compareAndSet(false, true)) {
            releaseAllHeldChunks();
            activeSessions.remove(this);

            if (!isCancelled()) {
                // Fire BuildPasteCompleteEvent
                Location minCorner = baseLocation.clone().add(schematicOffset);
                Location maxCorner = minCorner.clone().add(
                        schematic.getWidth() - 1,
                        schematic.getHeight() - 1,
                        schematic.getLength() - 1
                );
                List<Location> containers = new ArrayList<>();
                for (Vector chestPos : schematic.getChestLocations()) {
                    containers.add(minCorner.clone().add(chestPos));
                }

                if (fitter != null) {
                    Bukkit.getPluginManager().callEvent(
                            new BuildPasteCompleteEvent(fitter, minCorner, maxCorner, containers)
                    );
                }

                if (onComplete != null) {
                    try {
                        onComplete.run();
                    } catch (Throwable t) {
                        Logger.warn("Exception in paste onComplete callback: " + t.getMessage());
                    }
                }

                announceCompletion();
            }
        }
    }

    private void releaseAllHeldChunks() {
        for (Chunk chunk : heldChunks) {
            try {
                PluginChunkHolds.release(chunk);
            } catch (Exception ignored) {
            }
        }
        heldChunks.clear();
    }

    private void announceCompletion() {
        if (!DefaultConfig.isNewBuildingWarn()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("betterstructures.warn")) {
                player.spigot().sendMessage(SpigotMessage.commandHoverMessage(
                        "[BetterStructures] New building generated! Click to teleport. Do \"/betterstructures silent\" to stop getting warnings!",
                        "Click to teleport to " + baseLocation.getWorld().getName() + ", " + baseLocation.getBlockX() + ", " + baseLocation.getBlockY() + ", " + baseLocation.getBlockZ(),
                        "/betterstructures teleport " + baseLocation.getWorld().getName() + " " + baseLocation.getBlockX() + " " + baseLocation.getBlockY() + " " + baseLocation.getBlockZ()));
            }
        }
    }
}
