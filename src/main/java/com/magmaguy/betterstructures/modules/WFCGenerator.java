package com.magmaguy.betterstructures.modules;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.runtime.GenerationGovernor;
import com.magmaguy.betterstructures.runtime.WorkerPool;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.WorldFolderResolver;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Predicate;

import static com.magmaguy.betterstructures.modules.ModulesContainer.pickWeightedRandomModule;

public class WFCGenerator {
    private static final Set<WFCGenerator> ACTIVE_GENERATORS = ConcurrentHashMap.newKeySet();
    // A single exceeded backtrack limit is just an unlucky layout that gets skipped; only warn once a
    // generator keeps failing without ever succeeding, since that means it can likely never generate.
    private static final int STUCK_GENERATOR_WARNING_THRESHOLD = 100;
    private static final ConcurrentHashMap<String, AtomicInteger> backtrackFailureStreaks = new ConcurrentHashMap<>();
    @Getter
    private ModuleGeneratorsConfigFields moduleGeneratorsConfigFields;

    @Getter
    private WFCLattice spatialGrid;
    private Player player;
    private String startingModule;
    @Getter
    private World world;
    @Getter
    private Location startLocation;
    private volatile boolean isGenerating;
    private volatile boolean isCancelled;
    private File worldFolder;
    private String worldName;
    private int rollbackCounter = 0;
    private BossBar progressBar;
    private int totalNodes = 0;
    private volatile int completedNodes = 0;
    private volatile String pendingProgressMessage;
    private final AtomicBoolean progressUpdateScheduled = new AtomicBoolean();
    private final AtomicBoolean cleanedUp = new AtomicBoolean();
    private BooleanSupplier naturalPasteGuard;
    /** Governor の WFC 枠の返却券。終わり（{@link #cleanup()}）で必ず返す。公開コンストラクタからの生成では null。 */
    private GenerationGovernor.Ticket ticket;
    private static final AtomicBoolean foliaWorldGenerationWarned = new AtomicBoolean();

    public WFCGenerator(ModuleGeneratorsConfigFields moduleGeneratorsConfigFields, Player player) {
        this.player = Objects.requireNonNull(player, "player");
        this.startLocation = player.getLocation();
        initialize(moduleGeneratorsConfigFields);
    }

    /** Governor から枠をもらって始める時。位置は呼び出し側が自分のスレッドで取って渡す（Folia でプレイヤーを別スレッドから読まない）。 */
    private WFCGenerator(ModuleGeneratorsConfigFields moduleGeneratorsConfigFields, Player player,
                         Location startLocation, GenerationGovernor.Ticket ticket) {
        this.player = Objects.requireNonNull(player, "player");
        this.startLocation = Objects.requireNonNull(startLocation, "startLocation");
        this.ticket = ticket;
        initialize(moduleGeneratorsConfigFields);
    }

    public WFCGenerator(ModuleGeneratorsConfigFields moduleGeneratorsConfigFields, Location startLocation) {
        this.startLocation = Objects.requireNonNull(startLocation, "startLocation");
        initialize(moduleGeneratorsConfigFields);
    }

    /** 枠をもらって始める時（試験からも使う）。ticket は null 可。 */
    WFCGenerator(
            ModuleGeneratorsConfigFields moduleGeneratorsConfigFields,
            Location startLocation,
            BooleanSupplier naturalPasteGuard,
            GenerationGovernor.Ticket ticket) {
        this.startLocation = Objects.requireNonNull(startLocation, "startLocation");
        this.naturalPasteGuard = naturalPasteGuard;
        this.ticket = ticket;
        initialize(moduleGeneratorsConfigFields);
    }

    /**
     * Folia では、新しいワールドを作って生成する設定（{@code isWorldGeneration: true}）は今は使えない
     * （社長決定・2026-11-18。方法は今後考える）。使えない時は実行者へ出す案内、使える時は null。
     */
    public static String unsupportedWorldGenerationMessage(ModuleGeneratorsConfigFields config, boolean folia) {
        if (!folia || config == null || !config.isWorldGeneration()) return null;
        return "Folia では、新しいワールドを作って生成する設定（isWorldGeneration: true）のダンジョン生成は今は使えません。"
                + "この設定（" + config.getFilename() + "）では生成しませんでした。"
                + "isWorldGeneration: false の設定は、実行した人の足元にそのまま生成されます。";
    }

    public static void generateFromConfig(ModuleGeneratorsConfigFields generatorsConfigFields, Player player) {
        generateFromConfig(generatorsConfigFields, player, PlatformScheduler.isFolia(), GenerationGovernor.instance());
    }

    static void generateFromConfig(ModuleGeneratorsConfigFields generatorsConfigFields, Player player,
                                   boolean folia, GenerationGovernor governor) {
        Objects.requireNonNull(generatorsConfigFields, "generatorsConfigFields");
        Objects.requireNonNull(player, "player");
        String refusal = unsupportedWorldGenerationMessage(generatorsConfigFields, folia);
        if (refusal != null) {
            player.sendMessage(refusal);
            Logger.warn(refusal);
            return;
        }
        // 位置はプレイヤーを所有するスレッド（コマンドの実行スレッド）で取る
        Location playerLocation = player.getLocation();
        UUID worldId = playerLocation.getWorld().getUID();
        enqueue(governor, worldId, ticket -> startGenerator(ticket, () ->
                new WFCGenerator(generatorsConfigFields, player, playerLocation, ticket)));
    }

    public static void generateNaturally(
            ModuleGeneratorsConfigFields generatorsConfigFields,
            Location startLocation,
            BooleanSupplier pasteGuard) {
        generateNaturally(generatorsConfigFields, startLocation, pasteGuard,
                PlatformScheduler.isFolia(), GenerationGovernor.instance());
    }

    static void generateNaturally(
            ModuleGeneratorsConfigFields generatorsConfigFields,
            Location startLocation,
            BooleanSupplier pasteGuard,
            boolean folia,
            GenerationGovernor governor) {
        Objects.requireNonNull(generatorsConfigFields, "generatorsConfigFields");
        Objects.requireNonNull(startLocation, "startLocation");
        Objects.requireNonNull(pasteGuard, "pasteGuard");
        if (unsupportedWorldGenerationMessage(generatorsConfigFields, folia) != null) {
            if (foliaWorldGenerationWarned.compareAndSet(false, true))
                Logger.warn("Folia cannot generate dungeons into a new world yet (isWorldGeneration: true in "
                        + generatorsConfigFields.getFilename() + "); skipping those generations.");
            return;
        }
        UUID worldId = startLocation.getWorld().getUID();
        enqueue(governor, worldId, ticket -> startGenerator(ticket, () ->
                new WFCGenerator(generatorsConfigFields, startLocation, pasteGuard, ticket)));
    }

    /**
     * 生成の依頼を Governor の WFC 枠へ入れる。捨てない（枠が空くまで順に待つ）。
     * starter は枠をもらった時にグローバルリージョンのスレッドで呼ばれ、終わりに必ず ticket を返すこと。
     */
    static boolean enqueue(GenerationGovernor governor, UUID worldId, Consumer<GenerationGovernor.Ticket> starter) {
        return governor.submit(new GenerationGovernor.Work(
                GenerationGovernor.Lane.WFC, worldId, null, null, starter));
    }

    private static void startGenerator(GenerationGovernor.Ticket ticket, Supplier<WFCGenerator> factory) {
        try {
            factory.get();
        } catch (Throwable throwable) {
            Logger.warn("Failed to start modular generation: " + throwable.getMessage());
            ticket.release();
        }
    }

    public static void shutdown() {
        List.copyOf(ACTIVE_GENERATORS).forEach(WFCGenerator::cancel);
        ACTIVE_GENERATORS.clear();
    }

    static String selectAvailableWorldName(String filename, Predicate<String> folderExists) {
        String baseWorldName = Objects.requireNonNull(filename, "filename")
                .replaceFirst("(?i)\\.yml$", "");
        Objects.requireNonNull(folderExists, "folderExists");
        for (int suffix = 0; suffix < Integer.MAX_VALUE; suffix++) {
            String candidate = baseWorldName + "_" + suffix;
            if (!folderExists.test(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not find an available world name for " + filename);
    }

    static void forEachPasteCoordinate(int radius, int minY, int maxY, Consumer<Vector3i> consumer) {
        // Inclusive bounds on purpose: they sweep the full populated lattice ([-radius, +radius],
        // see WFCLattice.initializeLattice). The pre-extraction loop excluded only the +x/+z
        // planes; either way those planes hold boundary nodes carrying the 'nothing' container
        // (null clipboard) that ModulePasting filters out, so this is consistency, not a behavior
        // change.
        for (int x = -radius; x <= radius; x++)
            for (int z = -radius; z <= radius; z++)
                for (int y = minY; y <= maxY; y++)
                    consumer.accept(new Vector3i(x, y, z));
    }

    private void initializeProgressBar() {
        if (player != null) {
            progressBar = Bukkit.createBossBar("Generating Structure...", BarColor.BLUE, BarStyle.SOLID);
            progressBar.addPlayer(player);
            progressBar.setProgress(0.0);
        }
    }

    private void updateProgressBar(String message) {
        pendingProgressMessage = message;
        if (progressBar == null || !progressUpdateScheduled.compareAndSet(false, true)) return;
        runOnPrimaryThread(() -> {
            String appliedMessage = pendingProgressMessage;
            if (progressBar != null && totalNodes > 0) {
                double progress = (double) completedNodes / totalNodes;
                progressBar.setProgress(Math.max(0.0, Math.min(progress, 1.0)));
                progressBar.setTitle(appliedMessage);
            }
            progressUpdateScheduled.set(false);
            if (!Objects.equals(appliedMessage, pendingProgressMessage))
                updateProgressBar(pendingProgressMessage);
        });
    }

    private void removeProgressBar() {
        if (progressBar != null) {
            progressBar.removeAll();
            progressBar = null;
        }
    }

    private void initialize(ModuleGeneratorsConfigFields moduleGeneratorsConfigFields) {
        this.moduleGeneratorsConfigFields = Objects.requireNonNull(moduleGeneratorsConfigFields, "moduleGeneratorsConfigFields");
        List<String> startModules = moduleGeneratorsConfigFields.getStartModules();
        if (startModules.isEmpty()) {
            if (player != null) player.sendMessage("No start modules exist, you need to install or make modules first!");
            Logger.warn("No start modules exist, you need to install or make modules first!");
            cleanup();
            return;
        }
        startingModule = startModules.get(ThreadLocalRandom.current().nextInt(startModules.size())) + "_rotation_0";
        spatialGrid = new WFCLattice(moduleGeneratorsConfigFields.getRadius(),
                moduleGeneratorsConfigFields.getModuleSizeXZ(), moduleGeneratorsConfigFields.getModuleSizeY(),
                moduleGeneratorsConfigFields.getMinChunkY(), moduleGeneratorsConfigFields.getMaxChunkY());
        int radius = moduleGeneratorsConfigFields.getRadius();
        int minY = moduleGeneratorsConfigFields.getMinChunkY();
        int maxY = moduleGeneratorsConfigFields.getMaxChunkY();
        // Only interior nodes ever collapse: the +/-radius x/z planes (and the y planes outside
        // [minY, maxY]) are boundary nodes pre-collapsed to 'nothing' that never enter the entropy
        // queue, so counting them would keep the progress bar from ever reaching 100%.
        totalNodes = (radius * 2 - 1) * (radius * 2 - 1) * (maxY - minY + 1);
        ACTIVE_GENERATORS.add(this);

        try {
            initializeProgressBar();
            initializeWorldAndLattice();
        } catch (RuntimeException exception) {
            Logger.warn("Failed to initialize modular generation: " + exception.getMessage());
            exception.printStackTrace();
            cleanup();
        }
    }

    private void initializeWorldAndLattice() {
        updateProgressBar("Initializing lattice...");
        if (moduleGeneratorsConfigFields.isWorldGeneration()) {
            worldName = selectAvailableWorldName(moduleGeneratorsConfigFields.getFilename(), WorldFolderResolver::folderExists);
            world = WorldInitializer.generateWorld(worldName, player);
            startLocation = new Location(world, 0, 0, 0);
        } else {
            world = Objects.requireNonNull(startLocation.getWorld(), "startLocation world");
            worldName = world.getName();
        }
        worldFolder = WorldFolderResolver.resolve(worldName);
        List<String> missing = modulesWithoutSchematic(ModulesContainer.getModulesContainers().values());
        if (!missing.isEmpty()) {
            String message = "Cancelled modular generation because these modules have no loaded schematic: " + missing;
            Logger.warn(message);
            if (player != null) player.sendMessage(message);
            isCancelled = true;
            cleanup();
            return;
        }
        startArrangingModules();
    }

    /** 使うモジュールのうち、図面（StructureSchematic）が読み込まれていないものの名前。"nothing" は図面を持たない。 */
    static List<String> modulesWithoutSchematic(Collection<ModulesContainer> containers) {
        List<String> missing = new ArrayList<>();
        for (ModulesContainer container : containers)
            if (container != null && !container.isNothing() && container.getStructureSchematic() == null)
                missing.add(container.getClipboardFilename());
        return missing;
    }

    /** 格子の初期化と配置の計算は WorkerPool で行い、リージョンスレッドを止めない。 */
    private void startArrangingModules() {
        updateProgressBar("Starting generation...");
        try {
            WorkerPool.instance().execute(this::computeLayout);
        } catch (IllegalStateException exception) {
            Logger.warn("Failed to start modular generation: " + exception.getMessage());
            isCancelled = true;
            runOnGenerationThread(this::cleanup);
        }
    }

    private void computeLayout() {
        if (isCancelled) return;
        try {
            // 格子の初期化はワールドを読まない（ノードの生成と隣の登録だけ。デバッグ表示はスケジューラ経由）
            spatialGrid.initializeLattice(world, this);
        } catch (RuntimeException exception) {
            Logger.warn("Failed to initialize the modular lattice: " + exception.getMessage());
            exception.printStackTrace();
            isCancelled = true;
            runOnGenerationThread(this::cleanup);
            return;
        }
        start(startingModule);
    }

    private void start(String startingModule) {
        if (isCancelled || isGenerating) return;
        isGenerating = true;
        boolean completed = false;
        try {
            updateProgressBar("Collapsing initial node...");
            WFCNode startChunk = createStartChunk(startingModule);
            if (startChunk == null) {
                isCancelled = true;
                return;
            }
            completed = generateFast();
        } catch (RuntimeException exception) {
            Logger.warn("Error during generation: " + exception.getMessage());
            exception.printStackTrace();
            isCancelled = true;
        } finally {
            isGenerating = false;
            boolean generationCompleted = completed && !isCancelled;
            runOnGenerationThread(generationCompleted ? this::finishGeneration : this::cleanup);
        }
    }

    private WFCNode createStartChunk(String startingModule) {
        WFCNode startCell = spatialGrid.getNodeMap().get(new Vector3i());
        if (startCell == null) {
            Logger.warn("The configured lattice does not contain the origin node! Cancelling!");
            return null;
        }

        ModulesContainer modulesContainer = ModulesContainer.getModulesContainers().get(startingModule);
        if (modulesContainer == null) {
            Logger.warn("Starting module was null! Cancelling!");
            return null;
        }

        paste(startCell, modulesContainer);
        completedNodes++;
        return startCell;
    }

    private boolean generateFast() {
        updateProgressBar("Propagating constraints...");
        while (!isCancelled) {
            WFCNode nextCell = spatialGrid.getLowestEntropyNode();
            if (nextCell == null) return true;
            generateNextChunk(nextCell);
        }
        return false;
    }

    private void paste(WFCNode gridCell, ModulesContainer modulesContainer) {
        // Record the decision for backtracking
        spatialGrid.recordCollapseDecision(gridCell, modulesContainer);

        gridCell.setModulesContainer(modulesContainer);
        gridCell.getOrientedNeighbors().values().forEach(spatialGrid::updateNodeEntropy);
    }

    private void generateNextChunk(WFCNode gridCell) {
        HashSet<ModulesContainer> validOptions = gridCell.getValidOptions();
        if (validOptions == null || validOptions.isEmpty()) {
            updateProgressBar("Backtracking...");
            rollbackChunk();
            return;
        }

        ModulesContainer modulesContainer = pickWeightedRandomModule(validOptions, gridCell);
        if (modulesContainer == null) {
            updateProgressBar("Backtracking...");
            rollbackChunk();
            return;
        }

        paste(gridCell, modulesContainer);
        completedNodes++;
        updateProgressBar("Generating... (" + completedNodes + "/" + totalNodes + ")");
    }

    private void rollbackChunk() {
        // Use proper backtracking instead of just resetting
        if (spatialGrid.backtrack()) {
            completedNodes = Math.max(0, completedNodes - 1);
            updateProgressBar("Backtracking... (" + spatialGrid.getBacktrackDepth() + " decisions remaining)");
        } else {
            updateProgressBar("Generation failed - no decisions to backtrack");
            isCancelled = true;
            return;
        }

        rollbackCounter++;
        if (rollbackCounter > 1000) {
            updateProgressBar("Generation failed - exceeded backtrack limit");
            registerBacktrackLimitFailure();
            isCancelled = true;
        }
    }

    private void registerBacktrackLimitFailure() {
        String generatorName = moduleGeneratorsConfigFields.getFilename();
        int streak = backtrackFailureStreaks.computeIfAbsent(generatorName, key -> new AtomicInteger()).incrementAndGet();
        if (streak % STUCK_GENERATOR_WARNING_THRESHOLD == 0)
            Logger.warn("Exceeded backtrack limit! Generator " + generatorName + " has failed " + streak +
                    " times in a row without a successful generation, so it is likely unable to generate at all." +
                    " Check that its modules can fit together and that the lattice settings are sane.");
    }

    void finishGeneration() {
        // 例外が出ても、ボスバー・ACTIVE_GENERATORS・格子・枠の返却券を必ず片付ける
        try {
            if (isCancelled) return;
            if (naturalPasteGuard != null) {
                boolean safeToPaste;
                try {
                    safeToPaste = naturalPasteGuard.getAsBoolean();
                } catch (Throwable throwable) {
                    Logger.warn("Cancelled natural modular generation because its final safety check failed: "
                            + throwable.getMessage());
                    return;
                }
                if (!safeToPaste) {
                    Logger.info("Cancelled natural modular generation because its footprint gained "
                            + "a WorldGuard region before paste.");
                    return;
                }
            }
            backtrackFailureStreaks.remove(moduleGeneratorsConfigFields.getFilename());
            updateProgressBar("Generation complete!");
            if (player != null) {
                player.sendMessage("Done assembling!");
                player.sendMessage("It will take a moment to paste the structure, and will require relogging.");
            }
            instantPaste();
        } catch (Throwable throwable) {
            Logger.warn("Modular generation failed while finishing: " + throwable.getMessage());
            throwable.printStackTrace();
        } finally {
            cleanup();
        }
    }

    boolean isCleanedUp() {
        return cleanedUp.get();
    }

    private void cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return;
        try {
            if (spatialGrid != null) spatialGrid.clearAllData();
            ACTIVE_GENERATORS.remove(this);
            removeProgressBar();
        } finally {
            // 枠は必ず返す（返さないと次のダンジョンが永久に待つ）
            if (ticket != null) ticket.release();
        }
    }

    /**
     * Cancels the generation process.
     */
    public void cancel() {
        isCancelled = true;
        runOnPrimaryThread(() -> {
            ACTIVE_GENERATORS.remove(this);
            removeProgressBar();
            if (!isGenerating) cleanup();
        });
    }

    void instantPaste() {
        // This guarantees that the paste order is grouped by chunk, making pasting faster down the line.
        Deque<WFCNode> orderedPasteDeque = new ArrayDeque<>();
        forEachPasteCoordinate(spatialGrid.getLatticeRadius(), spatialGrid.getMinYLevel(), spatialGrid.getMaxYLevel(), coordinate -> {
            WFCNode cell = spatialGrid.getNodeMap().remove(coordinate);
            if (cell != null) orderedPasteDeque.add(cell);
        });

        new ModulePasting(world, worldFolder, orderedPasteDeque, moduleGeneratorsConfigFields.getSpawnPoolSuffix(), startLocation, moduleGeneratorsConfigFields);
    }

    private static void runOnPrimaryThread(Runnable runnable) {
        if (PlatformScheduler.isGlobalTickThread()) runnable.run();
        else if (MetadataHandler.PLUGIN != null && MetadataHandler.PLUGIN.isEnabled())
            PlatformScheduler.runGlobal(runnable);
    }

    private void runOnGenerationThread(Runnable runnable) {
        if (PlatformScheduler.isFolia()) {
            if (startLocation != null && startLocation.getWorld() != null) {
                PlatformScheduler.runRegion(startLocation, runnable);
            } else {
                PlatformScheduler.runGlobal(runnable);
            }
        } else {
            if (Bukkit.isPrimaryThread()) runnable.run();
            else if (MetadataHandler.PLUGIN != null && MetadataHandler.PLUGIN.isEnabled())
                PlatformScheduler.runGlobal(runnable);
        }
    }
}
