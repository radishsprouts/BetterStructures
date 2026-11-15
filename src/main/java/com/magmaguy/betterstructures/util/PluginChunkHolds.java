package com.magmaguy.betterstructures.util;

import com.magmaguy.betterstructures.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, reference-counted plugin chunk ticket manager compatible with both Paper and Folia.
 */
public final class PluginChunkHolds {
    private static final Map<ChunkKey, Hold> holds = new ConcurrentHashMap<>();

    private PluginChunkHolds() {
    }

    public static void hold(Chunk chunk) {
        if (chunk == null) return;
        hold(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    public static void hold(World world, int chunkX, int chunkZ) {
        if (world == null || MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled()) return;
        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
        boolean[] needAddTicket = {false};

        holds.compute(key, (k, existing) -> {
            if (existing != null && existing.world == world) {
                existing.count++;
                return existing;
            }
            needAddTicket[0] = true;
            return new Hold(world);
        });

        if (needAddTicket[0]) {
            applyTicketChange(world, chunkX, chunkZ, true);
        }
    }

    public static void release(Chunk chunk) {
        if (chunk == null) return;
        release(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    public static void release(World world, int chunkX, int chunkZ) {
        if (world == null) return;
        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
        boolean[] needRemoveTicket = {false};

        holds.computeIfPresent(key, (k, existing) -> {
            if (existing.world != world) {
                return null;
            }
            if (--existing.count > 0) {
                return existing;
            }
            needRemoveTicket[0] = true;
            return null;
        });

        if (needRemoveTicket[0]) {
            applyTicketChange(world, chunkX, chunkZ, false);
        }
    }

    private static void applyTicketChange(World world, int chunkX, int chunkZ, boolean add) {
        Plugin plugin = MetadataHandler.PLUGIN;
        if (plugin == null || !plugin.isEnabled()) return;
        if (Bukkit.getWorld(world.getUID()) != world) return;

        Runnable action = () -> {
            if (Bukkit.getWorld(world.getUID()) != world || !plugin.isEnabled()) return;
            try {
                if (add) {
                    // Only add ticket if hold is still active
                    Hold current = holds.get(new ChunkKey(world.getUID(), chunkX, chunkZ));
                    if (current != null && current.world == world && current.count > 0) {
                        world.addPluginChunkTicket(chunkX, chunkZ, plugin);
                    }
                } else {
                    // Only remove ticket if no new hold was acquired in the meantime
                    Hold current = holds.get(new ChunkKey(world.getUID(), chunkX, chunkZ));
                    if (current == null || current.world != world || current.count <= 0) {
                        world.removePluginChunkTicket(chunkX, chunkZ, plugin);
                    }
                }
            } catch (Throwable ignored) {
            }
        };

        if (PlatformScheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            action.run();
        } else {
            PlatformScheduler.runRegion(world, chunkX, chunkZ, action);
        }
    }

    public static void shutdown() {
        holds.clear();
    }

    private record ChunkKey(UUID worldId, int x, int z) {
    }

    private static final class Hold {
        private final World world;
        private int count = 1;

        private Hold(World world) {
            this.world = world;
        }
    }
}
