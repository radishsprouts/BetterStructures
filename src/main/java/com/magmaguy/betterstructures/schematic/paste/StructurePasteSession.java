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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * @deprecated 貼り付けは {@link com.magmaguy.betterstructures.paste.NativePasteEngine} に一本化した。呼び出し元
 * （FitAnything・ModulePasting・BetterStructures・Schematic）の付け替えが済み次第、このクラスと
 * {@link ChunkPasteTask} を削除する。新しく使わないこと。
 *
 * Manages the asynchronous, serialized per-chunk paste lifecycle of a structure.
 * Guarantees 2.0ms per-tick regional work budget, minimal chunk ticket hold (1 chunk at a time),
 * and 100% completion without dropping queued structures.
 */
@Deprecated
public final class StructurePasteSession {

    private static final int MAX_CONCURRENT_SESSIONS = 2;
    private static final long MAX_NANOS_PER_TICK = 2_000_000L; // Strict 2.0ms per tick budget

    private static final Set<StructurePasteSession> activeSessions = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<StructurePasteSession> pendingSessions = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger runningSessionCount = new AtomicInteger(0);

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
    private final Set<Long> heldChunkKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Long> chunkQueue = new ConcurrentLinkedQueue<>();
    private Map<Long, List<ChunkPasteTask.StructureBlockEntry>> blocksByChunk = Collections.emptyMap();
    private Map<Long, List<ChunkPasteTask.StructureEntityEntry>> entitiesByChunk = Collections.emptyMap();

    @Getter
    private final long maxNanosPerTick = MAX_NANOS_PER_TICK;

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
        pendingSessions.add(session);
        tryStartNextSession();
        return session;
    }

    private static void tryStartNextSession() {
        while (runningSessionCount.get() < MAX_CONCURRENT_SESSIONS) {
            StructurePasteSession next = pendingSessions.poll();
            if (next == null) break;
            if (next.isCancelled()) {
                activeSessions.remove(next);
                continue;
            }
            if (runningSessionCount.incrementAndGet() <= MAX_CONCURRENT_SESSIONS) {
                next.dispatchAsyncPreparation();
            } else {
                runningSessionCount.decrementAndGet();
                pendingSessions.add(next);
                break;
            }
        }
    }

    public static void shutdownAll() {
        pendingSessions.clear();
        for (StructurePasteSession session : activeSessions) {
            session.cancel();
        }
        activeSessions.clear();
        runningSessionCount.set(0);
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

    private void dispatchAsyncPreparation() {
        if (isCancelled() || world == null || Bukkit.getWorld(world.getUID()) != world) {
            finishSession();
            return;
        }

        final int baseX = baseLocation.getBlockX() + schematicOffset.getBlockX();
        final int baseY = baseLocation.getBlockY() + schematicOffset.getBlockY();
        final int baseZ = baseLocation.getBlockZ() + schematicOffset.getBlockZ();

        PlatformScheduler.runAsync(() -> {
            try {
                if (isCancelled()) {
                    finishSession();
                    return;
                }

                Map<Long, List<ChunkPasteTask.StructureBlockEntry>> preparedBlocks = new HashMap<>();
                for (int y = 0; y < schematic.getHeight(); y++) {
                    for (int z = 0; z < schematic.getLength(); z++) {
                        for (int x = 0; x < schematic.getWidth(); x++) {
                            StructureBlock block = schematic.getBlock(x, y, z);
                            if (block == null || block.blockData() == null) continue;
                            if (block.blockData().getMaterial() == Material.BARRIER) continue;

                            int wx = baseX + x;
                            int wy = baseY + y;
                            int wz = baseZ + z;
                            long chunkKey = Chunk.getChunkKey(wx >> 4, wz >> 4);

                            preparedBlocks.computeIfAbsent(chunkKey, k -> new ArrayList<>())
                                    .add(new ChunkPasteTask.StructureBlockEntry(wx, wy, wz, block, new Vector(x, y, z)));
                        }
                    }
                }

                // Sort each chunk's blocks by Y ascending on the async thread
                Comparator<ChunkPasteTask.StructureBlockEntry> byY =
                        Comparator.comparingInt(ChunkPasteTask.StructureBlockEntry::worldY);
                for (List<ChunkPasteTask.StructureBlockEntry> list : preparedBlocks.values()) {
                    list.sort(byY);
                }

                Map<Long, List<ChunkPasteTask.StructureEntityEntry>> preparedEntities = new HashMap<>();
                for (StructureEntity entity : schematic.getEntities()) {
                    double wx = baseX + entity.x();
                    double wy = baseY + entity.y();
                    double wz = baseZ + entity.z();
                    int cx = ((int) Math.floor(wx)) >> 4;
                    int cz = ((int) Math.floor(wz)) >> 4;
                    long chunkKey = Chunk.getChunkKey(cx, cz);

                    preparedEntities.computeIfAbsent(chunkKey, k -> new ArrayList<>())
                            .add(new ChunkPasteTask.StructureEntityEntry(wx, wy, wz, entity));
                }

                Set<Long> allChunkKeys = new LinkedHashSet<>(preparedBlocks.keySet());
                allChunkKeys.addAll(preparedEntities.keySet());

                if (allChunkKeys.isEmpty()) {
                    finishSession();
                    return;
                }

                this.blocksByChunk = preparedBlocks;
                this.entitiesByChunk = preparedEntities;
                this.chunkQueue.addAll(allChunkKeys);

                processNextChunk();
            } catch (Throwable t) {
                Logger.warn("Failed to prepare structure paste session: " + t.getMessage());
                finishSession();
            }
        });
    }

    private void processNextChunk() {
        if (isCancelled() || Bukkit.getWorld(world.getUID()) != world) {
            finishSession();
            return;
        }

        Long nextKey = chunkQueue.poll();
        if (nextKey == null) {
            finishSession();
            return;
        }

        final long chunkKey = nextKey;
        final int cx = (int) chunkKey;
        final int cz = (int) (chunkKey >> 32);

        List<ChunkPasteTask.StructureBlockEntry> chunkBlocks =
                blocksByChunk.getOrDefault(chunkKey, Collections.emptyList());
        List<ChunkPasteTask.StructureEntityEntry> chunkEntities =
                entitiesByChunk.getOrDefault(chunkKey, Collections.emptyList());

        ChunkPasteTask task = new ChunkPasteTask(
                this, world, cx, cz, chunkBlocks, chunkEntities, pedestalMaterialProvider
        );

        world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, ex) -> {
            if (ex != null || isCancelled() || chunk == null || !chunk.isLoaded()
                    || Bukkit.getWorld(world.getUID()) != world) {
                if (ex != null) {
                    Logger.warn("Failed to load chunk " + cx + ", " + cz + " for schematic paste: " + ex.getMessage());
                }
                onChunkComplete(cx, cz);
                return;
            }

            heldChunkKeys.add(chunkKey);
            PluginChunkHolds.hold(world, cx, cz);

            PlatformScheduler.runRegion(world, cx, cz, task);
        });
    }

    void onChunkComplete(int chunkX, int chunkZ) {
        long chunkKey = Chunk.getChunkKey(chunkX, chunkZ);
        try {
            if (heldChunkKeys.remove(chunkKey)) {
                PluginChunkHolds.release(world, chunkX, chunkZ);
            }
            // Free processed chunk lists immediately for GC
            blocksByChunk.remove(chunkKey);
            entitiesByChunk.remove(chunkKey);
        } finally {
            processNextChunk();
        }
    }

    private void finishSession() {
        if (finished.compareAndSet(false, true)) {
            try {
                releaseAllHeldChunks();
                activeSessions.remove(this);

                if (!isCancelled() && Bukkit.getWorld(world.getUID()) == world) {
                    Runnable completionTask = () -> {
                        try {
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
                                onComplete.run();
                            }

                            announceCompletion();
                        } catch (Throwable t) {
                            Logger.warn("Exception in paste completion callback: " + t.getMessage());
                        }
                    };

                    if (PlatformScheduler.isOwnedByCurrentRegion(baseLocation)) {
                        completionTask.run();
                    } else {
                        PlatformScheduler.runRegion(baseLocation, completionTask);
                    }
                }
            } finally {
                runningSessionCount.decrementAndGet();
                tryStartNextSession();
            }
        }
    }

    private void releaseAllHeldChunks() {
        for (long chunkKey : heldChunkKeys) {
            int cx = (int) chunkKey;
            int cz = (int) (chunkKey >> 32);
            try {
                PluginChunkHolds.release(world, cx, cz);
            } catch (Exception ignored) {
            }
        }
        heldChunkKeys.clear();
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
