package com.magmaguy.betterstructures.paste;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.Skull;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.UUID;

/**
 * 図面のブロックエンティティの NBT を、公開の BlockState API で世界のブロックへ書く
 * （スポナー・コマンドブロック・ジュークボックス・頭・旗・書見台・容器の中身）。
 * リフレクションも、サーバー内部の型も使わない。
 *
 * <p>第一案（チャンクごとにバニラ構造物 NBT を組み立てて {@code Structure#place}）は、MockBukkit に
 * StructureManager が無く試験で固定できないこと、Folia 上での {@code Structure#place} の挙動が未確認なことから
 * 採らず、章の第二案（型ごとの公開 API）を採った。
 *
 * <p>呼び出すスレッド: そのブロックを所有するリージョンのスレッドだけ。
 */
public final class BlockEntityWriter {

    private BlockEntityWriter() {
    }

    /** 図面の NBT を書く対象の材質か。 */
    public static boolean handles(Material material) {
        if (material == null) return false;
        String name = material.name();
        if (name.startsWith("LEGACY_")) return false;
        return material == Material.SPAWNER
                || name.endsWith("COMMAND_BLOCK")
                || material == Material.JUKEBOX
                || material == Material.LECTERN
                || name.endsWith("_HEAD") || name.endsWith("_SKULL")
                || name.endsWith("_BANNER")
                || isContainer(material);
    }

    private static boolean isContainer(Material material) {
        String name = material.name();
        return material == Material.CHEST || material == Material.TRAPPED_CHEST || material == Material.BARREL
                || name.endsWith("SHULKER_BOX")
                || material == Material.HOPPER || material == Material.DISPENSER || material == Material.DROPPER
                || material == Material.FURNACE || material == Material.BLAST_FURNACE || material == Material.SMOKER
                || material == Material.BREWING_STAND;
    }

    /**
     * ブロックへ NBT を書く。
     *
     * @param restoreItems 容器の中身を図面の NBT から復元するか（戦利品で埋める容器は false）
     * @return 何かを書いたか
     */
    public static boolean write(Block block, SimpleNbt.CompoundTag nbt, boolean restoreItems) {
        if (nbt == null) return false;
        BlockState state = block.getState();
        boolean changed = false;

        if (state instanceof CreatureSpawner spawner) {
            changed = writeSpawner(spawner, nbt);
        } else if (state instanceof CommandBlock commandBlock) {
            String command = nbt.getString("Command", null);
            if (command != null) {
                commandBlock.setCommand(command);
                changed = true;
            }
        } else if (state instanceof Jukebox jukebox) {
            ItemStack record = item(nbt.getCompound("RecordItem"));
            if (record != null) {
                jukebox.setRecord(record);
                changed = true;
            }
        } else if (state instanceof Skull skull) {
            PlayerProfile profile = profile(nbt);
            if (profile != null) {
                skull.setPlayerProfile(profile);
                changed = true;
            }
        } else if (state instanceof Banner banner) {
            changed = writeBanner(banner, nbt);
        } else if (state instanceof Lectern lectern) {
            ItemStack book = item(nbt.getCompound("Book"));
            if (book != null) {
                lectern.getSnapshotInventory().setItem(0, book);
                int page = nbt.getInt("Page", 0);
                lectern.setPage(Math.max(0, page));
                changed = true;
            }
        } else if (state instanceof Container container && restoreItems) {
            changed = writeItems(container.getSnapshotInventory(), nbt.getList("Items"));
        }

        if (changed) state.update(true, false);
        return changed;
    }

    private static boolean writeSpawner(CreatureSpawner spawner, SimpleNbt.CompoundTag nbt) {
        boolean changed = false;
        String id = null;
        SimpleNbt.CompoundTag spawnData = nbt.getCompound("SpawnData");
        if (spawnData != null) {
            SimpleNbt.CompoundTag entity = spawnData.getCompound("entity");
            id = (entity != null ? entity : spawnData).getString("id", null);
        }
        if (id == null) id = nbt.getString("EntityId", null);
        if (id != null) {
            NamespacedKey key = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
            EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
            if (type != null) {
                spawner.setSpawnedType(type);
                changed = true;
            }
        }
        if (nbt.containsKey("Delay")) {
            spawner.setDelay(nbt.getShort("Delay", (short) 20));
            changed = true;
        }
        if (nbt.containsKey("RequiredPlayerRange")) {
            spawner.setRequiredPlayerRange(nbt.getShort("RequiredPlayerRange", (short) 16));
            changed = true;
        }
        if (nbt.containsKey("SpawnCount")) {
            spawner.setSpawnCount(nbt.getShort("SpawnCount", (short) 4));
            changed = true;
        }
        if (nbt.containsKey("MaxNearbyEntities")) {
            spawner.setMaxNearbyEntities(nbt.getShort("MaxNearbyEntities", (short) 6));
            changed = true;
        }
        if (nbt.containsKey("SpawnRange")) {
            spawner.setSpawnRange(nbt.getShort("SpawnRange", (short) 4));
            changed = true;
        }
        if (nbt.containsKey("MinSpawnDelay")) {
            spawner.setMinSpawnDelay(nbt.getShort("MinSpawnDelay", (short) 200));
            changed = true;
        }
        if (nbt.containsKey("MaxSpawnDelay")) {
            spawner.setMaxSpawnDelay(nbt.getShort("MaxSpawnDelay", (short) 800));
            changed = true;
        }
        return changed;
    }

    private static boolean writeBanner(Banner banner, SimpleNbt.CompoundTag nbt) {
        SimpleNbt.ListTag patterns = nbt.getList("patterns");
        if (patterns == null) return false;
        boolean changed = false;
        for (SimpleNbt.Tag tag : patterns) {
            if (!(tag instanceof SimpleNbt.CompoundTag entry)) continue;
            String patternId = entry.getString("pattern", null);
            String colorName = entry.getString("color", null);
            if (patternId == null || colorName == null) continue;
            NamespacedKey key = NamespacedKey.fromString(patternId.toLowerCase(Locale.ROOT));
            PatternType type = key == null ? null : Registry.BANNER_PATTERN.get(key);
            DyeColor color;
            try {
                color = DyeColor.valueOf(colorName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (type == null) continue;
            banner.addPattern(new Pattern(color, type));
            changed = true;
        }
        return changed;
    }

    private static boolean writeItems(Inventory inventory, SimpleNbt.ListTag items) {
        if (items == null || inventory == null) return false;
        boolean changed = false;
        for (SimpleNbt.Tag tag : items) {
            if (!(tag instanceof SimpleNbt.CompoundTag entry)) continue;
            ItemStack stack = item(entry);
            int slot = entry.getByte("Slot", (byte) -1) & 0xFF;
            if (entry.containsKey("Slot") && entry.get("Slot") instanceof SimpleNbt.IntTag intSlot) {
                slot = intSlot.asInt();
            }
            if (stack == null || slot < 0 || slot >= inventory.getSize()) continue;
            inventory.setItem(slot, stack);
            changed = true;
        }
        return changed;
    }

    /**
     * 図面の NBT のアイテム（{@code id} と {@code Count}/{@code count}）から ItemStack を作る。
     * 個別のコンポーネント（名前・エンチャント・本の中身等）は復元しない。
     */
    static ItemStack item(SimpleNbt.CompoundTag tag) {
        if (tag == null) return null;
        String id = tag.getString("id", null);
        if (id == null) return null;
        Material material = Material.matchMaterial(id);
        if (material == null || material.isAir() || !material.isItem()) return null;
        int count = tag.containsKey("count") ? tag.getInt("count", 1) : tag.getByte("Count", (byte) 1);
        if (count < 1) count = 1;
        return new ItemStack(material, Math.min(count, material.getMaxStackSize()));
    }

    /** 頭の持ち主の情報（新旧どちらの形でも読む）。テクスチャを持つ時だけ作る。 */
    static PlayerProfile profile(SimpleNbt.CompoundTag nbt) {
        SimpleNbt.Tag raw = nbt.get("profile");
        if (raw == null) raw = nbt.get("SkullOwner");
        if (raw instanceof SimpleNbt.StringTag name) {
            return name.asString().isEmpty() ? null : Bukkit.createProfile(name.asString());
        }
        if (!(raw instanceof SimpleNbt.CompoundTag owner)) return null;

        String name = owner.getString("name", owner.getString("Name", null));
        UUID id = null;
        SimpleNbt.Tag idTag = owner.get("id") != null ? owner.get("id") : owner.get("Id");
        if (idTag instanceof SimpleNbt.IntArrayTag array && array.asIntArray().length == 4) {
            int[] v = array.asIntArray();
            id = new UUID(((long) v[0] << 32) | (v[1] & 0xFFFFFFFFL), ((long) v[2] << 32) | (v[3] & 0xFFFFFFFFL));
        } else if (idTag instanceof SimpleNbt.StringTag text) {
            try {
                id = UUID.fromString(text.asString());
            } catch (IllegalArgumentException ignored) {
            }
        }

        String texture = null;
        SimpleNbt.ListTag properties = owner.getList("properties");
        if (properties != null) {
            for (SimpleNbt.Tag tag : properties) {
                if (tag instanceof SimpleNbt.CompoundTag property && "textures".equals(property.getString("name", ""))) {
                    texture = property.getString("value", null);
                }
            }
        }
        if (texture == null) {
            SimpleNbt.CompoundTag legacy = owner.getCompound("Properties");
            SimpleNbt.ListTag textures = legacy == null ? null : legacy.getList("textures");
            if (textures != null && textures.size() > 0 && textures.get(0) instanceof SimpleNbt.CompoundTag first) {
                texture = first.getString("Value", null);
            }
        }
        if (texture == null && (name == null || name.isEmpty())) return null;

        if (id == null) {
            String seed = texture != null ? texture : name;
            id = UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        PlayerProfile profile = Bukkit.createProfile(id, name == null || name.isEmpty() ? null : name);
        if (texture != null) profile.setProperty(new ProfileProperty("textures", texture));
        return profile;
    }
}
