package com.magmaguy.betterstructures.schematic.entity;

import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.EulerAngle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Applies schematic NBT attributes, PersistentDataContainer (BukkitValues), and entity properties
 * to Bukkit entities upon spawning (e.g. ArmorStands, props, mobs).
 */
public final class EntityNbtApplier {

    private EntityNbtApplier() {
    }

    /**
     * Checks whether the schematic entity NBT is a 3D prop placeholder (FreeMinecraftModels).
     */
    public static boolean isPropModel(@Nullable SimpleNbt.CompoundTag entityNbt) {
        if (entityNbt == null) return false;
        SimpleNbt.CompoundTag dataTag = entityNbt.getCompound("Data");
        SimpleNbt.CompoundTag tag = dataTag != null ? dataTag : entityNbt;
        SimpleNbt.CompoundTag bukkitValues = tag.getCompound("BukkitValues");
        return bukkitValues != null &&
                (bukkitValues.containsKey("freeminecraftmodels:entity") || bukkitValues.containsKey("freeminecraftmodels:prop"));
    }

    /**
     * Applies all stored NBT properties from a schematic entity tag to a Bukkit entity.
     *
     * @param entity    The spawned Bukkit entity.
     * @param entityNbt The raw or wrapped CompoundTag from the schematic.
     */
    public static void applyNbt(@NotNull Entity entity, @Nullable SimpleNbt.CompoundTag entityNbt) {
        if (entityNbt == null) return;

        // Unwrap Sponge schematic "Data" tag if present
        SimpleNbt.CompoundTag dataTag = entityNbt.getCompound("Data");
        SimpleNbt.CompoundTag tag = dataTag != null ? dataTag : entityNbt;

        // 1. PersistentDataContainer (PDC / BukkitValues) - Critical for BetterModel / FreeMinecraftModels
        applyBukkitValues(entity, tag);

        // 2. Common Entity attributes
        applyCommonEntityAttributes(entity, tag);

        // 3. LivingEntity attributes
        if (entity instanceof LivingEntity living) {
            applyLivingEntityAttributes(living, tag);
        }

        // 4. ArmorStand specific attributes
        if (entity instanceof ArmorStand armorStand) {
            applyArmorStandAttributes(armorStand, tag);
        }
    }

    private static void applyBukkitValues(Entity entity, SimpleNbt.CompoundTag tag) {
        SimpleNbt.CompoundTag bukkitValues = tag.getCompound("BukkitValues");
        if (bukkitValues == null) return;

        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        for (String rawKey : bukkitValues.keySet()) {
            SimpleNbt.Tag valTag = bukkitValues.get(rawKey);
            if (valTag == null) continue;

            NamespacedKey namespacedKey = parseNamespacedKey(rawKey);
            if (namespacedKey == null) continue;

            if (valTag instanceof SimpleNbt.StringTag stringTag) {
                pdc.set(namespacedKey, PersistentDataType.STRING, stringTag.asString());
                Logger.info("[EntityNbtApplier] Set PDC (String) " + namespacedKey + " = " + stringTag.asString() + " on " + entity.getType());
            } else if (valTag instanceof SimpleNbt.ByteTag byteTag) {
                pdc.set(namespacedKey, PersistentDataType.BYTE, byteTag.asByte());
                Logger.info("[EntityNbtApplier] Set PDC (Byte) " + namespacedKey + " = " + byteTag.asByte() + " on " + entity.getType());
            } else if (valTag instanceof SimpleNbt.IntTag intTag) {
                pdc.set(namespacedKey, PersistentDataType.INTEGER, intTag.asInt());
                Logger.info("[EntityNbtApplier] Set PDC (Int) " + namespacedKey + " = " + intTag.asInt() + " on " + entity.getType());
            } else if (valTag instanceof SimpleNbt.LongTag longTag) {
                pdc.set(namespacedKey, PersistentDataType.LONG, longTag.asLong());
                Logger.info("[EntityNbtApplier] Set PDC (Long) " + namespacedKey + " = " + longTag.asLong() + " on " + entity.getType());
            } else if (valTag instanceof SimpleNbt.DoubleTag doubleTag) {
                pdc.set(namespacedKey, PersistentDataType.DOUBLE, doubleTag.asDouble());
                Logger.info("[EntityNbtApplier] Set PDC (Double) " + namespacedKey + " = " + doubleTag.asDouble() + " on " + entity.getType());
            } else if (valTag instanceof SimpleNbt.FloatTag floatTag) {
                pdc.set(namespacedKey, PersistentDataType.FLOAT, floatTag.asFloat());
                Logger.info("[EntityNbtApplier] Set PDC (Float) " + namespacedKey + " = " + floatTag.asFloat() + " on " + entity.getType());
            } else {
                pdc.set(namespacedKey, PersistentDataType.STRING, valTag.getValue().toString());
                Logger.info("[EntityNbtApplier] Set PDC (Fallback) " + namespacedKey + " = " + valTag.getValue() + " on " + entity.getType());
            }
        }
    }

    private static NamespacedKey parseNamespacedKey(String rawKey) {
        try {
            if (rawKey.contains(":")) {
                String[] parts = rawKey.split(":", 2);
                return new NamespacedKey(parts[0].toLowerCase(Locale.ROOT), parts[1].toLowerCase(Locale.ROOT));
            } else {
                return NamespacedKey.minecraft(rawKey.toLowerCase(Locale.ROOT));
            }
        } catch (Exception e) {
            Logger.warn("Invalid NamespacedKey in entity PDC: " + rawKey);
            return null;
        }
    }

    private static void applyCommonEntityAttributes(Entity entity, SimpleNbt.CompoundTag tag) {
        // CustomName
        String customName = tag.getString("CustomName", "");
        if (!customName.isBlank()) {
            entity.setCustomName(SignTextParser.extractTextFromJsonString(customName));
        }

        // CustomNameVisible
        if (tag.containsKey("CustomNameVisible")) {
            entity.setCustomNameVisible(tag.getByte("CustomNameVisible", (byte) 0) != 0);
        }

        // Glowing
        if (tag.containsKey("Glowing")) {
            entity.setGlowing(tag.getByte("Glowing", (byte) 0) != 0);
        }

        // Silent
        if (tag.containsKey("Silent")) {
            entity.setSilent(tag.getByte("Silent", (byte) 0) != 0);
        }

        // Invulnerable
        if (tag.containsKey("Invulnerable")) {
            entity.setInvulnerable(tag.getByte("Invulnerable", (byte) 0) != 0);
        }

        // Gravity (NoGravity tag)
        if (tag.containsKey("NoGravity")) {
            entity.setGravity(tag.getByte("NoGravity", (byte) 0) == 0);
        }

        // Persistence
        entity.setPersistent(true);
    }

    private static void applyLivingEntityAttributes(LivingEntity living, SimpleNbt.CompoundTag tag) {
        living.setRemoveWhenFarAway(false);

        // Equipment items (ArmorItems & HandItems)
        EntityEquipment equipment = living.getEquipment();
        if (equipment != null) {
            SimpleNbt.ListTag handItems = tag.getList("HandItems");
            if (handItems != null && handItems.size() >= 2) {
                ItemStack mainHand = parseItemStackTag(handItems.get(0));
                if (mainHand != null) equipment.setItemInMainHand(mainHand);
                ItemStack offHand = parseItemStackTag(handItems.get(1));
                if (offHand != null) equipment.setItemInOffHand(offHand);
            }

            SimpleNbt.ListTag armorItems = tag.getList("ArmorItems");
            if (armorItems != null && armorItems.size() >= 4) {
                ItemStack feet = parseItemStackTag(armorItems.get(0));
                if (feet != null) equipment.setBoots(feet);
                ItemStack legs = parseItemStackTag(armorItems.get(1));
                if (legs != null) equipment.setLeggings(legs);
                ItemStack chest = parseItemStackTag(armorItems.get(2));
                if (chest != null) equipment.setChestplate(chest);
                ItemStack head = parseItemStackTag(armorItems.get(3));
                if (head != null) equipment.setHelmet(head);
            }
        }
    }

    private static void applyArmorStandAttributes(ArmorStand armorStand, SimpleNbt.CompoundTag tag) {
        boolean invisible = tag.containsKey("Invisible") && tag.getByte("Invisible", (byte) 0) != 0;
        boolean small = tag.containsKey("Small") && tag.getByte("Small", (byte) 0) != 0;
        boolean arms = tag.containsKey("ShowArms") && tag.getByte("ShowArms", (byte) 0) != 0;
        boolean noBasePlate = tag.containsKey("NoBasePlate") && tag.getByte("NoBasePlate", (byte) 0) != 0;
        boolean marker = tag.containsKey("Marker") && tag.getByte("Marker", (byte) 0) != 0;

        if (tag.containsKey("Invisible")) {
            armorStand.setVisible(!invisible);
        }
        if (tag.containsKey("Small")) {
            armorStand.setSmall(small);
        }
        if (tag.containsKey("ShowArms")) {
            armorStand.setArms(arms);
        }
        if (tag.containsKey("NoBasePlate")) {
            armorStand.setBasePlate(!noBasePlate);
        }
        if (tag.containsKey("Marker")) {
            armorStand.setMarker(marker);
        }

        // If this ArmorStand is a 3D prop placeholder (has freeminecraftmodels PDC)
        // and FreeMinecraftModels plugin is NOT installed/active,
        // hide the untextured wooden stick figure so it does not obstruct players or look broken.
        SimpleNbt.CompoundTag bukkitValues = tag.getCompound("BukkitValues");
        boolean isPropModel = bukkitValues != null &&
                (bukkitValues.containsKey("freeminecraftmodels:entity") || bukkitValues.containsKey("freeminecraftmodels:prop"));

        if (isPropModel && !Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels")) {
            armorStand.setVisible(false);
            armorStand.setMarker(true);
            armorStand.setBasePlate(false);
            Logger.info("[EntityNbtApplier] Prop ArmorStand hidden (FreeMinecraftModels not active): " + armorStand.getLocation());
        }

        Logger.info("[EntityNbtApplier] ArmorStand attrs: invisible=" + invisible + ", small=" + small + ", arms=" + arms + ", noBasePlate=" + noBasePlate + ", marker=" + marker + ", isProp=" + isPropModel);

        // Pose
        SimpleNbt.CompoundTag pose = tag.getCompound("Pose");
        if (pose != null) {
            EulerAngle head = parseEulerAngle(pose.getList("Head"));
            if (head != null) armorStand.setHeadPose(head);

            EulerAngle body = parseEulerAngle(pose.getList("Body"));
            if (body != null) armorStand.setBodyPose(body);

            EulerAngle leftArm = parseEulerAngle(pose.getList("LeftArm"));
            if (leftArm != null) armorStand.setLeftArmPose(leftArm);

            EulerAngle rightArm = parseEulerAngle(pose.getList("RightArm"));
            if (rightArm != null) armorStand.setRightArmPose(rightArm);

            EulerAngle leftLeg = parseEulerAngle(pose.getList("LeftLeg"));
            if (leftLeg != null) armorStand.setLeftLegPose(leftLeg);

            EulerAngle rightLeg = parseEulerAngle(pose.getList("RightLeg"));
            if (rightLeg != null) armorStand.setRightLegPose(rightLeg);
        }
    }

    private static EulerAngle parseEulerAngle(SimpleNbt.ListTag list) {
        if (list == null || list.size() < 3) return null;
        try {
            double x = ((Number) list.get(0).getValue()).doubleValue();
            double y = ((Number) list.get(1).getValue()).doubleValue();
            double z = ((Number) list.get(2).getValue()).doubleValue();
            return new EulerAngle(Math.toRadians(x), Math.toRadians(y), Math.toRadians(z));
        } catch (Exception e) {
            return null;
        }
    }

    private static ItemStack parseItemStackTag(SimpleNbt.Tag tag) {
        if (!(tag instanceof SimpleNbt.CompoundTag itemTag) || itemTag.keySet().isEmpty()) {
            return null;
        }
        String id = itemTag.getString("id", "");
        if (id.isEmpty()) id = itemTag.getString("Id", "");
        if (id.isEmpty() || id.equalsIgnoreCase("minecraft:air")) {
            return null;
        }

        String materialName = id.replace("minecraft:", "").toUpperCase(Locale.ROOT);
        Material mat = Material.matchMaterial(materialName);
        if (mat == null) return null;

        byte count = itemTag.getByte("Count", (byte) 1);
        if (count <= 0) count = 1;

        return new ItemStack(mat, count);
    }
}
