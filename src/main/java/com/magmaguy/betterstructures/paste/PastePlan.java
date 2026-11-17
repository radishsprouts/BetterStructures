package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.schematic.model.StructureBlock;
import com.magmaguy.betterstructures.schematic.model.StructureEntity;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Lantern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 貼り付け1件の計画。チャンクの鍵 → そのチャンクで行う操作の列（Y の昇順）。
 * 図面を舐めるだけで世界には触れないので、WorkerPool（非同期）で作ってよい。作った後は変更しない。
 */
public final class PastePlan {

    /** 看板の扱い。 */
    public enum SignKind {
        /** 看板ではない。 */
        NONE,
        /** 目印の看板。{@link MarkerHandler#handleMarker} に任せる。 */
        MARKER,
        /** 空気にする（文字のない立ち看板・{@link MarkerHandler.PlainSignPolicy#REMOVE} の看板）。 */
        CLEAR,
        /** 文字を復元して残す看板。 */
        PLAIN
    }

    /** ブロック1つの操作。座標は世界座標。 */
    public record BlockOp(int x, int y, int z, StructureBlock block, SignKind sign) {
    }

    /** エンティティ1つの操作。座標は世界座標。{@code index} は図面の中の通し番号（二重生成の防止に使う）。 */
    public record EntityOp(int index, double x, double y, double z, StructureEntity entity) {
    }

    /** 台座・木の除去を行う柱（世界座標の x・z）。{@code pedestal} は図面の最下層が空気でない柱。 */
    public record Column(int x, int z, boolean pedestal) {
    }

    /** 1チャンクぶんの操作。 */
    public static final class ChunkOps {
        public final int chunkX;
        public final int chunkZ;
        public final List<BlockOp> blocks = new ArrayList<>();
        public final List<BlockOp> secondPass = new ArrayList<>();
        public final List<BlockOp> signs = new ArrayList<>();
        public final List<BlockOp> blockNbt = new ArrayList<>();
        public final List<BlockOp> containers = new ArrayList<>();
        public final List<EntityOp> entities = new ArrayList<>();
        public final List<Column> columns = new ArrayList<>();

        ChunkOps(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        public long key() {
            return Chunk.getChunkKey(chunkX, chunkZ);
        }

        /** 段ごとの操作数（再開の位置の検査と試験用）。 */
        public int size(PasteStage stage) {
            return switch (stage) {
                case BLOCKS -> blocks.size();
                case SECOND_PASS -> secondPass.size();
                case SIGNS -> signs.size();
                case BLOCK_NBT -> blockNbt.size();
                case CONTAINERS -> containers.size();
                case ENTITIES -> entities.size();
                case POST -> columns.size();
                case DONE -> 0;
            };
        }
    }

    private final Map<Long, ChunkOps> chunks;
    private final int baseX;
    private final int baseY;
    private final int baseZ;

    private PastePlan(Map<Long, ChunkOps> chunks, int baseX, int baseY, int baseZ) {
        this.chunks = chunks;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
    }

    /** チャンクの鍵 → 操作。挿入順（図面の Y・Z・X の順に初めて現れたチャンクの順）。 */
    public Map<Long, ChunkOps> chunks() {
        return chunks;
    }

    public int baseX() {
        return baseX;
    }

    public int baseY() {
        return baseY;
    }

    public int baseZ() {
        return baseZ;
    }

    /** 2周目に回すブロックか: 光源・向き付き・レール・吊り下げのランタン（モジュールの経路と同じ基準）。 */
    public static boolean needsSecondPass(BlockData data) {
        if (data == null) return false;
        if (data.getMaterial().isAir()) return false;
        return emitsLight(data)
                || data instanceof Directional
                || data instanceof Rail
                || data instanceof Lantern;
    }

    /** 光源か。光量を答えられない実装（テスト用の模擬サーバー）では光源として扱わない。 */
    private static boolean emitsLight(BlockData data) {
        try {
            return data.getLightEmission() > 0;
        } catch (RuntimeException notImplemented) {
            return false;
        }
    }

    /** 容器の中身を戦利品で埋める対象の材質か。 */
    public static boolean isLootContainer(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST || material == Material.BARREL
                || (material.name().endsWith("SHULKER_BOX") && !material.name().startsWith("LEGACY_"));
    }

    /** 図面と依頼から計画を作る。 */
    public static PastePlan build(PasteRequest request) {
        StructureSchematic schematic = request.schematic();
        MarkerHandler handler = request.markerHandler();
        boolean removePlainSigns = handler.plainSignPolicy() == MarkerHandler.PlainSignPolicy.REMOVE;
        int baseX = request.baseLocation().getBlockX() + (request.schematicOffset() == null ? 0 : request.schematicOffset().getBlockX());
        int baseY = request.baseLocation().getBlockY() + (request.schematicOffset() == null ? 0 : request.schematicOffset().getBlockY());
        int baseZ = request.baseLocation().getBlockZ() + (request.schematicOffset() == null ? 0 : request.schematicOffset().getBlockZ());

        Map<Long, ChunkOps> chunks = new LinkedHashMap<>();
        int width = schematic.getWidth();
        int height = schematic.getHeight();
        int length = schematic.getLength();

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    StructureBlock block = schematic.getBlock(x, y, z);
                    if (block == null || block.blockData() == null) continue;
                    Material material = block.blockData().getMaterial();
                    if (material == Material.BARRIER) continue;

                    int wx = baseX + x;
                    int wy = baseY + y;
                    int wz = baseZ + z;
                    ChunkOps ops = chunkOps(chunks, wx >> 4, wz >> 4);

                    SignKind sign = SignKind.NONE;
                    if (block.isSign()) {
                        if (handler.isMarker(block)) {
                            sign = SignKind.MARKER;
                        } else if (!block.parsedSign().hasText() && !material.name().contains("WALL")) {
                            sign = SignKind.CLEAR;
                        } else if (removePlainSigns) {
                            sign = SignKind.CLEAR;
                        } else {
                            sign = SignKind.PLAIN;
                        }
                    }
                    BlockOp op = new BlockOp(wx, wy, wz, block, sign);

                    if (sign == SignKind.MARKER || sign == SignKind.CLEAR) {
                        // 看板としては置かない。SIGNS 段で空気にする（目印は処理も行う）。
                        ops.signs.add(op);
                        continue;
                    }
                    if (needsSecondPass(block.blockData())) {
                        ops.secondPass.add(op);
                    } else {
                        ops.blocks.add(op);
                    }
                    if (sign == SignKind.PLAIN) {
                        ops.signs.add(op);
                    } else if (block.blockEntityData() != null && com.magmaguy.betterstructures.paste.BlockEntityWriter.handles(material)) {
                        ops.blockNbt.add(op);
                    }
                    if (isLootContainer(material)
                            && (material == Material.BARREL ? request.barrelContents() : request.chestContents()) != null) {
                        ops.containers.add(op);
                    }
                }
            }
        }

        int index = 0;
        for (StructureEntity entity : schematic.getEntities()) {
            double wx = baseX + entity.x();
            double wy = baseY + entity.y();
            double wz = baseZ + entity.z();
            ChunkOps ops = chunkOps(chunks, ((int) Math.floor(wx)) >> 4, ((int) Math.floor(wz)) >> 4);
            ops.entities.add(new EntityOp(index++, wx, wy, wz, entity));
        }

        // 台座と木の除去のための柱。範囲の全部の柱が対象（空だけのチャンクも DONE まで hold される）。
        if (request.fillPedestal() || request.clearTrees()) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    BlockData bottom = schematic.getBlockData(x, 0, z);
                    boolean pedestal = bottom != null && !bottom.getMaterial().isAir()
                            && bottom.getMaterial() != Material.BARRIER;
                    int wx = baseX + x;
                    int wz = baseZ + z;
                    chunkOps(chunks, wx >> 4, wz >> 4).columns.add(new Column(wx, wz, pedestal));
                }
            }
        }

        Comparator<BlockOp> byY = Comparator.comparingInt(BlockOp::y);
        for (ChunkOps ops : chunks.values()) {
            ops.blocks.sort(byY);
            ops.secondPass.sort(byY);
        }
        return new PastePlan(chunks, baseX, baseY, baseZ);
    }

    private static ChunkOps chunkOps(Map<Long, ChunkOps> chunks, int chunkX, int chunkZ) {
        return chunks.computeIfAbsent(Chunk.getChunkKey(chunkX, chunkZ), key -> new ChunkOps(chunkX, chunkZ));
    }
}
