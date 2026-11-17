package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.schematic.entity.EntityNbtApplier;
import com.magmaguy.betterstructures.schematic.model.StructureEntity;
import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import com.magmaguy.betterstructures.thirdparty.BetterModelBridge;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 図面のエンティティを世界へ置く。額縁・絵画は、位置（{@code Pos} と {@code block_pos}/{@code TileX/Y/Z}）と
 * 向きを回転後の値に書き換えてから {@code EntityFactory} 経由で作る（書き換えないとサーバーが破棄する）。
 *
 * <p>置いたエンティティには PDC でセッションの ID と通し番号を付け、停止をまたぐ再開で二重に作らない（04章 G6）。
 */
public final class EntityPlacer {

    /** 貼り付けで置いたエンティティの印（値は {@code セッションID:通し番号}）。 */
    public static final NamespacedKey PASTE_TAG_KEY = NamespacedKey.fromString("betterstructures:paste_entity");

    // 額縁の Facing: DOWN0 UP1 NORTH2 SOUTH3 WEST4 EAST5
    private static final BlockFace[] FRAME_FACES = {
            BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};
    // 絵画の facing: SOUTH0 WEST1 NORTH2 EAST3
    private static final BlockFace[] PAINTING_FACES = {
            BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST};
    // 時計回りに90度ずつ回した水平の向き
    private static final BlockFace[] CLOCKWISE = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private EntityPlacer() {
    }

    /** 額縁・絵画か（{@code minecraft:} の有無・大小文字を問わない）。 */
    public static boolean isHanging(String entityId) {
        if (entityId == null) return false;
        String id = entityId.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        return id.equals("item_frame") || id.equals("glow_item_frame") || id.equals("painting");
    }

    private static boolean isPainting(String entityId) {
        return entityId != null && entityId.toLowerCase(Locale.ROOT).endsWith("painting");
    }

    /** 時計回りに回す90度の回数（0〜3）。 */
    static int steps(int degrees) {
        return (((degrees % 360) + 360) % 360) / 90;
    }

    /** 水平の向きを時計回りに {@code degrees} 度回す。上下はそのまま。 */
    public static BlockFace rotate(BlockFace face, int degrees) {
        int index = -1;
        for (int i = 0; i < CLOCKWISE.length; i++) {
            if (CLOCKWISE[i] == face) index = i;
        }
        if (index < 0) return face;
        return CLOCKWISE[(index + steps(degrees)) % 4];
    }

    /** 額縁の {@code Facing}（バイト値）を回す。 */
    public static int rotateFrameFacing(int id, int degrees) {
        if (id < 0 || id >= FRAME_FACES.length) return id;
        return indexOf(FRAME_FACES, rotate(FRAME_FACES[id], degrees), id);
    }

    /** 絵画の {@code facing}（バイト値）を回す。 */
    public static int rotatePaintingFacing(int id, int degrees) {
        if (id < 0 || id >= PAINTING_FACES.length) return id;
        return indexOf(PAINTING_FACES, rotate(PAINTING_FACES[id], degrees), id);
    }

    private static int indexOf(BlockFace[] faces, BlockFace face, int fallback) {
        for (int i = 0; i < faces.length; i++) {
            if (faces[i] == face) return i;
        }
        return fallback;
    }

    private static BlockFace faceOf(String entityId, int id) {
        BlockFace[] faces = isPainting(entityId) ? PAINTING_FACES : FRAME_FACES;
        return id >= 0 && id < faces.length ? faces[id] : null;
    }

    /**
     * 額縁・絵画の NBT を、新しい位置と回転後の向きに書き換えた写しを返す（元は変えない）。
     * 純粋な変換なので、サーバー無しで試験できる。
     */
    public static SimpleNbt.CompoundTag adjust(SimpleNbt.CompoundTag nbt, String entityId,
                                               double x, double y, double z, int rotationDegrees) {
        SimpleNbt.CompoundTag copy = new SimpleNbt.CompoundTag();
        if (nbt != null) {
            for (Map.Entry<String, SimpleNbt.Tag> entry : nbt.getValue().entrySet()) {
                String key = entry.getKey();
                if (key.equals("UUID") || key.equals("UUIDMost") || key.equals("UUIDLeast")) continue;
                copy.put(key, entry.getValue());
            }
        }
        String id = entityId == null ? "" : entityId.toLowerCase(Locale.ROOT);
        copy.put("id", new SimpleNbt.StringTag(id.contains(":") ? id : "minecraft:" + id));

        List<SimpleNbt.Tag> pos = new ArrayList<>();
        pos.add(new SimpleNbt.DoubleTag(x));
        pos.add(new SimpleNbt.DoubleTag(y));
        pos.add(new SimpleNbt.DoubleTag(z));
        copy.put("Pos", new SimpleNbt.ListTag(SimpleNbt.TAG_DOUBLE, pos));

        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        if (copy.containsKey("TileX") || copy.containsKey("TileY") || copy.containsKey("TileZ")) {
            copy.put("TileX", new SimpleNbt.IntTag(bx));
            copy.put("TileY", new SimpleNbt.IntTag(by));
            copy.put("TileZ", new SimpleNbt.IntTag(bz));
        }
        if (copy.containsKey("block_pos")) {
            copy.put("block_pos", new SimpleNbt.IntArrayTag(new int[]{bx, by, bz}));
        }

        boolean painting = isPainting(entityId);
        for (String key : new String[]{"Facing", "facing"}) {
            SimpleNbt.Tag facing = copy.get(key);
            if (facing instanceof SimpleNbt.ByteTag byteTag) {
                int rotated = painting
                        ? rotatePaintingFacing(byteTag.asByte(), rotationDegrees)
                        : rotateFrameFacing(byteTag.asByte(), rotationDegrees);
                copy.put(key, new SimpleNbt.ByteTag((byte) rotated));
            } else if (facing instanceof SimpleNbt.StringTag name) {
                try {
                    BlockFace face = BlockFace.valueOf(name.asString().toUpperCase(Locale.ROOT));
                    copy.put(key, new SimpleNbt.StringTag(rotate(face, rotationDegrees).name().toLowerCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return copy;
    }

    /** 回転後の向き（額縁・絵画）。NBT に向きが無ければ null。 */
    public static BlockFace facingOf(SimpleNbt.CompoundTag adjusted, String entityId) {
        for (String key : new String[]{"Facing", "facing"}) {
            SimpleNbt.Tag facing = adjusted.get(key);
            if (facing instanceof SimpleNbt.ByteTag byteTag) return faceOf(entityId, byteTag.asByte());
            if (facing instanceof SimpleNbt.StringTag name) {
                try {
                    return BlockFace.valueOf(name.asString().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return null;
    }

    /** 印の値（セッション ID と通し番号）。 */
    public static String tagValue(String sessionId, int index) {
        return sessionId + ":" + index;
    }

    /** そのチャンクの既存のエンティティの中に、同じ印のものがあるか。再開で二重に作らないための検査。 */
    public static boolean alreadyPlaced(Iterable<? extends Entity> existing, String tagValue) {
        for (Entity entity : existing) {
            String value = entity.getPersistentDataContainer().get(PASTE_TAG_KEY, PersistentDataType.STRING);
            if (tagValue.equals(value)) return true;
        }
        return false;
    }

    /**
     * エンティティを1つ置く。所有リージョンのスレッドで呼ぶ。
     *
     * @param tagValue PDC に付ける印（null なら付けない）
     */
    public static void place(World world, PastePlan.EntityOp op, int rotationDegrees, String tagValue) {
        StructureEntity entity = op.entity();
        Location loc = new Location(world, op.x(), op.y(), op.z(), entity.yaw(), entity.pitch());

        if (EntityNbtApplier.isPropModel(entity.nbt())) {
            String propName = EntityNbtApplier.getPropModelName(entity.nbt());
            if (BetterModelBridge.isEnabled() && propName != null) {
                BetterModelBridge.spawnProp(loc, propName);
            }
            // FreeMinecraftModels が無ければ、衝突を避けるため素の ArmorStand は出さない。
            if (!Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels")) return;
        }

        String rawId = entity.entityId();
        if (rawId == null || rawId.isBlank()) return;

        if (isHanging(rawId) && placeHanging(world, op, rotationDegrees, tagValue)) return;

        String cleanId = rawId.replace("minecraft:", "").toUpperCase(Locale.ROOT);
        EntityType type;
        try {
            type = EntityType.valueOf(cleanId);
        } catch (IllegalArgumentException e) {
            Logger.warn("図面のエンティティの種類が不明です: " + rawId);
            return;
        }
        try {
            Class<? extends Entity> entityClass = type.getEntityClass();
            if (entityClass != null) {
                world.spawn(loc, entityClass, spawned -> {
                    EntityNbtApplier.applyNbt(spawned, entity.nbt());
                    tag(spawned, tagValue);
                });
            } else {
                Entity spawned = world.spawnEntity(loc, type);
                EntityNbtApplier.applyNbt(spawned, entity.nbt());
                tag(spawned, tagValue);
            }
        } catch (Exception e) {
            try {
                Entity spawned = world.spawnEntity(loc, type);
                EntityNbtApplier.applyNbt(spawned, entity.nbt());
                tag(spawned, tagValue);
            } catch (Exception ex) {
                Logger.warn("エンティティ " + rawId + " を置けませんでした: " + ex.getMessage());
            }
        }
    }

    private static boolean placeHanging(World world, PastePlan.EntityOp op, int rotationDegrees, String tagValue) {
        StructureEntity entity = op.entity();
        if (entity.nbt() == null) return false;
        try {
            SimpleNbt.CompoundTag adjusted = adjust(entity.nbt(), entity.entityId(), op.x(), op.y(), op.z(), rotationDegrees);
            EntitySnapshot snapshot = Bukkit.getEntityFactory().createEntitySnapshot(SimpleNbt.toSnbt(adjusted));
            Location loc = new Location(world, op.x(), op.y(), op.z());
            Entity created = snapshot.createEntity(loc);
            BlockFace face = facingOf(adjusted, entity.entityId());
            if (created instanceof Hanging hanging && face != null) {
                hanging.setFacingDirection(face, true);
            }
            tag(created, tagValue);
            return true;
        } catch (Throwable t) {
            Logger.warn("額縁・絵画の置き直しに失敗したので通常の方法で置きます: " + t.getMessage());
            return false;
        }
    }

    private static void tag(Entity entity, String tagValue) {
        if (tagValue == null || entity == null) return;
        entity.getPersistentDataContainer().set(PASTE_TAG_KEY, PersistentDataType.STRING, tagValue);
    }
}
