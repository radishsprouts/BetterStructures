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
import com.magmaguy.betterstructures.thirdparty.BetterModelBridge;
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
                    Logger.warn("[spawn] sign at (" + bx + "," + by + "," + bz + ") has no entity type specified!");
                }
                return;
            }

            // [pool:...] Spawn pool
            if (lower.contains("[pool:")) {
                String poolName = SpawnPoolsConfig.extractPoolName(line);
                if (poolName != null) {
                    spawnPoolMob(loc, poolName);
                } else {
                    Logger.warn("Could not extract pool name from: " + line);
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
                }
                return;
            }

            // [mythicmobs]
            if (lower.contains("[mythicmobs]")) {
                String mob = (i + 1 < lines.size()) ? lines.get(i + 1).trim() : "";
                String level = (i + 2 < lines.size()) ? lines.get(i + 2).trim() : "";
                if (!mob.isEmpty()) {
                    MythicMobs.Spawn(loc, level.isEmpty() ? mob : mob + ":" + level);
                }
                return;
            }

            // [custom]
            if (lower.contains("[custom]")) {
                List<String> args = new ArrayList<>();
                for (int j = i + 1; j < lines.size(); j++) args.add(lines.get(j).trim());
                Bukkit.getPluginManager().callEvent(new BuildCustomMarkerEvent(session.getFitter(), loc, args));
                return;
            }

            // [chest] & [trapped_chest]
            if (lower.contains("[chest]")) {
                world.getBlockAt(bx, by, bz).setType(Material.CHEST, false);
                return;
            }
            if (lower.contains("[trapped_chest]")) {
                world.getBlockAt(bx, by, bz).setType(Material.TRAPPED_CHEST, false);
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
                });
            } else {
                Entity entity = world.spawnEntity(loc, type);
                entity.setPersistent(true);
                if (entity instanceof LivingEntity living) {
                    living.setRemoveWhenFarAway(false);
                    living.setNoDamageTicks(60);
                    living.setFallDistance(0);
                }
            }
        } catch (Exception e) {
            Logger.warn("Failed to spawn entity from sign: " + entityName + " (" + e.getMessage() + ")");
        }
    }

    private void spawnPoolMob(Location loc, String poolName) {
        String poolFilename = poolName.endsWith(".yml") ? poolName : poolName + ".yml";
        SpawnPoolsConfigFields pool = SpawnPoolsConfig.getConfigFields(poolFilename);
        if (pool == null) {
            Logger.warn("Could not find spawn pool " + poolFilename);
            return;
        }
        String bossFilename = SpawnPoolsConfig.pickBossFilename(pool);
        if (bossFilename == null) {
            Logger.warn("Spawn pool " + poolFilename + " has no entries");
            return;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) {
            EliteMobs.Spawn(loc, bossFilename);
        } else {
            String cleanName = bossFilename.replace(".yml", "").toUpperCase(Locale.ROOT);
            try {
                EntityType type = EntityType.valueOf(cleanName);
                spawnVanillaMob(loc, type.name());
            } catch (Exception ignored) {
                Logger.warn("EliteMobs not enabled for boss " + bossFilename + " from pool " + poolFilename);
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
        Location loc = new Location(world, entry.worldX(), entry.worldY(), entry.worldZ(), entry.entity().yaw(), entry.entity().pitch());

        // Handle 3D prop models (BetterModel / FreeMinecraftModels)
        if (EntityNbtApplier.isPropModel(entry.entity().nbt())) {
            String propName = EntityNbtApplier.getPropModelName(entry.entity().nbt());
            if (BetterModelBridge.isEnabled() && propName != null) {
                BetterModelBridge.spawnProp(loc, propName);
            }
            // If FreeMinecraftModels is not installed, never spawn the vanilla ArmorStand to prevent collisions
            if (!Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels")) {
                return;
            }
        }

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
                });
            } else {
                Entity entity = world.spawnEntity(loc, entityType);
                EntityNbtApplier.applyNbt(entity, entry.entity().nbt());
            }
        } catch (Exception e) {
            try {
                Entity entity = world.spawnEntity(loc, entityType);
                EntityNbtApplier.applyNbt(entity, entry.entity().nbt());
            } catch (Exception ex) {
                Logger.warn("Failed to spawn entity " + rawId + " at " + loc + ": " + ex.getMessage());
            }
        }
    }
}
