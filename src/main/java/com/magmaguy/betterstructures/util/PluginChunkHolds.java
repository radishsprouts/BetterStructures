package com.magmaguy.betterstructures.util;

import com.magmaguy.betterstructures.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /**
     * 持ち主を切り替える。新しい持ち主で先に hold してから古い持ち主の分を release するので、
     * 参照カウントが 0 になる瞬間（=チケットが外れて千切れる瞬間）を作らない（04章 G2）。
     * どのスレッドから呼んでもよい。チケットの付け外しは今までどおり所有リージョンで行う。
     *
     * @throws IllegalArgumentException ワールドが違う時
     */
    public static void transfer(HoldSet from, HoldSet to) {
        if (from == null || to == null || from == to) return;
        if (from.world != to.world) {
            throw new IllegalArgumentException("hold の引き継ぎはワールドをまたげません");
        }
        for (long key : from.keys()) {
            to.hold((int) key, (int) (key >> 32));
        }
        from.releaseAll();
    }

    public static void shutdown() {
        holds.clear();
    }

    /**
     * 1つの持ち主（走査のジョブ・貼り付けのセッション等）が持っているチャンクの hold の集まり。
     * 同じチャンクを同じ持ち主が二重に hold しない（持ち主の中では 0/1）。スレッドセーフ。
     */
    public static final class HoldSet {
        private final World world;
        private final Set<Long> keys = ConcurrentHashMap.newKeySet();

        public HoldSet(World world) {
            this.world = Objects.requireNonNull(world, "world");
        }

        public World world() {
            return world;
        }

        /** まだ持っていなければ hold する。実際に hold したら true。 */
        public boolean hold(int chunkX, int chunkZ) {
            if (!keys.add(Chunk.getChunkKey(chunkX, chunkZ))) return false;
            PluginChunkHolds.hold(world, chunkX, chunkZ);
            return true;
        }

        /** 持っていれば release する。実際に release したら true。 */
        public boolean release(int chunkX, int chunkZ) {
            if (!keys.remove(Chunk.getChunkKey(chunkX, chunkZ))) return false;
            PluginChunkHolds.release(world, chunkX, chunkZ);
            return true;
        }

        public boolean contains(int chunkX, int chunkZ) {
            return keys.contains(Chunk.getChunkKey(chunkX, chunkZ));
        }

        public int size() {
            return keys.size();
        }

        /** 持っているチャンクの鍵（{@code Chunk#getChunkKey} 式）の写し。 */
        public Set<Long> keys() {
            return Set.copyOf(keys);
        }

        /** 持っているものを全部 release する。 */
        public void releaseAll() {
            for (long key : Set.copyOf(keys)) {
                release((int) key, (int) (key >> 32));
            }
        }
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
