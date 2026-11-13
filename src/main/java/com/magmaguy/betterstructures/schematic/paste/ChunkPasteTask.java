package com.magmaguy.betterstructures.schematic.paste;

import com.magmaguy.betterstructures.api.BuildCustomMarkerEvent;
import com.magmaguy.betterstructures.api.ChestFillEvent;
import com.magmaguy.betterstructures.chests.ChestContents;
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
        String line1 = sign.getFrontLine(0).toLowerCase(Locale.ROOT);
        if (line1.contains("[spawn]") || line1.contains("[elitemobs]")
                || line1.contains("[mythicmobs]") || line1.contains("[custom]")) {
            return true;
        }
        for (int i = 0; i < 4; i++) {
            String line = sign.getFrontLine(i).toLowerCase(Locale.ROOT);
            if (line.contains("[chest]") || line.contains("[trapped_chest]")) return true;
        }
        return false;
    }

    private void handleMarkerSign(StructureBlockEntry entry, SignTextParser.ParsedSign sign) {
        Location loc = new Location(world, entry.worldX() + 0.5, entry.worldY(), entry.worldZ() + 0.5);
        // Ensure marker block is air
        world.getBlockAt(entry.worldX(), entry.worldY(), entry.worldZ()).setType(Material.AIR, false);

        String line1 = sign.getFrontLine(0).toLowerCase(Locale.ROOT).trim();
        Logger.info("[ChunkPasteTask] Processing marker sign at (" + entry.worldX() + "," + entry.worldY() + "," + entry.worldZ() + "): line1='" + line1 + "', line2='" + sign.getFrontLine(1) + "'");

        if (line1.contains("[spawn]")) {
            String line2 = sign.getFrontLine(1).toUpperCase(Locale.ROOT).replace("\"", "").trim();
            if (!line2.isEmpty()) {
                try {
                    EntityType type = EntityType.valueOf(line2.equalsIgnoreCase("WITHER_CRYSTAL") ? "END_CRYSTAL" : line2);
                    Entity entity = world.spawnEntity(loc, type);
                    entity.setPersistent(true);
                    if (entity instanceof LivingEntity living) {
                        living.setRemoveWhenFarAway(false);
                    }
                    Logger.info("[ChunkPasteTask] Spawned mob " + type + " at " + loc);
                } catch (Exception e) {
                    Logger.warn("[ChunkPasteTask] Failed to spawn entity from sign: " + line2 + " (" + e.getMessage() + ")");
                }
            }
        } else if (line1.contains("[elitemobs]")) {
            StringBuilder bossFile = new StringBuilder();
            for (int i = 1; i < 4; i++) {
                String line = sign.getFrontLine(i).trim();
                if (!line.isEmpty()) bossFile.append(line);
            }
            if (bossFile.length() > 0) {
                EliteMobs.Spawn(loc, bossFile.toString());
                Logger.info("[ChunkPasteTask] Spawned EliteMob " + bossFile + " at " + loc);
            }
        } else if (line1.contains("[mythicmobs]")) {
            String mob = sign.getFrontLine(1).trim();
            String level = sign.getFrontLine(2).trim();
            if (!mob.isEmpty()) {
                MythicMobs.Spawn(loc, level.isEmpty() ? mob : mob + ":" + level);
                Logger.info("[ChunkPasteTask] Spawned MythicMob " + mob + " at " + loc);
            }
        } else if (line1.contains("[custom]")) {
            List<String> args = new ArrayList<>();
            for (int i = 1; i < 4; i++) args.add(sign.getFrontLine(i).trim());
            Bukkit.getPluginManager().callEvent(new BuildCustomMarkerEvent(session.getFitter(), loc, args));
            Logger.info("[ChunkPasteTask] Dispatched custom marker at " + loc + ": " + args);
        } else {
            // Chest markers
            for (int i = 0; i < 4; i++) {
                String line = sign.getFrontLine(i).toLowerCase(Locale.ROOT);
                if (line.contains("[chest]")) {
                    world.getBlockAt(entry.worldX(), entry.worldY(), entry.worldZ()).setType(Material.CHEST, false);
                    Logger.info("[ChunkPasteTask] Replaced sign with chest at (" + entry.worldX() + "," + entry.worldY() + "," + entry.worldZ() + ")");
                    break;
                } else if (line.contains("[trapped_chest]")) {
                    world.getBlockAt(entry.worldX(), entry.worldY(), entry.worldZ()).setType(Material.TRAPPED_CHEST, false);
                    Logger.info("[ChunkPasteTask] Replaced sign with trapped_chest at (" + entry.worldX() + "," + entry.worldY() + "," + entry.worldZ() + ")");
                    break;
                }
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
