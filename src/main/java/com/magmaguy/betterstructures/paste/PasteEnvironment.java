package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.integration.RegionProtection;
import com.magmaguy.betterstructures.runtime.ChunkLoadLimiter;
import com.magmaguy.betterstructures.runtime.GenerationGovernor;
import com.magmaguy.betterstructures.runtime.Services;
import com.magmaguy.betterstructures.runtime.ThreadTickBudget;
import com.magmaguy.betterstructures.runtime.WorkerPool;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 貼り付けエンジンが頼る外の仕組み（チャンクの読み込み・スケジューラ・予算・流量制御・記録の場所）を
 * 1か所にまとめたもの。本番は {@link #production(Path)}、試験は差し替えて、サーバー無しで動かす。
 */
final class PasteEnvironment {

    /** チャンクの読み込み。完了は所有リージョンのスレッドで通知される（{@link ChunkLoadLimiter}）。 */
    interface Chunks {
        CompletableFuture<?> load(World world, int chunkX, int chunkZ);
    }

    /** チャンクの所有リージョンへ仕事を回す。 */
    interface Regions {
        void run(World world, int chunkX, int chunkZ, Runnable task);

        void runLater(World world, int chunkX, int chunkZ, Runnable task, long delayTicks);

        boolean isOwned(World world, int chunkX, int chunkZ);
    }

    /** グローバルリージョンでの遅延実行（読み込みの再試行の間隔）。 */
    interface Later {
        void run(Runnable task, long delayTicks);
    }

    private final Chunks chunks;
    private final Regions regions;
    private final Executor async;
    private final Later globalLater;
    private final Supplier<ThreadTickBudget> budgetSource;
    private final GenerationGovernor governor;
    private final Path journalPath;
    private final LongSupplier lifecycle;
    private final Supplier<RegionProtection> protection;
    private final Predicate<World> worldAlive;

    PasteEnvironment(Chunks chunks, Regions regions, Executor async, Later globalLater,
                     Supplier<ThreadTickBudget> budgetSource, GenerationGovernor governor, Path journalPath,
                     LongSupplier lifecycle, Supplier<RegionProtection> protection, Predicate<World> worldAlive) {
        this.chunks = chunks;
        this.regions = regions;
        this.async = async;
        this.globalLater = globalLater;
        this.budgetSource = budgetSource;
        this.governor = governor;
        this.journalPath = journalPath;
        this.lifecycle = lifecycle;
        this.protection = protection;
        this.worldAlive = worldAlive;
    }

    Chunks chunks() {
        return chunks;
    }

    Regions regions() {
        return regions;
    }

    Executor async() {
        return async;
    }

    Later globalLater() {
        return globalLater;
    }

    /** 現在のスレッドの予算。 */
    ThreadTickBudget budget() {
        return budgetSource.get();
    }

    GenerationGovernor governor() {
        return governor;
    }

    Path journalPath() {
        return journalPath;
    }

    long lifecycle() {
        return lifecycle.getAsLong();
    }

    RegionProtection protection() {
        return protection.get();
    }

    boolean worldAlive(World world) {
        return worldAlive.test(world);
    }

    /** 本番の環境。 */
    static PasteEnvironment production(Path journalPath) {
        return new PasteEnvironment(
                (world, x, z) -> ChunkLoadLimiter.instance().load(world, x, z, true),
                new Regions() {
                    @Override
                    public void run(World world, int chunkX, int chunkZ, Runnable task) {
                        PlatformScheduler.runRegion(world, chunkX, chunkZ, task);
                    }

                    @Override
                    public void runLater(World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
                        PlatformScheduler.runRegionLater(world, chunkX, chunkZ, task, Math.max(1L, delayTicks));
                    }

                    @Override
                    public boolean isOwned(World world, int chunkX, int chunkZ) {
                        return PlatformScheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ);
                    }
                },
                task -> WorkerPool.instance().execute(task),
                (task, delay) -> PlatformScheduler.runGlobalLater(task, Math.max(1L, delay)),
                ThreadTickBudget::current,
                GenerationGovernor.instance(),
                journalPath,
                PlatformScheduler::lifecycleGeneration,
                Services::regionProtection,
                world -> Bukkit.getWorld(world.getUID()) == world);
    }
}
