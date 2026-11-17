package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Stairs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 貼り付けエンジンの機能: 岩盤の置き換え、バリアと空気、2周目、看板の目印、ブロックエンティティ、容器、台座と木の除去、
 * 予算切れでも操作を落とさないこと。サーバーは MockBukkit、スケジューラと時計は {@link PasteTestKit} で手動。
 * （旧 SchematicPasteStatesTest の「岩盤は台座・バリアは地形を残す・空気は地形を削る」を新エンジンで置き換えたもの。）
 */
final class NativePasteEngineTest {

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

    private PasteResult run(PasteRequest request) {
        PasteEngine.PasteHandle handle = kit.engine.start(request);
        kit.tickUntil(handle::isDone, 2000);
        assertTrue(handle.isDone(), "貼り付けが終わらなかった");
        assertEquals(List.of(), kit.ticketViolations);
        return handle.completion().join();
    }

    private Material at(int x, int y, int z) {
        return kit.world.getBlockAt(x, y, z).getType();
    }

    @Test
    void 岩盤は台座材になりバリアは地形を残し空気は地形を削る() {
        var schem = new PasteTestKit.SchemBuilder(4, 3, 4);
        for (int x = 0; x < 4; x++)
            for (int z = 0; z < 4; z++) {
                schem.set(x, 0, z, Material.BEDROCK);
                schem.set(x, 1, z, x < 2 ? Material.STONE : Material.AIR);
                schem.set(x, 2, z, z == 0 ? Material.OAK_PLANKS : z == 1 ? Material.BARRIER : Material.AIR);
            }
        kit.world.getBlockAt(103, 72, -37).setType(Material.DIRT);
        kit.world.getBlockAt(100, 72, -39).setType(Material.DIRT);

        PasteResult result = run(kit.request(schem.build(), 100, 70, -40, new PasteTestKit.TestMarkers())
                .pedestalMaterialProvider(ground -> ground ? Material.GRASS_BLOCK : Material.COBBLESTONE).build());

        assertEquals(PasteResult.Outcome.COMPLETED, result.outcome());
        for (int x = 0; x < 4; x++)
            for (int z = 0; z < 4; z++) {
                assertEquals(x < 2 ? Material.COBBLESTONE : Material.GRASS_BLOCK, at(100 + x, 70, -40 + z), "岩盤は上が固体なら地中の台座材、空気なら地表の台座材");
                assertEquals(x < 2 ? Material.STONE : Material.AIR, at(100 + x, 71, -40 + z));
                Material top = z == 0 ? Material.OAK_PLANKS : x == 0 && z == 1 ? Material.DIRT : Material.AIR;
                assertEquals(top, at(100 + x, 72, -40 + z), "バリアは地形を残し、空気は地形を削る");
            }
        assertEquals(new Location(kit.world, 100, 70, -40), result.minCorner());
        assertEquals(new Location(kit.world, 103, 72, -37), result.maxCorner());
    }

    @Test
    void 岩盤の置き換え先を固定した時はその材に置き換え固体があれば置かない() {
        var schem = new PasteTestKit.SchemBuilder(2, 1, 1).set(0, 0, 0, Material.BEDROCK).set(1, 0, 0, Material.BEDROCK);
        kit.world.getBlockAt(11, 60, 5).setType(Material.DIRT);

        run(kit.request(schem.build(), 10, 60, 5, new PasteTestKit.TestMarkers()).bedrockReplacement(Material.STONE).build());

        assertEquals(Material.STONE, at(10, 60, 5));
        assertEquals(Material.DIRT, at(11, 60, 5), "固体の地形の上には置かない");
    }

    @Test
    void 光源と向き付きは2周目に回り計画の段が分かれる() {
        var schem = new PasteTestKit.SchemBuilder(3, 2, 1)
                .set(0, 0, 0, Material.STONE)
                .set(1, 0, 0, Material.STONE)
                .set(2, 0, 0, Material.STONE)
                .set(0, 1, 0, Material.STONE_BRICKS)
                .set(1, 1, 0, "minecraft:oak_stairs[facing=east,half=bottom]")
                .set(2, 1, 0, "minecraft:oak_stairs[facing=west,half=bottom]");
        PasteRequest request = kit.request(schem.build(), 0, 64, 0, new PasteTestKit.TestMarkers()).build();

        PastePlan plan = PastePlan.build(request);
        PastePlan.ChunkOps ops = plan.chunks().values().iterator().next();
        assertEquals(4, ops.blocks.size(), "下の3つの石と石レンガは1周目");
        // 光源（松明）の判定は模擬サーバーが光量を答えないため自動試験では確かめられない。向き付きで確かめる。
        assertEquals(2, ops.secondPass.size(), "階段（向き付き）は2周目");
        assertTrue(ops.blocks.stream().allMatch(op -> !PastePlan.needsSecondPass(op.block().blockData())));
        for (int i = 1; i < ops.blocks.size(); i++) {
            assertTrue(ops.blocks.get(i - 1).y() <= ops.blocks.get(i).y(), "Y の昇順");
        }

        run(request);
        assertEquals(Material.STONE_BRICKS, at(0, 65, 0));
        assertEquals(Material.OAK_STAIRS, at(2, 65, 0));
        Stairs stairs = (Stairs) kit.world.getBlockAt(1, 65, 0).getBlockData();
        assertEquals(org.bukkit.block.BlockFace.EAST, stairs.getFacing());
    }

    @Test
    void 看板は目印を処理して空気にし普通の看板は残し空文字の立ち看板は消える() {
        var schem = new PasteTestKit.SchemBuilder(4, 1, 1)
                .sign(0, 0, 0, Material.OAK_SIGN, "[m]")
                .sign(1, 0, 0, Material.OAK_SIGN, "こんにちは", "BS")
                .sign(2, 0, 0, Material.OAK_SIGN);
        PasteTestKit.TestMarkers markers = new PasteTestKit.TestMarkers();

        run(kit.request(schem.build(), 5, 80, 5, markers).build());

        assertEquals(List.of("5,80,5"), markers.handled);
        assertEquals(Material.AIR, at(5, 80, 5));
        assertEquals(Material.OAK_SIGN, at(6, 80, 5));
        // 看板の文字の復元は、模擬サーバーが Sign の状態を返さないため自動試験では確かめられない（報告の未確認に記載）。
        assertEquals(Material.AIR, at(7, 80, 5), "文字のない立ち看板は消える");
    }

    @Test
    void 目印でない看板を空気にする方針ならモジュール用に全部消える() {
        var schem = new PasteTestKit.SchemBuilder(2, 1, 1)
                .sign(0, 0, 0, Material.OAK_SIGN, "残さない")
                .sign(1, 0, 0, Material.OAK_SIGN, "[m]");
        PasteTestKit.TestMarkers markers = new PasteTestKit.TestMarkers(MarkerHandler.PlainSignPolicy.REMOVE);

        run(kit.request(schem.build(), 0, 80, 0, markers).build());

        assertEquals(Material.AIR, at(0, 80, 0));
        assertEquals(List.of("1,80,0"), markers.handled);
    }

    @Test
    void 台座列は下を埋め木は上を片付ける() {
        var schem = new PasteTestKit.SchemBuilder(2, 2, 2).layer(0, Material.STONE).layer(1, Material.AIR);
        kit.world.getBlockAt(0, 103, 0).setType(Material.OAK_LOG);
        kit.world.getBlockAt(0, 104, 0).setType(Material.OAK_LEAVES);
        kit.world.getBlockAt(0, 105, 0).setType(Material.STONE);

        run(kit.request(schem.build(), 0, 100, 0, new PasteTestKit.TestMarkers())
                .fillPedestal(true).clearTrees(true)
                .pedestalMaterialProvider(ground -> Material.DIRT).build());

        for (int depth = 1; depth <= PedestalFiller.MAX_DEPTH; depth++) {
            assertEquals(Material.DIRT, at(1, 100 - depth, 1), "台座は深さ " + depth);
        }
        assertEquals(Material.AIR, at(1, 100 - PedestalFiller.MAX_DEPTH - 1, 1), "深さの上限は10");
        assertEquals(Material.AIR, at(0, 103, 0), "木は消える");
        assertEquals(Material.AIR, at(0, 104, 0), "葉も消える");
        assertEquals(Material.STONE, at(0, 105, 0), "取り除ける素材が途切れたらそこで止まる");
    }

    @Test
    void 予算が切れても操作を落とさず次のtickに続けて全部置く() {
        var schem = new PasteTestKit.SchemBuilder(8, 4, 8).fill(Material.STONE);
        // ブロック1つに触れるたびに予算の上限の4分の1を使ったことにする。
        kit.costPerAccessNanos = kit.budget.limitNanos() / 4 + 1;

        PasteEngine.PasteHandle handle = kit.engine.start(kit.request(schem.build(), 0, 64, 0, new PasteTestKit.TestMarkers()).build());
        int ticks = kit.tickUntil(handle::isDone, 5000);

        assertTrue(handle.isDone());
        assertTrue(ticks > 10, "1 tick では終わらない（予算で区切られる）: " + ticks);
        assertFalse(kit.laterDelays.isEmpty(), "続きは runRegionLater で予約される");
        assertTrue(kit.laterDelays.stream().allMatch(delay -> delay >= 1L), "遅延は1以上: " + kit.laterDelays);
        int placed = 0;
        for (int x = 0; x < 8; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 8; z++)
                    if (at(x, 64 + y, z) == Material.STONE) placed++;
        assertEquals(8 * 4 * 8, placed, "1つも落としていない");
        assertEquals(List.of(), kit.ticketViolations);
    }

    @Test
    void 完了通知は完了結果と範囲を渡し一度だけ呼ぶ() {
        var schem = new PasteTestKit.SchemBuilder(2, 2, 2).fill(Material.STONE).set(0, 0, 0, Material.CHEST);
        List<PasteResult> results = new ArrayList<>();
        AtomicReference<PasteResult> single = new AtomicReference<>();

        PasteResult result = run(kit.request(schem.build(), 0, 64, 0, new PasteTestKit.TestMarkers())
                .onFinished(r -> {
                    results.add(r);
                    single.set(r);
                }).build());

        assertEquals(1, results.size());
        assertEquals(PasteResult.Outcome.COMPLETED, single.get().outcome());
        assertEquals(1, result.containers().size());
        assertEquals(new Location(kit.world, 0, 64, 0), result.containers().get(0));
        assertEquals(0, kit.engine.pendingCount());
    }

    @Test
    void 取り消すと途中で止まり取り消し結果になる() {
        var schem = new PasteTestKit.SchemBuilder(8, 4, 8).fill(Material.STONE);
        kit.costPerAccessNanos = kit.budget.limitNanos() / 4 + 1;
        PasteEngine.PasteHandle handle = kit.engine.start(kit.request(schem.build(), 0, 64, 0, new PasteTestKit.TestMarkers()).build());
        kit.tick();
        kit.tick();
        kit.tick();

        handle.cancel();
        kit.tickUntil(() -> kit.idle(), 100);

        assertTrue(handle.isDone());
        assertEquals(PasteResult.Outcome.CANCELLED, handle.completion().join().outcome());
        assertTrue(kit.tickets.isEmpty(), "取り消したら hold を全部返す: " + kit.tickets);
        assertEquals(0, kit.engine.pendingCount());
    }
}
