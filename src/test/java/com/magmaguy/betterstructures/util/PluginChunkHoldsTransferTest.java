package com.magmaguy.betterstructures.util;

import com.magmaguy.betterstructures.MetadataHandler;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 千切れ防止の不変条件(G2): 走査の持ち主から貼り付けの持ち主へ hold を引き継いでも、
 * チャンクのチケットが外れる瞬間（参照カウント 0）を作らない。
 */
final class PluginChunkHoldsTransferTest {
    private final World world = mock(World.class);
    private final World otherWorld = mock(World.class);
    private ServerMock server;

    /** world に付いているチケットと、その付け外しの履歴（"+x,z" / "-x,z"）。 */
    private final Set<String> tickets = new HashSet<>();
    private final List<String> history = new ArrayList<>();

    @BeforeEach
    void setUp() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        when(world.getUID()).thenReturn(id);
        when(otherWorld.getUID()).thenReturn(otherId);
        doAnswer(call -> {
            String key = call.getArgument(0) + "," + call.getArgument(1);
            history.add("+" + key);
            return tickets.add(key);
        }).when(world).addPluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
        doAnswer(call -> {
            String key = call.getArgument(0) + "," + call.getArgument(1);
            history.add("-" + key);
            return tickets.remove(key);
        }).when(world).removePluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));

        server = MockBukkit.mock(new ServerMock() {
            @Override
            public World getWorld(UUID uid) {
                if (uid.equals(id)) return world;
                if (uid.equals(otherId)) return otherWorld;
                return null;
            }
        });
        MetadataHandler.PLUGIN = MockBukkit.createMockPlugin("PluginChunkHoldsTransferFixture");
    }

    @AfterEach
    void tearDown() {
        PluginChunkHolds.shutdown();
        MetadataHandler.PLUGIN = null;
        MockBukkit.unmock();
    }

    /** 予約されたリージョンのタスクを流す（Paper では主スレッドで直接実行されるので何も残らない）。 */
    private void flush() {
        server.getScheduler().performTicks(2L);
    }

    @Test
    void 引き継ぎの間チケットが一度も外れない() {
        PluginChunkHolds.HoldSet scan = new PluginChunkHolds.HoldSet(world);
        scan.hold(1, 2);
        scan.hold(-3, 4);
        flush();
        assertEquals(Set.of("1,2", "-3,4"), tickets);

        PluginChunkHolds.HoldSet paste = new PluginChunkHolds.HoldSet(world);
        PluginChunkHolds.transfer(scan, paste);
        flush();

        assertEquals(Set.of("1,2", "-3,4"), tickets, "引き継ぎ後もチケットは付いたまま");
        assertTrue(history.stream().noneMatch(entry -> entry.startsWith("-")), "引き継ぎでチケットを外してはいけない: " + history);
        assertEquals(0, scan.size());
        assertEquals(2, paste.size());
        assertTrue(paste.contains(1, 2));
    }

    @Test
    void 引き継ぎ先が最後にreleaseして初めてチケットが外れる() {
        PluginChunkHolds.HoldSet scan = new PluginChunkHolds.HoldSet(world);
        scan.hold(5, 5);
        PluginChunkHolds.HoldSet paste = new PluginChunkHolds.HoldSet(world);
        PluginChunkHolds.transfer(scan, paste);
        flush();

        assertEquals(Set.of("5,5"), tickets);
        paste.releaseAll();
        flush();
        assertTrue(tickets.isEmpty());
        assertEquals(List.of("+5,5", "-5,5"), history);
    }

    @Test
    void 同じチャンクを引き継ぎ先も既に持っていてもカウントは0にならない() {
        PluginChunkHolds.HoldSet scan = new PluginChunkHolds.HoldSet(world);
        PluginChunkHolds.HoldSet paste = new PluginChunkHolds.HoldSet(world);
        scan.hold(7, 7);
        paste.hold(7, 7);
        PluginChunkHolds.transfer(scan, paste);
        flush();

        assertEquals(Set.of("7,7"), tickets);
        assertEquals(1, paste.size());
        assertFalse(history.stream().anyMatch(entry -> entry.startsWith("-")), history.toString());
    }

    @Test
    void 持ち主の中では同じチャンクを二重にholdしない() {
        PluginChunkHolds.HoldSet set = new PluginChunkHolds.HoldSet(world);
        assertTrue(set.hold(0, 0));
        assertFalse(set.hold(0, 0));
        assertEquals(1, set.size());

        assertTrue(set.release(0, 0));
        assertFalse(set.release(0, 0));
        flush();
        assertTrue(tickets.isEmpty());
    }

    @Test
    void 別の持ち主が同じチャンクを持つ間は片方がreleaseしても外れない() {
        PluginChunkHolds.HoldSet first = new PluginChunkHolds.HoldSet(world);
        PluginChunkHolds.HoldSet second = new PluginChunkHolds.HoldSet(world);
        first.hold(2, 2);
        second.hold(2, 2);
        first.releaseAll();
        flush();
        assertEquals(Set.of("2,2"), tickets);
        second.releaseAll();
        flush();
        assertTrue(tickets.isEmpty());
    }

    @Test
    void ワールドが違う持ち主どうしの引き継ぎは拒否する() {
        PluginChunkHolds.HoldSet a = new PluginChunkHolds.HoldSet(world);
        PluginChunkHolds.HoldSet b = new PluginChunkHolds.HoldSet(otherWorld);
        a.hold(0, 0);
        assertThrows(IllegalArgumentException.class, () -> PluginChunkHolds.transfer(a, b));
        assertEquals(1, a.size(), "拒否した時は元の持ち主の hold を保つ");
    }
}
