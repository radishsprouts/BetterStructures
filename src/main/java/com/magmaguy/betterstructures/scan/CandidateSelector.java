package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType;
import org.bukkit.World;

/**
 * 構造物の種類ごとに、候補の位置を旧実装（{@code Fit*Building#evaluate}）と同じ順に採点し、採用する位置を決める。
 * 純粋な状態機械で、ワールドにもスレッドにも依らない（採点は WorkerPool で呼ぶ）。
 *
 * <p>使い方: {@code for i in 0..candidateCount()}、{@link #isFinished()} が true なら止める。各 i で
 * {@link #neededChunks(int)} のチャンクを {@link BlockSource} に足してから {@link #score(int, BlockSource)} を呼ぶ。
 * 最後に {@link #result()}（採用なしは null）。
 *
 * <p>候補の順（旧実装と同じ。地表の (0,0) の二重走査だけ除く。同じ入力に同じ結果なので結果は変わらない）:
 * 地表は (0,0) → 「+」字の周囲 4 つ、地下・空・液体は (0,0) → 周囲 8。
 *
 * <p>地表は、地形の凹凸（{@link TopologyScorer}）が合格なら合計点が必ず 50 を超えて確定する
 * （凹凸の点は 0 か 85 以上、体積の点は 0 以上を足すだけ）ので、体積走査を省く。体積走査は位置の選択に効かない。
 */
public final class CandidateSelector {
    /** 周囲の候補（チャンク単位のずれ）。地表は「+」字。 */
    private static final int[][] SURFACE_ORDER = {{0, 0}, {-1, 0}, {0, -1}, {0, 1}, {1, 0}};
    /** 地下・空・液体は (0,0) の後に 8 近傍（x 外側、z 内側の昇順）。 */
    private static final int[][] FULL_ORDER = {
            {0, 0}, {-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};
    /** 点数の初期値。これ以下の適合は「合わない」とみなす。 */
    private static final double NO_FIT_SCORE = 10;
    /** 走査の間隔（旧 FitAnything#scanStep）。 */
    public static final int SCAN_STEP = 3;

    /** 採用した候補。 */
    public record Candidate(int chunkShiftX, int chunkShiftZ, int x, int y, int z, double score) {
    }

    /** 候補が読むチャンクの範囲（両端を含む）。 */
    public record ChunkRange(int minX, int maxX, int minZ, int maxZ) {
    }

    private final StructureType kind;
    private final boolean nether;
    private final SchematicShape shape;
    private final int anchorX;
    private final int anchorY;
    private final int anchorZ;
    private final int[][] order;
    private final double startingScore;

    private double best = NO_FIT_SCORE;
    private Candidate chosen;
    private boolean finished;

    public CandidateSelector(StructureType kind, World.Environment environment, SchematicShape shape,
                             int anchorX, int anchorY, int anchorZ) {
        this.kind = kind;
        this.nether = environment == World.Environment.NETHER;
        this.shape = shape;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.order = kind == StructureType.SURFACE ? SURFACE_ORDER : FULL_ORDER;
        this.startingScore = kind == StructureType.SURFACE && nether ? 200 : 100;
    }

    public int candidateCount() {
        return order.length;
    }

    public boolean isFinished() {
        return finished;
    }

    /** 採用した候補。まだ無ければ null。 */
    public Candidate result() {
        return chosen;
    }

    /** 今の最良点（初期値 10）。試験用。 */
    public double bestScore() {
        return best;
    }

    /** 候補 i の位置（オフセットを足す前）の X。 */
    private int candidateX(int i) {
        return anchorX + order[i][0] * 16;
    }

    private int candidateZ(int i) {
        return anchorZ + order[i][1] * 16;
    }

    /**
     * 候補 i の採点が読むブロックのチャンクの範囲。Y には依らない（読むのは図面の幅・奥行きの格子点の列だけ）。
     */
    public ChunkRange neededChunks(int i) {
        int x0 = candidateX(i) + shape.offsetX();
        int z0 = candidateZ(i) + shape.offsetZ();
        int lastX = ((shape.width() - 1) / SCAN_STEP) * SCAN_STEP;
        int lastZ = ((shape.depth() - 1) / SCAN_STEP) * SCAN_STEP;
        return new ChunkRange(Math.floorDiv(x0, 16), Math.floorDiv(x0 + lastX, 16),
                Math.floorDiv(z0, 16), Math.floorDiv(z0 + lastZ, 16));
    }

    /** 候補 i を採点して状態を進める。{@code neededChunks(i)} が {@code source} に入っていること。 */
    public void score(int i, BlockSource source) {
        if (finished) return;
        int x = candidateX(i);
        int z = candidateZ(i);
        switch (kind) {
            case SURFACE -> scoreSurface(i, source, x, z);
            case UNDERGROUND_SHALLOW, UNDERGROUND_DEEP -> scoreUnderground(i, source, x, z);
            case SKY -> scorePerfect(i, source, x, anchorY, z, AdequacyScorer.ScanType.AIR);
            case LIQUID_SURFACE -> scorePerfect(i, source, x, anchorY + 1, z, AdequacyScorer.ScanType.LIQUID);
            default -> throw new IllegalStateException("走査の対象でない種類: " + kind);
        }
    }

    private void scoreSurface(int i, BlockSource source, int x, int z) {
        TopologyScorer.Result topology = TopologyScorer.score(source, startingScore, SCAN_STEP, shape, x, z, nether);
        if (topology.passed()) {
            // 合格の凹凸の点は 85 以上なので、体積の点を足しても足さなくても、最良点（初期 10）を超え、50 も超える。
            double score = topology.score();
            if (score > best) {
                best = score;
                chosen = new Candidate(order[i][0], order[i][1], x, topology.y(), z, score);
            }
        }
        // 旧実装: 先頭の候補の後は「50 未満の間だけ」周囲を見る。周囲の候補は「50 を超えたら」止める。
        finished = i == 0 ? best >= 50 : best > 50;
    }

    private void scoreUnderground(int i, BlockSource source, int x, int z) {
        double minimum = nether ? 50 : 70;
        double score = AdequacyScorer.score(source, shape, SCAN_STEP, x, anchorY, z,
                AdequacyScorer.ScanType.UNDERGROUND, minimum, best);
        if (score != AdequacyScorer.ABORTED && score >= minimum && score > best) {
            best = score;
            chosen = new Candidate(order[i][0], order[i][1], x, anchorY, z, score);
        }
        finished = i == 0 ? best >= 90 : best > 90;
    }

    /** 空・液体: 満点の候補だけを採用し、最初の満点で止める。 */
    private void scorePerfect(int i, BlockSource source, int x, int y, int z, AdequacyScorer.ScanType type) {
        double score = AdequacyScorer.score(source, shape, SCAN_STEP, x, y, z, type, startingScore, Double.NEGATIVE_INFINITY);
        if (score != AdequacyScorer.ABORTED && score == startingScore) {
            best = score;
            chosen = new Candidate(order[i][0], order[i][1], x, y, z, score);
            finished = true;
        }
    }
}
