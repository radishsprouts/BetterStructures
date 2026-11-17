package com.magmaguy.betterstructures;

import com.magmaguy.betterstructures.config.schematics.SchematicConfig;
import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.initialization.PluginInitializationContext;
import com.magmaguy.magmacore.initialization.PluginInitializationManager;
import com.magmaguy.magmacore.initialization.PluginInitializationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Uses the real reload entry, initialization owner, scheduler, completion and disable path. */
class ContentReloadLifecycleTest {
    private ServerMock server;
    private BetterStructures plugin;
    private MagmaCore previousCore;
    private final CountDownLatch releaseWorker = new CountDownLatch(1);

    @BeforeEach
    void open() throws Exception {
        server = MockBukkit.mock();
        var owner = MockBukkit.createMockPlugin("BetterStructuresFixture" + UUID.randomUUID().toString().replace("-", ""));
        plugin = mock(BetterStructures.class, CALLS_REAL_METHODS);
        doReturn(owner.getDescription()).when(plugin).getDescription();
        doReturn(owner.getPluginMeta()).when(plugin).getPluginMeta();
        doReturn(owner.getDataFolder()).when(plugin).getDataFolder();
        doReturn(owner.getLogger()).when(plugin).getLogger();
        doReturn(owner.getPluginLoader()).when(plugin).getPluginLoader();
        doReturn(server).when(plugin).getServer();
        doReturn(null).when(plugin).getResource(anyString());
        // 有効フラグは JavaPlugin の private フィールドなので、リフレクションで書かず、
        // 本物の setEnabled(true) 経由で立てる（onEnable は本番の初期化を走らせないよう空にする）。
        doNothing().when(plugin).onEnable();
        plugin.prepareReloadStateForTest();
        MetadataHandler.PLUGIN = plugin;
        previousCore = MagmaCore.getInstance();
        MagmaCore.createInstance(plugin);
        PluginInitializationManager.onEnable(plugin);
        PluginInitializationManager.setState(plugin, PluginInitializationState.INITIALIZED);
        SchematicConfig.prepareForEnable();
        Files.createDirectories(plugin.getDataFolder().toPath());
    }

    @AfterEach
    void close() throws Exception {
        releaseWorker.countDown();
        try {
            if (plugin != null) plugin.onDisable();
            if (server != null) server.getScheduler().waitAsyncTasksFinished();
        } finally {
            MetadataHandler.PLUGIN = null;
            MockBukkit.unmock();
        }
    }

    @Test
    void contentRemainsLoadingUntilWorkerCompletionAndQueuedReloadsAreCoalesced() throws Exception {
        var started = new CountDownLatch(1);
        var loads = new AtomicInteger();
        doAnswer(call -> {
            if (loads.incrementAndGet() == 1) {
                started.countDown();
                assertTrue(releaseWorker.await(5, TimeUnit.SECONDS));
            }
            return null;
        }).when(plugin).loadContent(any());

        plugin.reloadImportedContent(server.getConsoleSender());
        assertTrue(BetterStructures.isReloading());
        assertEquals(PluginInitializationState.INITIALIZING, MagmaCore.getInitializationState(plugin.getName()));
        server.getScheduler().performOneTick();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        plugin.reloadImportedContent(server.getConsoleSender());
        plugin.reloadImportedContent(server.getConsoleSender());

        releaseWorker.countDown();
        finishScheduledWork(() -> loads.get() == 2 && !BetterStructures.isReloading());

        assertEquals(2, loads.get());
        assertFalse(BetterStructures.isReloading());
        assertEquals(PluginInitializationState.INITIALIZED, MagmaCore.getInitializationState(plugin.getName()));
        assertTrue(Files.isDirectory(plugin.getDataFolder().toPath().resolve("components")));
    }

    @Test
    void disableWaitsForItsActiveReloadWorkerAndSuppressesLateCompletion() throws Exception {
        var started = new CountDownLatch(1);
        var stopped = new AtomicBoolean();
        doAnswer(call -> {
            PluginInitializationContext context = call.getArgument(0);
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!context.isShutdownRequested() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(context.isShutdownRequested(), "disable must cancel the reload's managed context");
            stopped.set(true);
            return null;
        }).when(plugin).loadContent(any());

        plugin.reloadImportedContent(null);
        server.getScheduler().performOneTick();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        plugin.onDisable();

        assertTrue(stopped.get(), "disable must not return while the worker can still read the plugin jar");
        server.getScheduler().performTicks(2);
        assertFalse(BetterStructures.isReloading());
        assertEquals(PluginInitializationState.UNINITIALIZED, MagmaCore.getInitializationState(plugin.getName()));
        assertFalse(Files.exists(plugin.getDataFolder().toPath().resolve("components")));
    }

    @Test
    void failedContentReloadDisablesOutsideItsInitializationCallback() {
        doThrow(new IllegalStateException("Fixture content failure")).when(plugin).loadContent(any());

        plugin.reloadImportedContent(null);
        finishScheduledWork(() -> !plugin.isEnabled());

        assertFalse(plugin.isEnabled());
        assertFalse(BetterStructures.isReloading());
        assertEquals(PluginInitializationState.UNINITIALIZED, MagmaCore.getInitializationState(plugin.getName()));
    }

    private void finishScheduledWork(BooleanSupplier completed) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!completed.getAsBoolean() && System.nanoTime() < deadline) {
            server.getScheduler().performOneTick();
            // The managed completion callback can schedule its successor after an executor snapshot.
            // Wait for the real lifecycle outcome; MockBukkit's wait helper cancels repeating work.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertTrue(completed.getAsBoolean(), "The managed content reload did not reach its terminal state");
    }
}
