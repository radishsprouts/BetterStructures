package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType;
import com.magmaguy.betterstructures.schematics.SchematicContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * 地形走査1件の状態遷移。ワールドにもスレッドにも直接触れず、{@link ScanPorts} だけを通して読み込む。
 *
 * <p>流れ: 原点チャンクを取る → 仮の位置と図面を決める（WorkerPool）→ 候補を評価順に1つずつ、
 * 要るチャンクのうち持っていないものだけを足して採点する（WorkerPool）→ 採用した位置と台座材の標本を返す。
 * 読み込めていないチャンクに当たっても最初からやり直さず、足りない分を待って続ける。
 *
 * <p>結果は {@link Result}。{@link End#PLAN} の時だけ貼り付ける位置がある。走査が取ったチャンクの hold は
 * {@link ScanPorts#holds()} にあり、後始末（貼り付けへの引き継ぎ、または捨てる）は呼び出し側が行う。
 */
public final class ScanJob {

    /** 図面の選択結果。 */
    public record Choice(SchematicContainer container, SchematicShape shape) {
    }

    /** 仮の位置のバイオームと Y から図面を選ぶ。条件に合うものが無ければ null。 */
    @FunctionalInterface
    public interface Chooser {
        Choice choose(Object biome, int anchorY);
    }

    /** 貼り付ける位置の決定。 */
    public record Plan(StructureType kind, SchematicContainer container, SchematicShape shape, int x, int y, int z,
                       double score, PedestalSampler.Samples pedestal) {
    }

    /** 走査の終わり方。 */
    public enum End {
        PLAN,
        /** 合う場所が無かった。 */
        NO_FIT,
        /** 合う図面が無かった。 */
        NO_SCHEMATIC,
        /** 途中で再読み込み・停止があり、結果が使えない。 */
        STALE
    }

    public record Result(End end, Plan plan) {
        static final Result NO_FIT = new Result(End.NO_FIT, null);
        static final Result NO_SCHEMATIC = new Result(End.NO_SCHEMATIC, null);
        static final Result STALE = new Result(End.STALE, null);
    }

    private final ScanPorts ports;
    private final StructureType kind;
    private final int chunkX;
    private final int chunkZ;
    private final Chooser chooser;
    private final AnchorPlanner.Config config;
    private final RandomGenerator random;
    private final SnapshotBlockSource source;

    private AnchorPlanner.Anchor anchor;
    private Choice choice;
    private CandidateSelector selector;

    public ScanJob(ScanPorts ports, StructureType kind, int chunkX, int chunkZ, Chooser chooser,
                   AnchorPlanner.Config config, RandomGenerator random) {
        this.ports = ports;
        this.kind = kind;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.chooser = chooser;
        this.config = config;
        this.random = random;
        this.source = new SnapshotBlockSource(ports.minY(), ports.maxY());
    }

    /** 読み込んだ地形（試験用）。 */
    SnapshotBlockSource source() {
        return source;
    }

    public CompletableFuture<Result> run() {
        return acquire(chunkX, chunkZ)
                .thenCompose(ignored -> onWorker(this::begin))
                .thenCompose(early -> early != null ? CompletableFuture.completedFuture(early) : step(0));
    }

    /** 仮の位置と図面を決め、採点の準備をする。決着がついたらその結果、続けるなら null。 */
    private Result begin() {
        if (ports.stale()) return Result.STALE;
        AnchorPlanner.Anchor planned = AnchorPlanner.plan(kind, ports.environment(), source, chunkX, chunkZ, random, config);
        if (planned == null) return Result.NO_FIT;
        Object biome = source.getBiome(planned.x(), planned.y(), planned.z());
        choice = chooser.choose(biome, planned.y());
        if (choice == null) return Result.NO_SCHEMATIC;
        anchor = AnchorPlanner.clampUnderground(kind, ports.environment(), planned, choice.shape(), config);
        selector = new CandidateSelector(kind, ports.environment(), choice.shape(), anchor.x(), anchor.y(), anchor.z());
        return null;
    }

    /** 候補 i 以降を評価順に採点して、決着をつける。 */
    private CompletableFuture<Result> step(int i) {
        if (selector.isFinished() || i >= selector.candidateCount()) return onWorker(this::finish);
        if (ports.stale()) return CompletableFuture.completedFuture(Result.STALE);
        List<CompletableFuture<Void>> loads = new ArrayList<>();
        CandidateSelector.ChunkRange range = selector.neededChunks(i);
        for (int cx = range.minX(); cx <= range.maxX(); cx++) {
            for (int cz = range.minZ(); cz <= range.maxZ(); cz++) {
                if (!source.has(cx, cz)) loads.add(acquire(cx, cz));
            }
        }
        return CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0]))
                .thenCompose(ignored -> onWorker(() -> {
                    selector.score(i, source);
                    return null;
                }))
                .thenCompose(ignored -> step(i + 1));
    }

    private Result finish() {
        if (ports.stale()) return Result.STALE;
        CandidateSelector.Candidate best = selector.result();
        if (best == null) return Result.NO_FIT;
        PedestalSampler.Samples pedestal = kind == StructureType.SKY
                ? PedestalSampler.Samples.EMPTY
                : PedestalSampler.sample(source, choice.shape(),
                best.x() + choice.shape().offsetX(), best.y() + choice.shape().offsetY(), best.z() + choice.shape().offsetZ());
        return new Result(End.PLAN, new Plan(kind, choice.container(), choice.shape(), best.x(), best.y(), best.z(),
                best.score(), pedestal));
    }

    private CompletableFuture<Void> acquire(int cx, int cz) {
        return ports.acquire(cx, cz).thenAccept(data -> source.put(cx, cz, data));
    }

    private <T> CompletableFuture<T> onWorker(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        ports.worker(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }
}
