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

    private final List<StructureBlockEntry> signEntries = new ArrayList<>();
    private final List<StructureBlockEntry> containerEntries = new ArrayList<>();

    private int phase = 0;
    private int blockCursor = 0;
    private int signCursor = 0;
    private int containerCursor = 0;
    private int entityCursor = 0;
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
        this.blocks = blocks != null ? blocks : Collections.emptyList();
        this.entities = entities != null ? entities : Collections.emptyList();
        this.pedestalMaterialProvider = pedestalMaterialProvider;

        // Pre-index only signs and containers so we never call Block#getState() on normal blocks
        for (StructureBlockEntry entry : this.blocks) {
            StructureBlock sb = entry.block();
            if (sb == null || sb.blockData() == null) continue;
            if (sb.isSign()) {
                signEntries.add(entry);
            }
            Material mat = sb.blockData().getMaterial();
            if (isContainerMaterial(mat)) {
                containerEntries.add(entry);
            }
        }
    }

    private static boolean isContainerMaterial(Material mat) {
        return mat == Material.CHEST || mat == Material.TRAPPED_CHEST || mat == Material.BARREL
                || (mat.name().endsWith("SHULKER_BOX") && !mat.name().startsWith("LEGACY_"));
    }

    @Override
    public void run() {
        if (completed) return;
        if (session.isCancelled()) {
            completed = true;
            session.onChunkComplete(chunkX, chunkZ);
            return;
        }

        long deadline = System.nanoTime() + session.getMaxNanosPerTick();

        try {
            // Phase 0: Paste blocks
            if (phase == 0) {
                while (blockCursor < blocks.size()) {
                    try {
                        pasteSingleBlock(blocks.get(blockCursor));
                    } catch (Throwable t) {
                        Logger.warn("Failed to paste block in chunk (" + chunkX + "," + chunkZ + "): " + t.getMessage());
                    }
                    blockCursor++;
                    if (System.nanoTime() >= deadline && blockCursor < blocks.size()) {
                        PlatformScheduler.runRegionLater(world, chunkX, chunkZ, this, 1L);
                        return;
                    }
                }
                phase = 1;
                if (System.nanoTime() >= deadline && (!signEntries.isEmpty() || !containerEntries.isEmpty() || !entities.isEmpty())) {
                    PlatformScheduler.runRegionLater(world, chunkX, chunkZ, this, 1L);
                    return;
                }
            }

            // Phase 1: Process signs (markers & normal text signs)
            if (phase == 1) {
                while (signCursor < signEntries.size()) {
                    try {
                        processSignEntry(signEntries.get(signCursor));
                    } catch (Throwable t) {
                        Logger.warn("Failed to process sign in chunk (" + chunkX + "," + chunkZ + "): " + t.getMessage());
                    }
                    signCursor++;
                    if (System.nanoTime() >= deadline && signCursor < signEntries.size()) {
                        PlatformScheduler.runRegionLater(world, chunkX, chunkZ, this, 1L);
                        return;
                    }
                }
                phase = 2;
                if (System.nanoTime() >= deadline && (!containerEntries.isEmpty() || !entities.isEmpty())) {
                    PlatformScheduler.runRegionLater(world, chunkX, chunkZ, this, 1L);
                    return;
                }
            }

            // Phase 2: Fill containers (chests, barrels, shulker boxes)
            if (phase == 2) {
                while (containerCursor < containerEntries.size()) {
                    try {
                        StructureBlockEntry entry = containerEntries.get(containerCursor);
                        Block destination = world.getBlockAt(entry.worldX(), entry.worldY(), entry.worldZ());
                        if (destination.getState() instanceof Container container) {
                            fillContainer(container, entry);
                        }
                    } catch (Throwable t) {
                        Logger.warn("Failed to fill container in chunk (" + chunkX + "," + chunkZ + "): " + t.getMessage());
                    }
                    containerCursor++;
                    if (System.nanoTime() >= deadline && containerCursor < containerEntries.size()) {
                        PlatformScheduler.runRegionLater(world, chunkX, chunkZ, this, 1L);
                        return;
                    }
                }
                phase = 3;
                if (System.nanoTime() >= deadline && !entities.isEmpty()) {
                    PlatformScheduler.runRegionLater(world, chunkX, chunkZ, this, 1L);
                    return;
                }
            }

            // Phase 3: Spawn entities
            if (phase == 3) {
                while (entityCursor < entities.size()) {
                    try {
                        spawnEntity(entities.get(entityCursor));
                    } catch (Throwable t) {
                        Logger.warn("Failed to spawn entity in chunk (" + chunkX + "," + chunkZ + "): " + t.getMessage());
                    }
                    entityCursor++;
                    if (System.nanoTime() >= deadline && entityCursor < entities.size()) {
                        PlatformScheduler.runRegionLater(world, chunkX, chunkZ, this, 1L);
                        return;
                    }
                }
                phase = 4;
            }
        } catch (Throwable fatal) {
            Logger.warn("Unexpected error during chunk paste (" + chunkX + "," + chunkZ + "): " + fatal.getMessage());
        }

        completed = true;
        session.onChunkComplete(chunkX, chunkZ);
    }

    private void pasteSingleBlock(StructureBlockEntry entry) {
        Block destination = world.getBlockAt(entry.worldX(), entry.worldY(), entry.worldZ());
        org.bukkit.block.data.BlockData newBlockData = entry.block().blockData();
        Material newMaterial = newBlockData.getMaterial();

        // Barrier rule: barriers protect existing terrain, do not place
        if (newMaterial == Material.BARRIER) {
            return;
        }

        Material currentType = destination.getType();
        // Air optimization: if both are air, skip
        if (newMaterial.isAir() && currentType.isAir()) {
            return;
        }

        // Bedrock rule: bedrock becomes the pedestal material
        if (newMaterial == Material.BEDROCK) {
            if (!currentType.isSolid()) {
                boolean isGround = !world.getBlockAt(entry.worldX(), entry.worldY() + 1, entry.worldZ()).getType().isSolid();
                Material pedestal = pedestalMaterialProvider != null ? pedestalMaterialProvider.apply(isGround) : Material.COBBLESTONE;
                if (currentType != pedestal) {
                    destination.setType(pedestal, false);
                }
            }
            return;
        }

        // Special marker sign rule: marker signs turn into AIR, then spawn their target
        if (entry.block().isSign()) {
            if (isMarkerSign(entry.block().parsedSign())) {
                if (!currentType.isAir()) {
                    destination.setType(Material.AIR, false);
                }
                return;
            }
            // Empty standing sign rule (Plan A): standing signs without any text are forgotten markers, clear to AIR
            if (entry.block().parsedSign() != null && !entry.block().parsedSign().hasText()
                    && !newMaterial.name().contains("WALL")) {
                if (!currentType.isAir()) {
                    destination.setType(Material.AIR, false);
                }
                return;
            }
        }

        // Skip redundant block data writes to avoid unnecessary chunk dirtying
        if (currentType == newMaterial && destination.getBlockData().equals(newBlockData)) {
            return;
        }

        // Normal block placement without physics
        destination.setBlockData(newBlockData, false);
    }

    private void processSignEntry(StructureBlockEntry entry) {
        Block destination = world.getBlockAt(entry.worldX(), entry.worldY(), entry.worldZ());
        StructureBlock structBlock = entry.block();
        SignTextParser.ParsedSign sign = structBlock.parsedSign();
        if (isMarkerSign(sign)) {
            handleMarkerSign(entry, sign);
        } else if (sign != null && !sign.hasText() && !structBlock.blockData().getMaterial().name().contains("WALL")) {
            if (!destination.getType().isAir()) {
                destination.setType(Material.AIR, false);
            }
        } else {
            restoreNormalSign(destination, sign);
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
                containerEntries.add(entry);
                return;
            }
            if (lower.contains("[trapped_chest]")) {
                world.getBlockAt(bx, by, bz).setType(Material.TRAPPED_CHEST, false);
                containerEntries.add(entry);
                return;
            }
        }
    }

    private void spawnVanillaMob(Location loc, String entityName) {
        try {
            String cleanName = entityName.replace("\"", "").trim().toUpperCase(Locale.ROOT);
            if (cleanName.equals("WITHER_CRYSTAL") || cleanName.equals("ENDER_CRYSTAL")) {
                cleanName = "END_CRYSTAL";
            }
            EntityType type = EntityType.valueOf(cleanName);
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
