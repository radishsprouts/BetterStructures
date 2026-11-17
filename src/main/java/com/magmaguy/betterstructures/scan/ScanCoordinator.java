package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.BetterStructures;
import com.magmaguy.betterstructures.buildingfitter.FitAnything;
import com.magmaguy.betterstructures.buildingfitter.util.SchematicPicker;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType;
import com.magmaguy.betterstructures.paste.MarkerHandler;
import com.magmaguy.betterstructures.paste.PasteJournal;
import com.magmaguy.betterstructures.runtime.GenerationGovernor;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * 地形走査の入口（旧 {@code TerrainScanQueue} の代わり）。依頼は {@link GenerationGovernor} の SCAN の枠で
 * 順に走らせ、<b>捨てない</b>（同じ格子点の二重の依頼だけを重複として除く）。
 *
 * <p>1件は {@link ScanJob} が進める。枠は走査が終わり、貼り付けの依頼を PasteEngine へ渡した時点で返す
 * （貼り付け自体は PASTE の枠）。再読み込みで世代が変わった走査は結果を捨てずに保留へ回し、
 * 再読み込みの後に {@link #replayDeferred()} でやり直す。
 *
 * <p>スレッド: {@link #submit} はどのスレッドからでもよい。ワールドには触れない。
 */
public final class ScanCoordinator {
    private record ScanKey(StructureType kind, int chunkX, int chunkZ) {
    }

    /** 再読み込みの間に走らせられなかった（または途中で世代が変わった）自然生成の依頼。 */
    private static final Queue<ScanRequest> deferred = new ConcurrentLinkedQueue<>();
    /** 枠をもらって走っている自然生成の依頼（停止時の記録用）。 */
    private static final Set<ScanRequest> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static volatile Function<FitAnything, MarkerHandler> markerHandlerFactory = fit -> {
        throw new IllegalStateException("The marker handler for building pastes is not installed");
    };
    private static volatile ScanEvents events = ScanEvents.BUKKIT;

    private ScanCoordinator() {
    }

    /** 建造物の看板の目印の扱い（U2 の {@code BuildingMarkerHandler}）を作る関数。onEnable で入れる。 */
    public static void setMarkerHandlerFactory(Function<FitAnything, MarkerHandler> factory) {
        markerHandlerFactory = factory;
    }

    /** 試験用: 出すイベントを差し替える。 */
    static void setEvents(ScanEvents replacement) {
        events = replacement == null ? ScanEvents.BUKKIT : replacement;
    }

    /** 依頼を入れる。再読み込み中なら保留に回す（自然生成）または中断を伝える（指定の図面）。 */
    public static void submit(ScanRequest request) {
        if (BetterStructures.isReloading()) {
            deferOrInterrupt(request);
            return;
        }
        long generation = PlatformScheduler.reloadGeneration();
        // 自然生成は同じ格子点を二重に入れない。指定の図面（/bs place）は毎回別の依頼として通す。
        Object dedupeKey = request.forced() == null ? new ScanKey(request.kind(), request.chunkX(), request.chunkZ()) : null;
        GenerationGovernor.instance().submit(new GenerationGovernor.Work(
                GenerationGovernor.Lane.SCAN, request.world().getUID(), dedupeKey, request,
                ticket -> {
                    // 走っている間も停止時の記録に載せるため、枠を返すまで覚えておく。
                    if (request.forced() == null) inFlight.add(request);
                    start(request, generation, () -> {
                        inFlight.remove(request);
                        ticket.release();
                    });
                }));
    }

    /**
     * 停止時の記録（PasteJournal）に載せる、まだ貼り付けに渡っていない自然生成の走査の依頼
     * （待ち・走っている・再読み込みで保留中）。{@code /bs place} の指定の図面は、その場の操作なので載せない。
     * 呼んだスレッドは待たない。
     */
    public static List<PasteJournal.ScanEntry> journalEntries() {
        Set<ScanRequest> unique = new LinkedHashSet<>(inFlight);
        for (GenerationGovernor.Work work : GenerationGovernor.instance().pendingSnapshot(GenerationGovernor.Lane.SCAN)) {
            if (work.payload() instanceof ScanRequest request && request.forced() == null) unique.add(request);
        }
        unique.addAll(deferred);
        List<PasteJournal.ScanEntry> entries = new ArrayList<>(unique.size());
        for (ScanRequest request : unique) {
            if (request.forced() != null) continue;
            entries.add(new PasteJournal.ScanEntry(request.world().getUID().toString(),
                    request.chunkX(), request.chunkZ(), request.kind().name()));
        }
        return entries;
    }

    /** 前回の停止で記録された走査の依頼を戻す。世界が無い・種類が読めないものは警告して飛ばす。 */
    public static void restore(List<PasteJournal.ScanEntry> entries) {
        for (PasteJournal.ScanEntry entry : entries) {
            try {
                World world = Bukkit.getWorld(UUID.fromString(entry.worldId));
                if (world == null) {
                    Logger.warn("走査の記録の世界 " + entry.worldId + " が見つからないため戻せません。");
                    continue;
                }
                submit(ScanRequest.natural(world, entry.chunkX, entry.chunkZ, StructureType.valueOf(entry.kind)));
            } catch (RuntimeException e) {
                Logger.warn("走査の記録を戻せませんでした: " + e);
            }
        }
    }

    /** 保留していた依頼を、今の世代でやり直す（再読み込みが終わった後に呼ぶ）。 */
    public static void replayDeferred() {
        ScanRequest request;
        List<ScanRequest> batch = new ArrayList<>();
        while ((request = deferred.poll()) != null) batch.add(request);
        for (ScanRequest pending : batch) {
            World world = pending.world();
            if (Bukkit.getWorld(world.getUID()) != world) continue;
            submit(pending);
        }
    }

    /** 保留を捨てる（コンテンツの再読み込みを取りやめる・停止する時）。 */
    public static void discardDeferred() {
        deferred.clear();
    }

    /** 停止時。 */
    public static void shutdown() {
        deferred.clear();
        inFlight.clear();
    }

    /** 保留中の依頼の数（試験・診断用）。 */
    public static int deferredCount() {
        return deferred.size();
    }

    private static void deferOrInterrupt(ScanRequest request) {
        if (request.forced() != null) {
            request.notifyOutcome(ScanOutcome.INTERRUPTED);
            return;
        }
        deferred.add(request);
    }

    private static void start(ScanRequest request, long generation, GenerationGovernor.Ticket ticket) {
        BukkitScanPorts ports = null;
        try {
            if (!PlatformScheduler.isCurrentReloadGeneration(generation) || BetterStructures.isReloading()) {
                deferOrInterrupt(request);
                ticket.release();
                return;
            }
            ports = new BukkitScanPorts(request.world(), generation);
            ScanJob job = createJob(request, ports);
            if (job == null) {
                request.notifyOutcome(ScanOutcome.FAILED);
                ticket.release();
                return;
            }
            BukkitScanPorts started = ports;
            job.run().whenComplete((result, error) -> finish(request, started, result, error, ticket));
        } catch (Throwable failure) {
            Logger.warn("Could not start the terrain scan at " + request.chunkX() + ", " + request.chunkZ() + ": " + failure);
            if (ports != null) ports.holds().releaseAll();
            request.notifyOutcome(ScanOutcome.FAILED);
            ticket.release();
        }
    }

    private static ScanJob createJob(ScanRequest request, BukkitScanPorts ports) {
        ScanJob.Chooser chooser;
        if (request.forced() != null) {
            SchematicContainer forced = request.forced();
            if (forced.getStructureSchematic() == null) {
                Logger.warn("The schematic " + forced.getConfigFilename() + " has no native structure data and cannot be placed.");
                return null;
            }
            ScanJob.Choice choice = new ScanJob.Choice(forced, new StructureSchematicShape(forced.getStructureSchematic()));
            chooser = (biome, y) -> choice;
        } else {
            // 登録簿は再読み込みで作り直されるので、所有を持たない WorkerPool からは触れず、ここで写しを取る。
            List<SchematicContainer> candidates = new ArrayList<>();
            for (SchematicContainer container : SchematicContainer.getSchematics().get(request.kind()))
                if (container.getStructureSchematic() != null) candidates.add(container);
            String worldName = ports.worldName();
            World.Environment environment = ports.environment();
            SplittableRandom pickRandom = new SplittableRandom();
            StructureType kind = request.kind();
            chooser = (biome, y) -> {
                SchematicContainer picked = SchematicPicker.pick(candidates, worldName, environment, biome, y, kind, pickRandom);
                return picked == null ? null : new ScanJob.Choice(picked, new StructureSchematicShape(picked.getStructureSchematic()));
            };
        }
        return new ScanJob(ports, request.kind(), request.chunkX(), request.chunkZ(), chooser, anchorConfig(), new SplittableRandom());
    }

    static AnchorPlanner.Config anchorConfig() {
        return new AnchorPlanner.Config(
                DefaultConfig.getNormalCustomAirBuildingMinAltitude(), DefaultConfig.getNormalCustomAirBuildingMaxAltitude(),
                DefaultConfig.getEndAirBuildMinAltitude(), DefaultConfig.getEndAirBuildMaxAltitude(),
                DefaultConfig.getLowestYNormalCustom(), DefaultConfig.getHighestYNormalCustom(),
                DefaultConfig.getLowestYNether(), DefaultConfig.getHighestYNether(),
                DefaultConfig.getLowestYEnd(), DefaultConfig.getHighestYEnd());
    }

    private static void finish(ScanRequest request, BukkitScanPorts ports, ScanJob.Result result, Throwable error,
                               GenerationGovernor.Ticket ticket) {
        if (error != null) {
            ports.holds().releaseAll();
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            // 停止による取りやめは異常ではない。
            if (!(cause instanceof CancellationException))
                Logger.warn("Terrain scan failed at " + request.chunkX() + ", " + request.chunkZ() + " (" + request.kind() + "): " + cause);
            request.notifyOutcome(ScanOutcome.FAILED);
            ticket.release();
            return;
        }
        switch (result.end()) {
            case NO_FIT -> {
                ports.holds().releaseAll();
                request.notifyOutcome(ScanOutcome.NO_FIT);
                ticket.release();
            }
            case NO_SCHEMATIC -> {
                ports.holds().releaseAll();
                request.notifyOutcome(ScanOutcome.NO_SCHEMATIC);
                ticket.release();
            }
            case STALE -> {
                ports.holds().releaseAll();
                deferOrInterrupt(request);
                ticket.release();
            }
            case PLAN -> decide(request, ports, result.plan(), ticket);
        }
    }

    /** 決定の段: 貼り付け先の原点チャンクの所有リージョンで、イベントを出して PasteEngine へ渡す。 */
    private static void decide(ScanRequest request, BukkitScanPorts ports, ScanJob.Plan plan, GenerationGovernor.Ticket ticket) {
        int chunkX = Math.floorDiv(plan.x(), 16);
        int chunkZ = Math.floorDiv(plan.z(), 16);
        Runnable decision = () -> {
            try {
                if (ports.stale()) {
                    ports.holds().releaseAll();
                    deferOrInterrupt(request);
                    return;
                }
                ScanOutcome outcome = PasteHandoff.start(request.world(), plan, plan.container().getStructureSchematic(),
                        ports.holds(), markerHandlerFactory, events);
                if (outcome != ScanOutcome.PLACED) ports.holds().releaseAll();
                request.notifyOutcome(outcome);
            } catch (Throwable failure) {
                ports.holds().releaseAll();
                Logger.warn("Could not start the structure paste at " + plan.x() + ", " + plan.y() + ", " + plan.z() + ": " + failure);
                request.notifyOutcome(ScanOutcome.FAILED);
            } finally {
                ticket.release();
            }
        };
        if (PlatformScheduler.isOwnedByCurrentRegion(request.world(), chunkX, chunkZ)) {
            decision.run();
        } else {
            PlatformScheduler.runRegion(request.world(), chunkX, chunkZ, decision);
        }
    }
}
