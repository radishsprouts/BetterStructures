package com.magmaguy.betterstructures.schematic.paste;

import com.magmaguy.betterstructures.api.BuildCustomMarkerEvent;
import com.magmaguy.betterstructures.api.ChestFillEvent;
import com.magmaguy.betterstructures.chests.ChestContents;
import com.magmaguy.betterstructures.config.spawnpools.SpawnPoolsConfig;
import com.magmaguy.betterstructures.config.spawnpools.SpawnPoolsConfigFields;
import com.magmaguy.betterstructures.schematic.entity.EntityNbtApplier;
import com.magmaguy.betterstructures.schematic.model.StructureBlock;
import com.magmaguy.betterstructures.schematic.model.StructureEntity;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import com.magmaguy.betterstructures.thirdparty.EliteMobs;
import com.magmaguy.betterstructures.thirdparty.MythicMobs;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Function;

/**
 * Handles time-budgeted pasting of blocks, tile entities, and entities for a single chunk
 * on its owning Folia region thread.
 */
public final class ChunkPasteTask implements Runnable {

    private final StructurePasteSession session;
    private final World world;
    private final int chunkX;
    private final int chunkZ;
    private final List<StructureBlockEntry> blocks;
    private final List<StructureEntityEntry> entities;
    private final Function<Boolean, Material> pedestalMaterialProvider;

    private int blockCursor = 0;
    private boolean blocksFinished = false;
    private boolean completed = false;

    public record StructureBlockEntry(
            int worldX,
            int worldY,
            int worldZ,
            StructureBlock block,
            Vector schematicLocalPos
    ) {
    }

    public record StructureEntityEntry(
            double worldX,
            double worldY,
            double worldZ,
            StructureEntity entity
    ) {
    }

    public ChunkPasteTask(
            StructurePasteSession session,
            World world,
            int chunkX,
            int chunkZ,
            List<StructureBlockEntry> blocks,
            List<StructureEntityEntry> entities,
            Function<Boolean, Material> pedestalMaterialProvider
    ) {
        this.session = session;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.blocks = blocks != null ? blocks : new ArrayList<>();
        this.entities = entities != null ? entities : new ArrayList<>();
        this.pedestalMaterialProvider = pedestalMaterialProvider;

        // Sort blocks by Y ascending (bottom to top) to prevent gravity block falling or torch dropping
        this.blocks.sort(Comparator.comparingInt(StructureBlockEntry::worldY));
    }

    @Override
    public void run() {
        if (session.isCancelled() || completed) return;

        // Bounded per-tick budget (default: 2ms - 5ms)
        long deadline = System.nanoTime() + session.getMaxNanosPerTick();

        // 1. Paste blocks in slices
        if (!blocksFinished) {
            while (blockCursor < blocks.size()) {
                StructureBlockEntry entry = blocks.get(blockCursor);
                pasteSingleBlock(entry);
                blockCursor++;

                if (System.nanoTime() >= deadline && blockCursor < blocks.size()) {
                    // Time budget exhausted, defer remaining blocks to the next tick on this region
                    PlatformScheduler.runRegionLater(world, chunkX, chunkZ, this, 1L);
                    return;
                }
            }
            blocksFinished = true;
        }

        // 2. Post-process block entities, signs, containers, and entity spawns
        postProcessChunk();

        // 3. Mark chunk complete in session
        completed = true;
        session.onChunkComplete(chunkX, chunkZ);
    }

    private void pasteSingleBlock(StructureBlockEntry entry) {
        Block destination = world.getBlockAt(entry.worldX(), entry.worldY(), entry.worldZ());
        Material newMaterial = entry.block().blockData().getMaterial();

        // Barrier rule: barriers protect existing terrain, do not place
        if (newMaterial == Material.BARRIER) {
            return;
        }

        // Air optimization: if both are air, skip
        if (newMaterial.isAir() && destination.getType().isAir()) {
            return;
        }

        // Bedrock rule: bedrock becomes the pedestal material
        if (newMaterial == Material.BEDROCK) {
            if (!destination.getType().isSolid()) {
                boolean isGround = !world.getBlockAt(entry.worldX(), entry.worldY() + 1, entry.worldZ()).getType().isSolid();
                Material pedestal = pedestalMaterialProvider != null ? pedestalMaterialProvider.apply(isGround) : Material.COBBLESTONE;
                destination.setType(pedestal, false);
            }
            return;
        }

        // Special marker sign rule: marker signs turn into AIR, then spawn their target
        if (entry.block().isSign() && isMarkerSign(entry.block().parsedSign())) {
            destination.setType(Material.AIR, false);
            return;
        }

        // Normal block placement without physics
        destination.setBlockData(entry.block().blockData(), false);
    }

    private void postProcessChunk() {
        for (StructureBlockEntry entry : blocks) {
            Block destination = world.getBlockAt(entry.worldX(), entry.worldY(), entry.worldZ());
            StructureBlock structBlock = entry.block();

            // Handle signs
            if (structBlock.isSign()) {
                SignTextParser.ParsedSign sign = structBlock.parsedSign();
                if (isMarkerSign(sign)) {
                    handleMarkerSign(entry, sign);
                } else {
                    restoreNormalSign(destination, sign);
                }
            }

            // Handle containers (chests & barrels)
            if (destination.getState() instanceof Container container) {
                fillContainer(container, entry);
            }
        }

        // Spawn entities
        for (StructureEntityEntry entityEntry : entities) {
            spawnEntity(entityEntry);
        }
    }

    private boolean isMarkerSign(SignTextParser.ParsedSign sign) {
        if (sign == null) return false;
        for (int i = 0; i < 4; i++) {
            String line = sign.getFrontLine(i).toLowerCase(Locale.ROOT).trim();
            if (line.contains("[spawn]") || line.contains("[elitemobs]")
                    || line.contains("[pool:") || line.contains("[mythicmobs]")
                    || line.contains("[custom]") || line.contains("[chest]")
                    || line.contains("[trapped_chest]")) {
                return true;
            }
        }
        return false;
    }

    private void handleMarkerSign(StructureBlockEntry entry, SignTextParser.ParsedSign sign) {
        int bx = entry.worldX();
        int by = entry.worldY();
        int bz = entry.worldZ();

        // 1. Ensure marker sign block itself is turned into AIR
        world.getBlockAt(bx, by, bz).setType(Material.AIR, false);

        // 2. Prevent suffocation: if head space (by + 1) is solid, clear it so 2-block mobs don't suffocate immediately
        Block headBlock = world.getBlockAt(bx, by + 1, bz);
        if (headBlock.getType().isSolid()) {
            headBlock.setType(Material.AIR, false);
        }

        Location loc = new Location(world, bx + 0.5, by, bz + 0.5);

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add(sign.getFrontLine(i).trim());
        }

        Logger.info("[ChunkPasteTask] Processing marker sign at (" + bx + "," + by + "," + bz + "): lines=" + lines);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);

            // [spawn] Vanilla Mob
            if (lower.contains("[spawn]")) {
                String entityName = null;
                for (int j = i + 1; j < lines.size(); j++) {
                    String candidate = lines.get(j).replace("\"", "").trim();
                    if (!candidate.isEmpty()) {
                        entityName = candidate.toUpperCase(Locale.ROOT);
                        break;
                    }
                }
                if (entityName != null) {
                    spawnVanillaMob(loc, entityName);
                } else {
                    Logger.warn("[ChunkPasteTask] [spawn] sign at (" + bx + "," + by + "," + bz + ") has no entity type specified!");
                }
                return;
            }

            // [pool:...] Spawn pool
            if (lower.contains("[pool:")) {
                String poolName = SpawnPoolsConfig.extractPoolName(line);
                if (poolName != null) {
                    spawnPoolMob(loc, poolName);
                } else {
                    Logger.warn("[ChunkPasteTask] Could not extract pool name from: " + line);
                }
                return;
            }

            // [elitemobs]
            if (lower.contains("[elitemobs]")) {
                StringBuilder bossFile = new StringBuilder();
                for (int j = i + 1; j < lines.size(); j++) {
                    String part = lines.get(j).trim();
                    if (!part.isEmpty()) bossFile.append(part);
                }
                if (bossFile.length() > 0) {
                    EliteMobs.Spawn(loc, bossFile.toString());
                    Logger.info("[ChunkPasteTask] Spawned EliteMob " + bossFile + " at " + loc);
                }
                return;
            }

            // [mythicmobs]
            if (lower.contains("[mythicmobs]")) {
                String mob = (i + 1 < lines.size()) ? lines.get(i + 1).trim() : "";
                String level = (i + 2 < lines.size()) ? lines.get(i + 2).trim() : "";
                if (!mob.isEmpty()) {
                    MythicMobs.Spawn(loc, level.isEmpty() ? mob : mob + ":" + level);
                    Logger.info("[ChunkPasteTask] Spawned MythicMob " + mob + " at " + loc);
                }
                return;
            }

            // [custom]
            if (lower.contains("[custom]")) {
                List<String> args = new ArrayList<>();
                for (int j = i + 1; j < lines.size(); j++) args.add(lines.get(j).trim());
                Bukkit.getPluginManager().callEvent(new BuildCustomMarkerEvent(session.getFitter(), loc, args));
                Logger.info("[ChunkPasteTask] Dispatched custom marker at " + loc + ": " + args);
                return;
            }

            // [chest] & [trapped_chest]
            if (lower.contains("[chest]")) {
                world.getBlockAt(bx, by, bz).setType(Material.CHEST, false);
                Logger.info("[ChunkPasteTask] Replaced sign with chest at (" + bx + "," + by + "," + bz + ")");
                return;
            }
            if (lower.contains("[trapped_chest]")) {
                world.getBlockAt(bx, by, bz).setType(Material.TRAPPED_CHEST, false);
                Logger.info("[ChunkPasteTask] Replaced sign with trapped_chest at (" + bx + "," + by + "," + bz + ")");
                return;
            }
        }
    }

    private void spawnVanillaMob(Location loc, String entityName) {
        try {
            EntityType type = EntityType.valueOf(entityName.equalsIgnoreCase("WITHER_CRYSTAL") ? "END_CRYSTAL" : entityName);
            Class<? extends Entity> entityClass = type.getEntityClass();
            if (entityClass != null && LivingEntity.class.isAssignableFrom(entityClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends LivingEntity> livingClass = (Class<? extends LivingEntity>) entityClass;
                world.spawn(loc, livingClass, living -> {
                    living.setRemoveWhenFarAway(false);
                    living.setPersistent(true);
                    living.setNoDamageTicks(60);
                    living.setFallDistance(0);
                    Logger.info("[ChunkPasteTask] Spawned mob " + type + " at " + loc + " with persistence guaranteed");
                });
            } else {
                Entity entity = world.spawnEntity(loc, type);
                entity.setPersistent(true);
                if (entity instanceof LivingEntity living) {
                    living.setRemoveWhenFarAway(false);
                    living.setNoDamageTicks(60);
                    living.setFallDistance(0);
                }
                Logger.info("[ChunkPasteTask] Spawned entity " + type + " at " + loc + " (fallback)");
            }
        } catch (Exception e) {
            Logger.warn("[ChunkPasteTask] Failed to spawn entity from sign: " + entityName + " (" + e.getMessage() + ")");
        }
    }

    private void spawnPoolMob(Location loc, String poolName) {
        String poolFilename = poolName.endsWith(".yml") ? poolName : poolName + ".yml";
        SpawnPoolsConfigFields pool = SpawnPoolsConfig.getConfigFields(poolFilename);
        if (pool == null) {
            Logger.warn("[ChunkPasteTask] Could not find spawn pool " + poolFilename);
            return;
        }
        String bossFilename = SpawnPoolsConfig.pickBossFilename(pool);
        if (bossFilename == null) {
            Logger.warn("[ChunkPasteTask] Spawn pool " + poolFilename + " has no entries");
            return;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) {
            EliteMobs.Spawn(loc, bossFilename);
            Logger.info("[ChunkPasteTask] Spawned EliteMob boss from pool: " + bossFilename + " at " + loc);
        } else {
            String cleanName = bossFilename.replace(".yml", "").toUpperCase(Locale.ROOT);
            try {
                EntityType type = EntityType.valueOf(cleanName);
                spawnVanillaMob(loc, type.name());
            } catch (Exception ignored) {
                Logger.warn("[ChunkPasteTask] EliteMobs not enabled for boss " + bossFilename + " from pool " + poolFilename);
            }
        }
    }

    private void restoreNormalSign(Block block, SignTextParser.ParsedSign signData) {
        if (!(block.getState() instanceof Sign sign)) return;

        SignSide front = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            front.setLine(i, signData.getFrontLine(i));
        }
        front.setColor(signData.frontColor());
        front.setGlowingText(signData.frontGlowing());

        SignSide back = sign.getSide(Side.BACK);
        for (int i = 0; i < 4; i++) {
            back.setLine(i, signData.getBackLine(i));
        }
        back.setColor(signData.backColor());
        back.setGlowingText(signData.backGlowing());

        if (signData.isWaxed()) {
            sign.setWaxed(true);
        }

        sign.update(true, false);
        Logger.info("[ChunkPasteTask] Restored sign at (" + block.getX() + "," + block.getY() + "," + block.getZ() + "): front[0]='" + signData.getFrontLine(0) + "', front[1]='" + signData.getFrontLine(1) + "'");
    }

    private void fillContainer(Container container, StructureBlockEntry entry) {
        ChestContents contents = session.getChestContents(container.getType() == Material.BARREL);
        if (contents == null) return;

        contents.rollChestContents(container);
        ChestFillEvent event = new ChestFillEvent(container, contents.getTreasureConfigFields().getFilename());
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            container.update(true, false);
        }
    }

    private void spawnEntity(StructureEntityEntry entry) {
        // If this entity is a 3D prop placeholder (FreeMinecraftModels) and FMM is not installed,
        // do not spawn the armor stand at all to prevent ghost collisions and suffocating nearby mobs.
        if (EntityNbtApplier.isPropModel(entry.entity().nbt()) && !Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels")) {
            Logger.info("[ChunkPasteTask] Skipped prop ArmorStand spawn (FreeMinecraftModels not active): " + entry.entity().entityId() + " at (" + entry.worldX() + "," + entry.worldY() + "," + entry.worldZ() + ")");
            return;
        }

        Location loc = new Location(world, entry.worldX(), entry.worldY(), entry.worldZ(), entry.entity().yaw(), entry.entity().pitch());
        String rawId = entry.entity().entityId();
        if (rawId == null || rawId.isBlank()) return;

        String cleanId = rawId.replace("minecraft:", "").toUpperCase(Locale.ROOT);
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(cleanId);
        } catch (IllegalArgumentException e) {
            Logger.warn("Unknown entity type for schematic spawn: " + rawId);
            return;
        }

        try {
            Class<? extends Entity> entityClass = entityType.getEntityClass();
            if (entityClass != null) {
                world.spawn(loc, entityClass, entity -> {
                    EntityNbtApplier.applyNbt(entity, entry.entity().nbt());
                    Logger.info("[ChunkPasteTask] Spawned entity " + entityType + " at " + loc + " with NBT applied");
                });
            } else {
                Entity entity = world.spawnEntity(loc, entityType);
                EntityNbtApplier.applyNbt(entity, entry.entity().nbt());
                Logger.info("[ChunkPasteTask] Spawned entity " + entityType + " at " + loc + " with NBT applied (fallback)");
            }
        } catch (Exception e) {
            try {
                Entity entity = world.spawnEntity(loc, entityType);
                EntityNbtApplier.applyNbt(entity, entry.entity().nbt());
                Logger.info("[ChunkPasteTask] Spawned entity " + entityType + " at " + loc + " with NBT applied (catch fallback)");
            } catch (Exception ex) {
                Logger.warn("[ChunkPasteTask] Failed to spawn entity " + rawId + " at " + loc + ": " + ex.getMessage());
            }
        }
    }
}
