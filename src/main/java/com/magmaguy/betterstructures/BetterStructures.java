package com.magmaguy.betterstructures;

import com.magmaguy.betterstructures.commands.*;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.ValidWorldsConfig;
import com.magmaguy.betterstructures.config.components.ComponentsConfigFolder;
import com.magmaguy.betterstructures.config.contentpackages.ContentPackageConfig;
import com.magmaguy.betterstructures.config.generators.GeneratorConfig;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfig;
import com.magmaguy.betterstructures.config.modules.ModulesConfig;
import com.magmaguy.betterstructures.config.schematics.SchematicConfig;
import com.magmaguy.betterstructures.config.spawnpools.SpawnPoolsConfig;
import com.magmaguy.betterstructures.config.treasures.TreasureConfig;
import com.magmaguy.betterstructures.content.BSPackage;
import com.magmaguy.betterstructures.content.BSPackageRefresher;
import com.magmaguy.betterstructures.listeners.FirstTimeSetupWarner;
import com.magmaguy.betterstructures.listeners.NewChunkLoadEvent;
import com.magmaguy.betterstructures.modules.ModulesContainer;
import com.magmaguy.betterstructures.modules.WFCGenerator;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.thirdparty.EliteMobs;
import com.magmaguy.betterstructures.thirdparty.WorldGuard;
import com.magmaguy.betterstructures.util.ChunkPregenerator;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import com.magmaguy.betterstructures.util.RegistrySnapshot;
import com.magmaguy.betterstructures.paste.PasteEngine;
import com.magmaguy.betterstructures.runtime.ChunkLoadLimiter;
import com.magmaguy.betterstructures.runtime.FlowSettings;
import com.magmaguy.betterstructures.runtime.GenerationGovernor;
import com.magmaguy.betterstructures.runtime.RuntimeSettings;
import com.magmaguy.betterstructures.runtime.Services;
import com.magmaguy.betterstructures.runtime.ThreadTickBudget;
import com.magmaguy.betterstructures.runtime.WorkerPool;
import com.magmaguy.betterstructures.worldedit.SchematicClipboardCache;
import com.magmaguy.betterstructures.worldedit.WorldEditCompatibility;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.dlc.ConfigurationImporter;
import com.magmaguy.magmacore.initialization.PluginInitializationConfig;
import com.magmaguy.magmacore.initialization.PluginInitializationContext;
import com.magmaguy.magmacore.initialization.PluginInitializationState;
import com.magmaguy.magmacore.nightbreak.NightbreakApplyPluginUpdateCommand;
import com.magmaguy.magmacore.nightbreak.NightbreakDownloadContentCommand;
import com.magmaguy.magmacore.nightbreak.NightbreakDownloadEverythingCommand;
import com.magmaguy.magmacore.nightbreak.NightbreakDownloadPluginUpdateCommand;
import com.magmaguy.magmacore.nightbreak.NightbreakForceReinstallContentCommand;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginSpec;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginUpdater;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginStateRegistry;
import com.magmaguy.magmacore.nightbreak.NightbreakRecommendedPluginsCommand;
import com.magmaguy.magmacore.util.Logger;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class BetterStructures extends JavaPlugin {
    private volatile boolean contentReloadInProgress;
    private List<CommandSender> activeReloadSenders = new ArrayList<>();
    private List<CommandSender> queuedReloadSenders = new ArrayList<>();

    /**
     * 試験専用。コンストラクタを通さずに作ったモックではフィールド初期化子が走らないため、
     * 再読み込みの送信者リストをここで用意する（リフレクションで差し込まないための口）。
     */
    void prepareReloadStateForTest() {
        activeReloadSenders = new ArrayList<>();
        queuedReloadSenders = new ArrayList<>();
        // 有効フラグも初期化子が走らないモックでは立たない。onEnable は試験側で空にしてから呼ぶ。
        setEnabled(true);
    }

    public static final NightbreakPluginSpec NIGHTBREAK_PLUGIN_SPEC = new NightbreakPluginSpec(
            "BetterStructures",
            "bs",
            "betterstructures.*",
            "betterstructures.setup",
            "betterstructures.initialize",
            "https://nightbreak.io/plugin/betterstructures/",
            "Reloaded BetterStructures.");

    @Override
    public void onEnable() {
        MetadataHandler.PLUGIN = this;
        SchematicConfig.prepareForEnable();
        // 流量制御の振り分けタイマー（グローバルリージョンの1tick周期。振り分けるだけで止めない）。
        // PasteEngine / RegionProtection を Services へ入れる配線は、実装クラスが入った統合時に足す。
        GenerationGovernor.instance().startTimer();
        Bukkit.getLogger().info("    ____       __  __            _____ __                  __                      ");
        Bukkit.getLogger().info("   / __ )___  / /_/ /____  _____/ ___// /________  _______/ /___  __________  _____");
        Bukkit.getLogger().info("  / __  / _ \\/ __/ __/ _ \\/ ___/\\__ \\/ __/ ___/ / / / ___/ __/ / / / ___/ _ \\/ ___/");
        Bukkit.getLogger().info(" / /_/ /  __/ /_/ /_/  __/ /   ___/ / /_/ /  / /_/ / /__/ /_/ /_/ / /  /  __(__  ) ");
        Bukkit.getLogger().info("/_____/\\___/\\__/\\__/\\___/_/   /____/\\__/_/   \\__,_/\\___/\\__/\\__,_/_/   \\___/____/");
        // Plugin startup logic
        Bukkit.getLogger().info("[BetterStructures] Initialized version " + this.getDescription().getVersion() + "!");
        MagmaCore.onEnable(this);
        MagmaCore.exportSharedAssets(this);
        SchematicContainer.updateOptionalPluginAvailability(
                Bukkit.getPluginManager().isPluginEnabled("EliteMobs"),
                Bukkit.getPluginManager().isPluginEnabled("MythicMobs"));
        // Content parsing runs on a worker thread and must not resolve registry entries itself.
        RegistrySnapshot.capture();
        if (PlatformScheduler.isFolia()) {
            startFoliaInitialization();
            return;
        }
        MagmaCore.startInitialization(this,
                new PluginInitializationConfig("BetterStructures", "betterstructures.*", 17, resolveInitializationDependencies()),
                this::asyncInitialization,
                this::syncInitialization,
                () -> {
                    Logger.info("BetterStructures fully initialized!");
                    NightbreakPluginUpdater.autoDownloadPluginUpdateIfEnabled(this, NIGHTBREAK_PLUGIN_SPEC);
                    CommandSender pendingReloadSender = NightbreakPluginStateRegistry.consumePendingReloadSender(this);
                    if (pendingReloadSender == null) {
                        pendingReloadSender = MetadataHandler.pendingReloadSender;
                    }
                    if (pendingReloadSender != null) {
                        Logger.sendMessage(pendingReloadSender, NIGHTBREAK_PLUGIN_SPEC.reloadSuccessMessage());
                        MetadataHandler.pendingReloadSender = null;
                    }
                },
                throwable -> {
                    MetadataHandler.pendingReloadSender = null;
                    throwable.printStackTrace();
                });
    }

    @Override
    public void onLoad() {
        MagmaCore.createInstance(this);
        try {
            if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null &&
                    Bukkit.getPluginManager().getPlugin("EliteMobs") != null)
                WorldGuard.initializeFlag();
            else
                Logger.info("WorldGuard is not enabled! WorldGuard is recommended when using the EliteMobs integration.");
        } catch (Exception ex) {
            Logger.info("WorldGuard could not be detected! Some BetterStructures features use WorldGuard, and they will not work until it is installed.");
        }
    }

    private volatile Metrics metrics;
    /** 計測ログ（runtimeMetricsLogging）の周期タスク。グローバルリージョンで読むだけ。 */
    private volatile PlatformScheduler.TaskHandle metricsTask;

    /**
     * 停止時の実行時基盤の後片付け。Bukkit のサーバーに触れないので試験から直接呼べる。
     * 順序（07章）: 振り分けを止める → 貼り付けエンジンの最後の記録（同期。停止処理の中だけ許す）
     * → 待ち行列を空にする → チャンク読み込みの依頼を終える → ワーカーを止める → 受け渡し口を空にする。
     */
    static void shutdownRuntimeServices() {
        GenerationGovernor governor = GenerationGovernor.instance();
        // 新しい仕事を取り出さない（枠に入ったものはそのまま終わる）。
        governor.stopTimer();
        PasteEngine engine = Services.pasteEngineOrNull();
        if (engine != null) {
            try {
                engine.shutdown();
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger("BetterStructures").warning("貼り付けエンジンの停止処理に失敗しました: " + t);
            }
        }
        governor.clear();
        GenerationGovernor.shutdown();
        ChunkLoadLimiter.shutdown();
        WorkerPool.shutdownInstance();
        Services.clear();
    }

    /** WorldEdit の型を持つ旧クラスの呼び出しを、読み込みの失敗（LinkageError）だけ握って続ける。 */
    private static void runIgnoringLinkageError(Runnable action) {
        try {
            action.run();
        } catch (LinkageError error) {
            java.util.logging.Logger.getLogger("BetterStructures").warning("WorldEdit が無いため読み込めないクラスの後片付けを省きました: " + error);
        }
    }

    /**
     * 計測ログの周期タスクを、現在の設定で作り直す。設定の読み込み後（起動・再読み込み）に呼ぶ。
     * 切ってあれば何も走らせない。グローバルリージョンで数値を読んで1行出すだけで、何も止めない。
     */
    void restartMetricsLogging() {
        PlatformScheduler.TaskHandle old = metricsTask;
        metricsTask = null;
        if (old != null) old.cancel();
        FlowSettings settings = RuntimeSettings.get();
        if (!settings.metricsEnabled()) return;
        long period = Math.max(1L, settings.metricsIntervalSeconds() * 20L);
        metricsTask = PlatformScheduler.runGlobalTimer(() -> Logger.info(metricsLine()), period, period);
    }

    /**
     * 計測ログの1行。待ち行列の深さ（レーンごと）、チャンク読み込みの数、ワーカーの件数、
     * このスレッドの予算の上限、貼り付けエンジンの件数。読むだけで、何も変えない。
     * 予算はスレッドごとなので、ここで出るのは呼んだスレッド（周期タスクならグローバルリージョン）の値。
     */
    static String metricsLine() {
        GenerationGovernor governor = GenerationGovernor.instance();
        ChunkLoadLimiter limiter = ChunkLoadLimiter.instance();
        WorkerPool pool = WorkerPool.instance();
        PasteEngine engine = Services.pasteEngineOrNull();
        long budgetMicros = ThreadTickBudget.current().limitNanos() / 1_000L;
        return "[runtime] 待ち行列(待ち/実行) 走査=" + governor.waiting(GenerationGovernor.Lane.SCAN)
                + "/" + governor.running(GenerationGovernor.Lane.SCAN)
                + " 貼り付け=" + governor.waiting(GenerationGovernor.Lane.PASTE)
                + "/" + governor.running(GenerationGovernor.Lane.PASTE)
                + " ダンジョン=" + governor.waiting(GenerationGovernor.Lane.WFC)
                + "/" + governor.running(GenerationGovernor.Lane.WFC)
                + " | チャンク読み込み 実行中=" + limiter.activeCount() + " 待ち=" + limiter.queuedCount()
                + " | ワーカー 待ち=" + pool.queuedCount() + " スレッド=" + pool.threadCount()
                + " | 予算の上限=" + (budgetMicros / 1000.0) + "ms"
                + " | 貼り付け中=" + (engine == null ? "-" : String.valueOf(engine.pendingCount()));
    }

    @Override
    public void onDisable() {
        // bStats runs its own scheduler thread; without this every reload leaves one
        // reporting for, and holding on to, the previous instance.
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
        contentReloadInProgress = false;
        activeReloadSenders.clear();
        queuedReloadSenders.clear();
        boolean shutdownDuringInitialization =
                MagmaCore.getInitializationState(this.getName())
                        == PluginInitializationState.INITIALIZING;
        MagmaCore.requestInitializationShutdown(this);
        if (!SchematicConfig.shutdownLoading()) {
            Bukkit.getLogger().severe(
                    "[BetterStructures] Timed out waiting for schematic loading to stop; "
                            + "shutdown will continue after the bounded wait.");
        }
        // Both startup and content reload use this owner. Wait before clearing registries
        // or returning control to Bukkit, which may close the plugin's resource jar.
        MagmaCore.shutdown(this);
        // 計測ログのタイマーを止める（世代で取り消されるが、先に外しておく）。
        if (metricsTask != null) {
            metricsTask.cancel();
            metricsTask = null;
        }
        // 新しい依頼を止め、貼り付けエンジンの最後の記録を同期で書き、待ち行列・読み込み・ワーカーを片付ける。
        shutdownRuntimeServices();
        com.magmaguy.betterstructures.schematic.paste.StructurePasteSession.shutdownAll();
        // WorldEdit の型を持つ旧クラスは、WorldEdit が無い環境では読み込み自体が失敗する。
        // 失敗しても停止処理の残りを続けるため、読み込みの失敗（LinkageError）だけを握る。
        runIgnoringLinkageError(SchematicClipboardCache::shutdown);
        runIgnoringLinkageError(SchematicContainer::shutdown);
        NewChunkLoadEvent.shutdown();
        ChunkPregenerator.shutdown();
        BSPackage.shutdown();
        runIgnoringLinkageError(ModulesContainer::shutdown);
        WFCGenerator.shutdown();
        // 貼り付けの後片付けが済んだ後で hold の記録を空にする。
        PluginChunkHolds.shutdown();
        PlatformScheduler.cancelAllTasks();
        HandlerList.unregisterAll(MetadataHandler.PLUGIN);
        if (shutdownDuringInitialization) {
            Bukkit.getLogger().info("[BetterStructures] Shutdown during initialization.");
            return;
        }
        Bukkit.getLogger().info("[BetterStructures] Shutdown!");
    }

    /**
     * Decides whether initialization has to queue behind the plugins listed in plugin.yml softdepend.
     * <p>
     * By default MagmaCore holds a plugin's whole async phase until every present softdepend has
     * finished initializing. For BetterStructures that meant its schematic loading ran strictly
     * after EliteMobs finished, serialising two long, unrelated workloads back to back.
     * <p>
     * Nothing in the async phase actually needs EliteMobs to be *initialized* — schematic parsing
     * only needs to know whether EliteMobs is *installed*, which is already resolved in onEnable
     * via SchematicContainer.updateOptionalPluginAvailability. The one exception is the content
     * importer: when it deposits files into another plugin's data folder, that plugin has to be
     * told to reload, and reloading a plugin mid-initialization is not safe. So the wait is kept
     * only when there is actually something to import, which is rare (a fresh DLC zip in imports).
     *
     * @return null to use the plugin.yml softdepend list, or an empty list to wait for nobody
     */
    private List<String> resolveInitializationDependencies() {
        File importsFolder = new File(getDataFolder(), "imports");
        String[] pendingImports = importsFolder.list();
        boolean hasPendingImports = pendingImports != null && pendingImports.length > 0;
        if (hasPendingImports) {
            Logger.info("Content is pending import; waiting for other plugins before initializing.");
            return null;
        }
        return List.of();
    }

    private void startFoliaInitialization() {
        PlatformScheduler.runAsync(() -> {
            try {
                asyncInitialization(null);
                PlatformScheduler.runGlobal(() -> {
                    try {
                        syncInitialization(null);
                        Logger.info("BetterStructures fully initialized!");
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                });
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void asyncInitialization(PluginInitializationContext initializationContext) {
        if (initializationContext != null) initializationContext.step("Base Configs");
        new DefaultConfig();
        loadContent(initializationContext);
    }

    void loadContent(PluginInitializationContext initializationContext) {
        if (initializationContext != null && initializationContext.isShutdownRequested()) return;
        if (initializationContext != null) initializationContext.step("Content Importer");
        ConfigurationImporter importer = MagmaCore.initializeImporter(this);
        if (initializationContext != null && initializationContext.isShutdownRequested()) return;
        if (importer != null && importer.isEliteMobsContentImported())
            EliteMobs.reloadAfterContentImport();

        if (initializationContext != null) initializationContext.step("Treasure Config");
        new TreasureConfig();
        if (initializationContext != null && initializationContext.isShutdownRequested()) return;
        if (initializationContext != null) initializationContext.step("Generator Config");
        new GeneratorConfig();
        if (initializationContext != null && initializationContext.isShutdownRequested()) return;
        if (initializationContext != null) initializationContext.step("Module Generators");
        new ModuleGeneratorsConfig();
        if (initializationContext != null && initializationContext.isShutdownRequested()) return;
        if (initializationContext != null) initializationContext.step("Spawn Pools");
        new SpawnPoolsConfig();
        if (initializationContext != null && initializationContext.isShutdownRequested()) return;
        if (initializationContext != null) initializationContext.step("Content Packages");
        new ContentPackageConfig();
        if (initializationContext != null && initializationContext.isShutdownRequested()) return;
        if (initializationContext != null) initializationContext.step("Schematics");
        new SchematicConfig();
        if (initializationContext != null && initializationContext.isShutdownRequested()) return;
        if (initializationContext != null) initializationContext.step("Modules");
        new ModulesConfig();
    }

    private void syncInitialization(PluginInitializationContext initializationContext) {
        if (initializationContext != null) initializationContext.step("Valid Worlds Config");
        new ValidWorldsConfig();

        if (initializationContext != null) initializationContext.step("NMS Adapter");
        if (!PlatformScheduler.isFolia()) {
            NMSManager.initializeAdapter(this);
        }

        if (initializationContext != null) initializationContext.step("WorldEdit Check");
        // Runs on the first tick, once WorldEdit has finished choosing its platform.
        // WorldEdit は図面の読み込み補助なので、無い環境でも続ける（読み込めない時だけ握る）。
        PlatformScheduler.runGlobal(() -> runIgnoringLinkageError(WorldEditCompatibility::checkAdapterOnStartup));

        if (initializationContext != null) initializationContext.step("Components Folder");
        ComponentsConfigFolder.initialize();

        if (initializationContext != null) initializationContext.step("Event Listeners");
        Bukkit.getPluginManager().registerEvents(new NewChunkLoadEvent(), this);
        Bukkit.getPluginManager().registerEvents(new FirstTimeSetupWarner(), this);
        Bukkit.getPluginManager().registerEvents(new ValidWorldsConfig.ValidWorldsConfigEvents(), this);

        if (initializationContext != null) initializationContext.step("Commands");
        CommandManager commandManager = new CommandManager(this, "betterstructures");
        commandManager.registerCommand(new LootifyCommand());
        commandManager.registerCommand(new PlaceCommand());
        commandManager.registerCommand(new PregenerateCommand());
        commandManager.registerCommand(new CancelPregenerateCommand());
        commandManager.registerCommand(new ReloadCommand());
        commandManager.registerCommand(new SilentCommand());
        commandManager.registerCommand(new TeleportCommand());
        commandManager.registerCommand(new VersionCommand());
        commandManager.registerCommand(new SetupCommand());
        commandManager.registerCommand(new FirstTimeSetupCommand());
        commandManager.registerCommand(new NightbreakRecommendedPluginsCommand(this, NIGHTBREAK_PLUGIN_SPEC));
        commandManager.registerCommand(new NightbreakDownloadPluginUpdateCommand(this, NIGHTBREAK_PLUGIN_SPEC));
        commandManager.registerCommand(new NightbreakApplyPluginUpdateCommand(this, NIGHTBREAK_PLUGIN_SPEC));
        commandManager.registerCommand(new NightbreakDownloadEverythingCommand<>(this,
                NIGHTBREAK_PLUGIN_SPEC,
                BetterStructures::availablePackages,
                ReloadCommand::reload));
        commandManager.registerCommand(new NightbreakDownloadContentCommand<>(this,
                NIGHTBREAK_PLUGIN_SPEC,
                BetterStructures::availablePackages,
                ReloadCommand::reload,
                false));
        commandManager.registerCommand(new NightbreakDownloadContentCommand<>(this,
                NIGHTBREAK_PLUGIN_SPEC,
                BetterStructures::availablePackages,
                ReloadCommand::reload,
                true));
        commandManager.registerCommand(new NightbreakForceReinstallContentCommand<>(this,
                NIGHTBREAK_PLUGIN_SPEC,
                BetterStructures::availablePackages,
                ReloadCommand::reload));
        commandManager.registerCommand(new GenerateModulesCommand());
        commandManager.registerCommand(new BetterStructuresCommand());

        if (initializationContext != null) initializationContext.step("Version Check");
        if (!PlatformScheduler.isFolia()) {
            MagmaCore.checkVersionUpdate("103241", "https://nightbreak.io/plugin/betterstructures/");
        }

        if (initializationContext != null) initializationContext.step("WorldGuard Integration");
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") &&
                Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) {
            Bukkit.getPluginManager().registerEvents(new WorldGuard(), this);
        }

        // 設定（runtimeMetricsLogging）の読み込みが済んだ後に、計測ログの周期タスクを始める。
        restartMetricsLogging();

        if (initializationContext != null) initializationContext.step("Metrics");
        if (!PlatformScheduler.isFolia()) {
            metrics = new Metrics(this, 19523);
        }
    }

    public void reloadImportedContent(CommandSender commandSender) {
        if (!isEnabled() || MagmaCore.isShutdownRequested(this)) return;
        if (!PlatformScheduler.isGlobalTickThread()) {
            PlatformScheduler.runGlobal(() -> reloadImportedContent(commandSender));
            return;
        }
        if (MagmaCore.getInitializationState(getName()) == PluginInitializationState.INITIALIZING
                && !contentReloadInProgress) {
            if (commandSender != null)
                Logger.sendMessage(commandSender, "BetterStructures is still initializing. Try reloading when it finishes.");
            return;
        }
        if (contentReloadInProgress) {
            if (commandSender != null) {
                queuedReloadSenders.add(commandSender);
                Logger.sendMessage(commandSender, "A BetterStructures content reload is already running. Your reload was queued.");
            }
            return;
        }

        activeReloadSenders.clear();
        if (commandSender != null) activeReloadSenders.add(commandSender);
        startImportedContentReload();
    }

    private void startImportedContentReload() {
        contentReloadInProgress = true;
        // 再読み込みの世代を進める。世代を見る仕事（まだ貼り付けに入っていない走査など）は
        // 古い内容で進めない。走っている貼り付けは取り消さず、持っている図面を最後まで使い切る（04章 G5）。
        PlatformScheduler.advanceReloadGeneration();

        // `/betterstructures reload` is presented as the plugin reload command,
        // so runtime settings must not remain pinned to their startup values.
        // Reload these synchronously on the server thread before asynchronous
        // content parsing starts; ValidWorldsConfig also reads the live Bukkit
        // world list and therefore must not be constructed by the worker.
        new DefaultConfig();
        new ValidWorldsConfig();
        // 流量の設定（計測ログの切り替え・間隔）は読み直した値で作り直す。
        restartMetricsLogging();
        SchematicContainer.updateOptionalPluginAvailability(
                Bukkit.getPluginManager().isPluginEnabled("EliteMobs"),
                Bukkit.getPluginManager().isPluginEnabled("MythicMobs"));
        runIgnoringLinkageError(SchematicContainer::shutdown);
        NewChunkLoadEvent.prepareForContentReload();
        ChunkPregenerator.shutdown();
        // PlatformScheduler.cancelAllTasks() は呼ばない: 流量制御のタイマーと、走っている貼り付けの
        // タスクまで取り消してしまうため（取り消しは停止時だけ）。
        BSPackage.shutdown();
        ModulesContainer.shutdown();
        WFCGenerator.shutdown();

        if (PlatformScheduler.isFolia()) {
            PlatformScheduler.runAsync(() -> {
                try {
                    loadContent(null);
                    BSPackageRefresher.reset();
                    ComponentsConfigFolder.initialize();
                    PlatformScheduler.runGlobal(() -> finishImportedContentReload(true));
                } catch (Throwable failure) {
                    Logger.warn("Failed to reload BetterStructures content asynchronously.");
                    failure.printStackTrace();
                    PlatformScheduler.runGlobal(() -> finishImportedContentReload(false));
                }
            });
            return;
        }

        MagmaCore.startInitialization(this,
                new PluginInitializationConfig("BetterStructures", "betterstructures.*", 10, List.of()),
                this::loadContent,
                context -> {
                    context.step("Content Access");
                    BSPackageRefresher.reset();
                    context.step("Components Folder");
                    ComponentsConfigFolder.initialize();
                },
                () -> finishImportedContentReload(true),
                failure -> {
                    Logger.warn("Failed to reload BetterStructures content asynchronously.");
                    failure.printStackTrace();
                    // Disabling inside the initialization callback would wait on itself.
                    PlatformScheduler.runGlobal(() -> finishImportedContentReload(false));
                });
    }

    private void finishImportedContentReload(boolean successful) {
        if (!isEnabled() || MagmaCore.isShutdownRequested(this)) return;
        String worldEditWarning = successful ? null : worldEditChatWarning();
        String resultMessage = successful
                ? "Reloaded BetterStructures content."
                : worldEditWarning != null
                ? worldEditWarning
                : "&cFailed to reload BetterStructures content. Check the console.";
        for (CommandSender sender : activeReloadSenders) {
            Logger.sendMessage(sender, resultMessage);
        }
        activeReloadSenders.clear();
        contentReloadInProgress = false;

        if (!successful) {
            queuedReloadSenders.clear();
            NewChunkLoadEvent.discardDeferredNewChunks();
            // Every content registry is intentionally cleared before the
            // worker rebuilds it. Continuing to run after a failed rebuild
            // would leave a deceptively enabled plugin with partial or empty
            // content, so fail closed and make the defect visible.
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (queuedReloadSenders.isEmpty()) {
            NewChunkLoadEvent.replayDeferredNewChunks();
            return;
        }

        // A command arriving during a reload can accompany a file change made
        // after the first worker started. Run one more complete reload instead
        // of merely attaching its sender to the first result. All overlapping
        // requests are coalesced into that one follow-up, keeping the static
        // registries single-writer while still observing the newest files.
        activeReloadSenders.addAll(queuedReloadSenders);
        queuedReloadSenders.clear();
        startImportedContentReload();
    }

    /** WorldEdit 由来の失敗の案内。WorldEdit が無く読み込めない時は null。 */
    private static String worldEditChatWarning() {
        try {
            return WorldEditCompatibility.chatWarning();
        } catch (LinkageError error) {
            return null;
        }
    }

    public static boolean isReloading() {
        return MetadataHandler.PLUGIN instanceof BetterStructures plugin && plugin.contentReloadInProgress;
    }

    public static List<BSPackage> availablePackages() {
        if (isReloading()) return List.of();
        return new ArrayList<>(BSPackage.getBsPackages().values());
    }

    /**
     * Rejects commands that read or mutate content registries while the
     * asynchronous reload worker owns those registries.
     */
    public static boolean rejectContentCommandDuringReload(
            CommandSender sender) {
        if (!isReloading()) return false;
        if (sender != null) {
            Logger.sendMessage(
                    sender,
                    "&eBetterStructures content is reloading. Try that command again when the reload finishes.");
        }
        return true;
    }
}
