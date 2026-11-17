package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 千切れ防止の不変条件（04章 G1〜G5・G8）。書き込むチャンクのチケットが、段 1（BLOCKS）から DONE まで常に 1 以上であること。
 * {@link PasteTestKit} がブロックに触れる瞬間と、チャンクのタスクの開始時にチケットを検査して違反を記録する。
 * これらの試験が落ちたらビルドが落ちる。
 */
final class PasteChunkSafetyTest {

    @TempDir
    Path temp;
    private PasteTestKit kit;

    @BeforeEach
    void setUp() {
        kit = new PasteTestKit(temp.resolve("journal.json"));
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    /** x 方向に 48 ブロック（チャンク 0・1・2 をまたぐ）の石の帯。 */
    private PasteRequest.Builder band() {
        var schem = new PasteTestKit.SchemBuilder(48, 1, 2).fill(Material.STONE);
        return kit.request(schem.build(), 0, 64, 0, new PasteTestKit.TestMarkers());
    }

    private long placed() {
        long count = 0;
        for (int x = 0; x < 48; x++)
            for (int z = 0; z < 2; z++)
                if (kit.world.getBlockAt(x, 64, z).getType() == Material.STONE) count++;
        return count;
    }

    private void slowDown() {
        // ブロック1回で予算の 4 分の 1 を使ったことにして、チャンクごとに複数 tick かかる状況にする。
        kit.costPerAccessNanos = kit.budget.limitNanos() / 4 + 1;
    }

    @Test
    void 全チャンクのチケットは書き込みの最初からDONEまで付いたままで途中で外れない() {
        slowDown();
        PasteEngine.PasteHandle handle = kit.engine.start(band().build());
        kit.tickUntil(handle::isDone, 5000);

        assertTrue(handle.isDone());
        assertEquals(List.of(), kit.ticketViolations);
        for (String chunk : List.of("0,0", "1,0", "2,0")) {
            List<String> history = kit.ticketHistory.stream().filter(e -> e.endsWith(chunk)).collect(Collectors.toList());
            assertEquals(List.of("+" + chunk, "-" + chunk), history, "付けて、DONE の後に外すだけ（付け外しを繰り返さない）");
        }
        assertEquals(96, placed());
        assertTrue(kit.tickets.isEmpty());
    }

    @Test
    void 走査から引き継いだholdはtransferでカウント0にならずDONEの後に初めて外れる() {
        PluginChunkHolds.HoldSet scan = new PluginChunkHolds.HoldSet(kit.world);
        scan.hold(0, 0);
        scan.hold(1, 0);
        scan.hold(2, 0);
        kit.server.getScheduler().performTicks(2L);
        assertEquals(Set.of("0,0", "1,0", "2,0"), kit.tickets);
        slowDown();

        PasteEngine.PasteHandle handle = kit.engine.start(band().heldChunks(scan).build());
        kit.tickUntil(handle::isDone, 5000);

        assertTrue(handle.isDone());
        assertEquals(List.of(), kit.ticketViolations);
        assertEquals(0, scan.size(), "走査側の hold は引き継がれて空");
        for (String chunk : List.of("0,0", "1,0", "2,0")) {
            List<String> history = kit.ticketHistory.stream().filter(e -> e.endsWith(chunk)).collect(Collectors.toList());
            assertEquals(List.of("+" + chunk, "-" + chunk), history, "引き継ぎの間に外れていない");
        }
    }

    @Test
    void bsReloadの世代が進んでも走っている貼り付けは取り消されず最後まで使い切る() {
        slowDown();
        PasteEngine.PasteHandle handle = kit.engine.start(band().build());
        kit.tick();
        kit.tick();
        kit.tick();
        assertFalse(handle.isDone());
        assertFalse(kit.tickets.isEmpty());

        long before = PlatformScheduler.reloadGeneration();
        PlatformScheduler.advanceReloadGeneration();
        assertTrue(PlatformScheduler.reloadGeneration() > before);
        kit.tick();
        assertFalse(handle.isDone(), "reload で取り消されない");
        assertFalse(kit.tickets.isEmpty(), "reload の途中でも hold は外れない");

        kit.tickUntil(handle::isDone, 5000);
        assertEquals(PasteResult.Outcome.COMPLETED, handle.completion().join().outcome());
        assertEquals(96, placed());
        assertEquals(List.of(), kit.ticketViolations);
    }

    @Test
    void 窓付きholdは今のチャンクとその先K個だけを持ちDONEの後に手放す() {
        slowDown();
        PasteEngine.PasteHandle handle = kit.engine.start(band().windowChunks(1).build());
        kit.tick();
        kit.tick();
        assertEquals(Set.of("0,0", "1,0"), kit.tickets, "チャンク 0 を処理中は 0 と先の 1 個だけ");

        kit.tickUntil(handle::isDone, 5000);
        assertTrue(handle.isDone());
        assertEquals(List.of(), kit.ticketViolations);
        assertEquals(96, placed());
        assertTrue(kit.tickets.isEmpty());
    }

    @Test
    void 読み込みの失敗は間隔を伸ばして再試行し成功すれば続きから完了する() {
        // チャンク 1 の最初の 2 回の読み込みだけ失敗する。
        long key = org.bukkit.Chunk.getChunkKey(1, 0);
        kit.loadFailure = (k, attempt) -> k == key && attempt <= 2 ? new IllegalStateException("一時的な失敗") : null;

        PasteEngine.PasteHandle handle = kit.engine.start(band().build());
        kit.tickUntil(handle::isDone, 5000);

        assertEquals(PasteResult.Outcome.COMPLETED, handle.completion().join().outcome());
        assertEquals(List.of(PasteSession.RETRY_BASE_TICKS, PasteSession.RETRY_BASE_TICKS * 2), kit.globalDelays,
                "再試行の間隔は1回ごとに伸びる");
        assertEquals(96, placed(), "失敗したチャンクも落とさず貼る");
        assertEquals(List.of(), kit.ticketViolations);
    }

    @Test
    void 読み込みが上限を超えて失敗し続けたら警告して終え_holdを全部返す() {
        kit.loadFailure = (k, attempt) -> new IllegalStateException("読み込めない");

        PasteEngine.PasteHandle handle = kit.engine.start(band().build());
        kit.tickUntil(handle::isDone, 5000);

        assertEquals(PasteResult.Outcome.FAILED, handle.completion().join().outcome());
        assertEquals(PasteSession.MAX_LOAD_ATTEMPTS, kit.globalDelays.size());
        assertTrue(kit.tickets.isEmpty(), "失敗で終えても hold は残さない: " + kit.tickets);
        assertEquals(0, kit.engine.pendingCount());
    }
}
