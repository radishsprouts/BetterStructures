package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.api.ChestFillEvent;
import com.magmaguy.betterstructures.chests.ChestContents;
import com.magmaguy.betterstructures.runtime.ThreadTickBudget;
import com.magmaguy.betterstructures.schematic.model.StructureBlock;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 1チャンクの貼り付けを、所有リージョンのスレッドで段（{@link PasteStage}）の順に進める。
 *
 * <p>予算は {@link ThreadTickBudget}。1つの操作ごとに予算を確かめ、切れたら進み具合
 * ({@link PasteSession.Progress}) を保ったまま {@code runRegionLater(…, 1L)} で次の tick に続ける。
 * 操作は落とさない・時間切れで捨てない。段の進み具合は PasteJournal が記録する。
 */
final class ChunkPasteTask implements Runnable {

    private final PasteSession session;
    private final PastePlan.ChunkOps ops;
    private final PasteSession.Progress progress;
    private final World world;

    /** 次に動く時刻の予定（予算の絞りのために遅れを測る）。0 は予約ではなく初回。 */
    private long expectedRun;
    private boolean finished;
    /** 看板の目印で置かれた容器（中身を戦利品で埋める）。 */
    private final List<PastePlan.BlockOp> markerContainers = new ArrayList<>();
    private List<PastePlan.BlockOp> containerList;
    /** 再開時だけ使う。このチャンクにいる既存のエンティティ（二重生成の検査）。 */
    private List<Entity> existingEntities;

    ChunkPasteTask(PasteSession session, PastePlan.ChunkOps ops, PasteSession.Progress progress) {
        this.session = session;
        this.ops = ops;
        this.progress = progress;
        this.world = session.world();
    }

    @Override
    public void run() {
        if (finished || session.isStopped()) return;
        ThreadTickBudget budget = session.env().budget();
        if (expectedRun != 0) budget.observeLag(expectedRun);

        try {
            while (progress.stage() != PasteStage.DONE) {
                boolean stageFinished = runStage(progress.stage(), budget);
                if (session.isStopped()) return;
                if (!stageFinished) {
                    expectedRun = ThreadTickBudget.expectedRunNanos(1L);
                    session.env().regions().runLater(world, ops.chunkX, ops.chunkZ, this, 1L);
                    return;
                }
                progress.advance();
            }
        } catch (Throwable fatal) {
            Logger.warn("チャンク (" + ops.chunkX + "," + ops.chunkZ + ") の貼り付けで予期しないエラーが起きました: " + fatal);
        }
        finished = true;
        session.chunkDone(ops.chunkX, ops.chunkZ);
    }

    /** 段を進められる所まで進める。段が終わったら true、予算切れ・停止で途中なら false。 */
    private boolean runStage(PasteStage stage, ThreadTickBudget budget) {
        return switch (stage) {
            case BLOCKS -> runOps(ops.blocks.size(), budget, i -> placeBlock(ops.blocks.get(i)));
            case SECOND_PASS -> runOps(ops.secondPass.size(), budget, i -> placeBlock(ops.secondPass.get(i)));
            case SIGNS -> runOps(ops.signs.size(), budget, i -> processSign(ops.signs.get(i)));
            case BLOCK_NBT -> runOps(ops.blockNbt.size(), budget, i -> writeBlockEntity(ops.blockNbt.get(i)));
            case CONTAINERS -> {
                if (containerList == null) {
                    containerList = new ArrayList<>(ops.containers);
                    containerList.addAll(markerContainers);
                }
                yield runOps(containerList.size(), budget, i -> fillContainer(containerList.get(i)));
            }
            case ENTITIES -> runOps(ops.entities.size(), budget, i -> placeEntity(ops.entities.get(i)));
            case POST -> runOps(ops.columns.size(), budget, i -> postProcess(ops.columns.get(i)));
            case DONE -> true;
        };
    }

    private boolean runOps(int size, ThreadTickBudget budget, IntConsumer op) {
        while (progress.cursor() < size) {
            if (session.isStopped()) return false;
            if (!budget.hasBudget()) return false;
            int index = progress.cursor();
            long start = budget.now();
            try {
                op.accept(index);
            } catch (Throwable t) {
                Logger.warn("チャンク (" + ops.chunkX + "," + ops.chunkZ + ") の " + progress.stage() + " 段でエラー: " + t);
            }
            budget.consumeSince(start);
            progress.step();
        }
        return true;
    }

    // ---- BLOCKS / SECOND_PASS ------------------------------------------------------------

    private void placeBlock(PastePlan.BlockOp op) {
        Block destination = world.getBlockAt(op.x(), op.y(), op.z());
        BlockData newData = op.block().blockData();
        Material newMaterial = newData.getMaterial();
        Material currentType = destination.getType();

        // 空気どうしは何もしない。
        if (newMaterial.isAir() && currentType.isAir()) return;

        // 岩盤は台座材（建造物）や固定の材（モジュールは STONE）に置き換える。固体が既にあれば置かない。
        if (newMaterial == Material.BEDROCK) {
            if (!currentType.isSolid()) {
                Material replacement = bedrockReplacement(op);
                if (replacement != null && currentType != replacement) {
                    destination.setType(replacement, false);
                }
            }
            return;
        }

        // 同じものを書き直さない（チャンクを無駄に dirty にしない。再開で何度やっても同じ結果）。
        if (currentType == newMaterial && destination.getBlockData().equals(newData)) return;

        destination.setBlockData(newData, false);
    }

    private Material bedrockReplacement(PastePlan.BlockOp op) {
        PasteRequest request = session.request();
        if (request.bedrockReplacement() != null) return request.bedrockReplacement();
        if (request.pedestalMaterialProvider() == null) return null;
        return request.pedestalMaterialProvider().apply(!isSolidAbove(op));
    }

    /**
     * 岩盤の真上が固体か。Y の昇順に置くので、真上はまだ世界に置かれていない。図面の中にあればその材質で
     * 判定し（地表か地中かを置く順に左右されない）、無い・バリア（既存の地形を残す）なら世界の実物で判定する。
     */
    private boolean isSolidAbove(PastePlan.BlockOp op) {
        PastePlan plan = session.plan();
        BlockData above = session.request().schematic().getBlockData(
                op.x() - plan.baseX(), op.y() - plan.baseY() + 1, op.z() - plan.baseZ());
        if (above != null && above.getMaterial() != Material.BARRIER) {
            return above.getMaterial().isSolid();
        }
        return world.getBlockAt(op.x(), op.y() + 1, op.z()).getType().isSolid();
    }

    // ---- SIGNS ---------------------------------------------------------------------------

    private void processSign(PastePlan.BlockOp op) {
        Block destination = world.getBlockAt(op.x(), op.y(), op.z());
        switch (op.sign()) {
            case MARKER -> {
                StructureBlock block = op.block();
                session.request().markerHandler().handleMarker(world, op.x(), op.y(), op.z(), block);
                String boss = BuildingMarkerHandler.eliteMobsBossFilename(block);
                if (boss != null) {
                    session.recordArena(boss, new Location(world, op.x() + 0.5, op.y(), op.z() + 0.5));
                }
                Material now = world.getBlockAt(op.x(), op.y(), op.z()).getType();
                if (PastePlan.isLootContainer(now) && contentsFor(now) != null) {
                    markerContainers.add(op);
                }
            }
            case CLEAR -> {
                if (!destination.getType().isAir()) destination.setType(Material.AIR, false);
            }
            case PLAIN -> restoreSign(destination, op.block().parsedSign());
            case NONE -> {
            }
        }
    }

    private void restoreSign(Block block, SignTextParser.ParsedSign data) {
        if (data == null || !(block.getState() instanceof Sign sign)) return;

        SignSide front = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            front.setLine(i, data.getFrontLine(i));
        }
        front.setColor(data.frontColor());
        front.setGlowingText(data.frontGlowing());

        SignSide back = sign.getSide(Side.BACK);
        for (int i = 0; i < 4; i++) {
            back.setLine(i, data.getBackLine(i));
        }
        back.setColor(data.backColor());
        back.setGlowingText(data.backGlowing());

        if (data.isWaxed()) sign.setWaxed(true);
        sign.update(true, false);
    }

    // ---- BLOCK_NBT / CONTAINERS ----------------------------------------------------------

    private ChestContents contentsFor(Material material) {
        PasteRequest request = session.request();
        return material == Material.BARREL ? request.barrelContents() : request.chestContents();
    }

    private void writeBlockEntity(PastePlan.BlockOp op) {
        Material material = op.block().blockData().getMaterial();
        // 戦利品で埋める容器は、図面の中身を復元しない（コンテナの段で埋める）。
        boolean restoreItems = !(PastePlan.isLootContainer(material) && contentsFor(material) != null);
        BlockEntityWriter.write(world.getBlockAt(op.x(), op.y(), op.z()), op.block().blockEntityData(), restoreItems);
    }

    private void fillContainer(PastePlan.BlockOp op) {
        Block destination = world.getBlockAt(op.x(), op.y(), op.z());
        if (!(destination.getState() instanceof Container container)) return;
        ChestContents contents = contentsFor(destination.getType());
        if (contents == null) return;

        contents.rollChestContents(container);
        ChestFillEvent event = new ChestFillEvent(container, contents.getTreasureConfigFields().getFilename());
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            container.update(true, false);
        }
    }

    // ---- ENTITIES / POST -----------------------------------------------------------------

    private void placeEntity(PastePlan.EntityOp op) {
        String tag = EntityPlacer.tagValue(session.id(), op.index());
        if (session.resumed()) {
            if (existingEntities == null) {
                existingEntities = List.of(world.getChunkAt(ops.chunkX, ops.chunkZ).getEntities());
            }
            if (EntityPlacer.alreadyPlaced(existingEntities, tag)) return;
        }
        EntityPlacer.place(world, op, session.request().rotationDegrees(), tag);
    }

    private void postProcess(PastePlan.Column column) {
        PasteRequest request = session.request();
        PastePlan plan = session.plan();
        if (request.clearTrees()) {
            TreeClearer.clear(world, column.x(), column.z(), plan.baseY() + request.schematic().getHeight());
        }
        if (request.fillPedestal() && column.pedestal()) {
            PedestalFiller.fill(world, column.x(), column.z(), plan.baseY(), request.pedestalMaterialProvider());
        }
    }
}
