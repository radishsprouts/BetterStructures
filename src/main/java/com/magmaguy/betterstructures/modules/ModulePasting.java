package com.magmaguy.betterstructures.modules;

import com.magmaguy.betterstructures.chests.ChestContents;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.config.modules.ModulesConfigFields;
import com.magmaguy.betterstructures.config.spawnpools.SpawnPoolsConfig;
import com.magmaguy.betterstructures.config.spawnpools.SpawnPoolsConfigFields;
import com.magmaguy.betterstructures.integration.EliteMobsHook;
import com.magmaguy.betterstructures.integration.elitemobs.EliteMobsHookSelector;
import com.magmaguy.betterstructures.paste.PasteEngine;
import com.magmaguy.betterstructures.paste.PasteRequest;
import com.magmaguy.betterstructures.runtime.RuntimeSettings;
import com.magmaguy.betterstructures.runtime.Services;
import com.magmaguy.betterstructures.runtime.WorkerPool;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 組み上がったダンジョン（WFC の結果）を、{@link PasteEngine} へモジュール単位で依頼する。
 *
 * <p>このクラスは WorldEdit の型を持たない。貼り付けは常に独自の PasteEngine が行う。
 * 図面の整形（回転済みの図面から、貼らないブロックを除く）は {@link WorkerPool} で行い、
 * 呼び出したスレッド（リージョンのスレッド）には重い計算を載せない。
 *
 * <p>目印（{@code [spawn]}・{@code [chest]}・{@code [exit]} 等）は {@link ModularMarkerHandler} が
 * 看板を所有するリージョンのスレッドで処理する。全モジュールの貼り付けが終わったら、生成ワールドを
 * 作る設定ではそのリージョンのスレッドで {@link ModularWorld#generationFinished()} を呼ぶ。
 */
public final class ModulePasting {

    private final String spawnPoolSuffix;
    private final Location startLocation;
    private final boolean createModularWorld;
    private final World world;
    private final File worldFolder;
    private final ModuleGeneratorsConfigFields moduleGeneratorsConfigFields;
    private final EliteMobsHook eliteMobs;
    private final ModularMarkerHandler markerHandler;
    private ModularWorld modularWorld;

    public ModulePasting(World world, File worldFolder, Deque<WFCNode> WFCNodeDeque, String spawnPoolSuffix,
                         Location startLocation, ModuleGeneratorsConfigFields moduleGeneratorsConfigFields) {
        this(world, worldFolder, WFCNodeDeque, spawnPoolSuffix, startLocation, moduleGeneratorsConfigFields, null);
    }

    /** @param eliteMobs EliteMobs の窓口。null なら {@link EliteMobsHookSelector#select()} を使う */
    ModulePasting(World world, File worldFolder, Deque<WFCNode> WFCNodeDeque, String spawnPoolSuffix,
                  Location startLocation, ModuleGeneratorsConfigFields moduleGeneratorsConfigFields,
                  EliteMobsHook eliteMobs) {
        //Generators without spawnPoolSuffix must look up "[pool:name]" as name.yml, not namenull.yml
        this.spawnPoolSuffix = spawnPoolSuffix == null ? "" : spawnPoolSuffix;
        this.startLocation = startLocation;
        this.world = world;
        this.worldFolder = worldFolder;
        this.moduleGeneratorsConfigFields = moduleGeneratorsConfigFields;
        this.eliteMobs = eliteMobs != null ? eliteMobs : EliteMobsHookSelector.select();
        this.createModularWorld = moduleGeneratorsConfigFields.isWorldGeneration();
        this.markerHandler = new ModularMarkerHandler(moduleGeneratorsConfigFields, this.spawnPoolSuffix,
                startLocation, () -> createModularWorld ? modularWorld() : null, this.eliteMobs);

        List<NodeSnapshot> snapshots = snapshot(WFCNodeDeque);
        notifyPlayers();
        if (snapshots.isEmpty()) {
            finishPaste();
            return;
        }
        try {
            WorkerPool.instance().execute(() -> startPaste(snapshots));
        } catch (RuntimeException e) {
            Logger.warn("Failed to start the modular paste: " + e.getMessage());
        }
    }

    /** 図面1つ分の貼り付けの材料。貼り付けの最中に WFCNode を触らないよう、先に値だけ写す。 */
    private record NodeSnapshot(StructureSchematic schematic, String filename, Location location, int rotation,
                                ModulesConfigFields config) {
    }

    private List<NodeSnapshot> snapshot(Deque<WFCNode> nodes) {
        List<NodeSnapshot> snapshots = new ArrayList<>(nodes.size());
        while (!nodes.isEmpty()) {
            WFCNode node = nodes.poll();
            if (node == null) continue;
            ModulesContainer module = node.getModulesContainer();
            if (module == null || module.getStructureSchematic() == null) continue;
            snapshots.add(new NodeSnapshot(module.getStructureSchematic(), module.getClipboardFilename(),
                    node.getRealLocation(startLocation), module.getRotation(), module.getModulesConfigField()));
        }
        return snapshots;
    }

    private void notifyPlayers() {
        if (!DefaultConfig.isNewBuildingWarn()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("betterstructures.warn")) {
                player.spigot().sendMessage(
                        SpigotMessage.commandHoverMessage(
                                "[BetterStructures] New dungeon started generating! Do not stop your server now. Click to teleport. Do \"/betterstructures silent\" to stop getting warnings!",
                                "Click to teleport to " + startLocation.getWorld().getName() + ", " +
                                        startLocation.getBlockX() + ", " + startLocation.getBlockY() + ", " + startLocation.getBlockZ(),
                                "/betterstructures teleport " + startLocation.getWorld().getName() + " " +
                                        startLocation.getBlockX() + " " + startLocation.getBlockY() + " " + startLocation.getBlockZ())
                );
            }
        }
    }

    // ---- 貼り付けの依頼（WorkerPool で動く） ----

    private void startPaste(List<NodeSnapshot> snapshots) {
        PasteEngine engine;
        try {
            engine = Services.pasteEngine();
        } catch (IllegalStateException e) {
            Logger.warn("Failed to paste the modular structure at " + startLocation.getBlockX() + ", "
                    + startLocation.getBlockY() + ", " + startLocation.getBlockZ() + ": " + e.getMessage());
            return;
        }
        AtomicInteger remaining = new AtomicInteger(snapshots.size());
        int windowChunks = RuntimeSettings.get().wfcWindowChunks();
        for (NodeSnapshot snapshot : snapshots) {
            try {
                PasteRequest request = buildRequest(snapshot, windowChunks);
                engine.start(request).completion().whenComplete((result, failure) -> {
                    if (failure != null)
                        Logger.warn("A modular paste failed: " + failure.getMessage());
                    nodeFinished(remaining);
                });
            } catch (RuntimeException e) {
                Logger.warn("Failed to start a modular paste for " + snapshot.filename() + ": " + e.getMessage());
                nodeFinished(remaining);
            }
        }
    }

    private PasteRequest buildRequest(NodeSnapshot snapshot, int windowChunks) {
        StructureSchematic rotated = snapshot.schematic().rotate(snapshot.rotation());
        StructureSchematic prepared = prepare(rotated, createModularWorld);
        ModulesConfigFields config = snapshot.config();
        return PasteRequest.builder(world, snapshot.location(), prepared, markerHandler)
                .schematicOffset(new Vector(0, 0, 0))
                .schematicName(snapshot.filename())
                .rotationDegrees(snapshot.rotation())
                .contents(markerHandler.contents(moduleGeneratorsConfigFields.getTreasureFile()), barrelContents(config))
                .bedrockReplacement(Material.STONE)
                .windowChunks(windowChunks)
                .announce(false)
                .build();
    }

    private ChestContents barrelContents(ModulesConfigFields config) {
        if (!moduleGeneratorsConfigFields.isGenerateLootInBarrels()
                || config != null && !config.isGenerateLootInBarrels()) return null;
        String treasure = config != null && config.getBarrelTreasureFilename() != null
                && !config.getBarrelTreasureFilename().isEmpty()
                ? config.getBarrelTreasureFilename() : moduleGeneratorsConfigFields.getBarrelTreasureFilename();
        return markerHandler.contents(treasure);
    }

    private void nodeFinished(AtomicInteger remaining) {
        if (remaining.decrementAndGet() == 0) finishPaste();
    }

    /** 全モジュールの貼り付けが終わった。生成ワールドを作る設定では完了を知らせる。 */
    private void finishPaste() {
        if (!createModularWorld) return;
        PlatformScheduler.runRegion(startLocation, () -> {
            // 貼り付けの途中でワールドが消えた時は、完了を知らせない
            if (world == null || Bukkit.getWorld(world.getUID()) != world) return;
            modularWorld().generationFinished();
        });
    }

    private synchronized ModularWorld modularWorld() {
        if (modularWorld == null) modularWorld = createWorld();
        return modularWorld;
    }

    private ModularWorld createWorld() {
        int moduleSize = moduleGeneratorsConfigFields.getModuleSizeXZ();
        Location center = new Location(world, startLocation.getBlockX() + (moduleSize % 2) * .5,
                startLocation.getBlockY(), startLocation.getBlockZ() + (moduleSize % 2) * .5);
        double size = (2D * moduleGeneratorsConfigFields.getRadius() - 1) * moduleSize;
        return new ModularWorld(world, worldFolder, List.of(), center, size, eliteMobs);
    }

    // ---- 図面の整形 ----

    /** 貼らない印のブロック（障壁）は常に除く。空気は、虚無のワールドでは除く（既存のワールドでは空洞を掘るので残す）。 */
    private static final Map<StructureSchematic, Optional<StructureSchematic>> CARVE_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<StructureSchematic, Optional<StructureSchematic>> VOID_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 回転済みの図面から、貼らないブロックを除いた図面を返す。除くものが無ければ同じ図面を返す。
     * 結果は図面ごとに覚える（同じモジュールが何度も出るため）。ワールドには触れない。
     */
    static StructureSchematic prepare(StructureSchematic rotated, boolean voidWorld) {
        Map<StructureSchematic, Optional<StructureSchematic>> cache = voidWorld ? VOID_CACHE : CARVE_CACHE;
        Optional<StructureSchematic> cached = cache.get(rotated);
        if (cached == null) {
            cached = Optional.ofNullable(filter(rotated, voidWorld));
            cache.put(rotated, cached);
        }
        return cached.orElse(rotated);
    }

    /** 除くものが無ければ null。 */
    private static StructureSchematic filter(StructureSchematic source, boolean voidWorld) {
        int volume = source.getWidth() * source.getHeight() * source.getLength();
        boolean[] skip = new boolean[volume];
        boolean any = false;
        for (int y = 0; y < source.getHeight(); y++)
            for (int z = 0; z < source.getLength(); z++)
                for (int x = 0; x < source.getWidth(); x++) {
                    BlockData data = source.getBlockData(x, y, z);
                    if (data == null) continue;
                    Material material = data.getMaterial();
                    if (material == Material.BARRIER || voidWorld && material.isAir()) {
                        skip[source.index(x, y, z)] = true;
                        any = true;
                    }
                }
        if (!any) return null;

        BlockData[] blocks = new BlockData[volume];
        Map<Integer, SimpleNbt.CompoundTag> blockEntities = new HashMap<>();
        Map<Integer, SignTextParser.ParsedSign> signs = new HashMap<>();
        for (int y = 0; y < source.getHeight(); y++)
            for (int z = 0; z < source.getLength(); z++)
                for (int x = 0; x < source.getWidth(); x++) {
                    int index = source.index(x, y, z);
                    if (skip[index]) continue;
                    blocks[index] = source.getBlockData(x, y, z);
                    SimpleNbt.CompoundTag tag = source.getBlockEntity(x, y, z);
                    if (tag != null) blockEntities.put(index, tag);
                    SignTextParser.ParsedSign sign = source.getParsedSign(x, y, z);
                    if (sign != null) signs.put(index, sign);
                }
        return new StructureSchematic(source.getWidth(), source.getHeight(), source.getLength(),
                source.getOffsetX(), source.getOffsetY(), source.getOffsetZ(),
                blocks, blockEntities, signs, new ArrayList<>(source.getEntities()));
    }

    // ---- デバッグ表示（WFCNode.debugPaste）用 ----

    /** 回転して1枚だけ貼る。PasteEngine が入っていなければ何もしない。 */
    public static void paste(StructureSchematic schematic, Location location, Integer rotation) {
        if (schematic == null || location == null) return;
        PasteEngine engine = Services.pasteEngineOrNull();
        if (engine == null) return;
        int degrees = rotation == null ? 0 : rotation;
        StructureSchematic rotated = schematic.rotate(degrees);
        engine.start(PasteRequest.builder(location.getWorld(), location, rotated, NoMarkerHandler.INSTANCE)
                .schematicOffset(new Vector(0, 0, 0))
                .rotationDegrees(((degrees % 360) + 360) % 360)
                .build());
    }

    // ---- ボスの看板 ----

    /**
     * The boss a module sign requests: "[elitemobs]" followed by the boss filename, which may be split
     * across the remaining lines, or "[pool:name]" resolved through spawn_pools/name.yml.
     * Null for signs that do not request a boss, or whose pool cannot supply one.
     */
    static String bossFilename(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.toLowerCase(Locale.ROOT).contains("[elitemobs]")) {
                StringBuilder filename = new StringBuilder();
                for (String part : lines.subList(i + 1, lines.size())) filename.append(part.trim());
                if (filename.isEmpty()) {
                    Logger.warn("Module sign " + lines + " has no EliteMobs boss filename");
                    return null;
                }
                return filename.toString();
            }
            String poolName = SpawnPoolsConfig.extractPoolName(line);
            if (poolName == null) continue;
            String poolFilename = poolName + ".yml";
            SpawnPoolsConfigFields pool = SpawnPoolsConfig.getConfigFields(poolFilename);
            if (pool == null) {
                Logger.warn("Could not find spawn pool " + poolFilename);
                return null;
            }
            String bossFilename = SpawnPoolsConfig.pickBossFilename(pool);
            if (bossFilename == null) Logger.warn("Spawn pool " + poolFilename + " has no entries");
            return bossFilename;
        }
        return null;
    }

    public record InterpretedSign(Location location, List<String> text) {
    }

    static Location entitySpawnLocation(Location marker) {
        // Marker coordinates address a block; entity centers must clear the neighboring blocks.
        return marker.clone().add(.5, 0, .5);
    }
}
