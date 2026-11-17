package com.magmaguy.betterstructures.modules;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.api.WorldGenerationFinishEvent;
import com.magmaguy.betterstructures.config.spawnpools.SpawnPoolsConfig;
import com.magmaguy.betterstructures.config.spawnpools.SpawnPoolsConfigFields;
import com.magmaguy.betterstructures.integration.EliteMobsHook;
import com.magmaguy.betterstructures.integration.elitemobs.EliteMobsHookSelector;
import com.magmaguy.betterstructures.paste.PasteRequest;
import com.magmaguy.betterstructures.runtime.Services;
import com.magmaguy.betterstructures.runtime.WorkerPool;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematic.reader.SchematicReader;
import com.magmaguy.elitemobs.mobconstructor.custombosses.InstancedBossEntity;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 生成されたダンジョン1つ分の目印（出現位置・宝箱・樽・出口・ボス）の置き場。
 *
 * <p>目印は貼り付けの途中に、複数のリージョンのスレッドから登録されるため、保持する集合は
 * スレッドセーフにしてある。EliteMobs の型はここでは扱わず、{@link EliteMobsHook} の裏に置く
 * （EliteMobs が無くてもこのクラスは読み込める）。
 */
public class ModularWorld {

    @Getter
    private final List<Location> spawnLocations = new CopyOnWriteArrayList<>();
    @Getter
    private final List<ExitLocation> exitLocations = new CopyOnWriteArrayList<>();
    @Getter
    private final List<Location> chestLocations = new CopyOnWriteArrayList<>();
    @Getter
    private final List<Location> trappedChestLocations = new CopyOnWriteArrayList<>();
    @Getter
    private final List<Location> barrelLocations = new CopyOnWriteArrayList<>();
    private final Set<ModulePasting.InterpretedSign> otherLocations = ConcurrentHashMap.newKeySet();
    private final List<ScheduledInstancedEntity> scheduledInstancedEntities = new CopyOnWriteArrayList<>();
    @Getter
    private final File worldFolder;
    @Getter
    private final World world;
    private final Location center;
    @Getter
    private final double horizontalSize;
    private final EliteMobsHook eliteMobs;
    private volatile boolean missingEliteMobsWarned;

    public ModularWorld(World world, File worldFolder, List<ModulePasting.InterpretedSign> interpretedSigns,
                        Location center, double horizontalSize) {
        this(world, worldFolder, interpretedSigns, center, horizontalSize, null);
    }

    /** @param eliteMobs EliteMobs の窓口。null なら {@link EliteMobsHookSelector#select()} を使う */
    ModularWorld(World world, File worldFolder, List<ModulePasting.InterpretedSign> interpretedSigns,
                 Location center, double horizontalSize, EliteMobsHook eliteMobs) {
        this.world = Objects.requireNonNull(world, "world");
        Objects.requireNonNull(center, "center");
        if (!world.equals(center.getWorld()) || !Double.isFinite(center.getX()) || !Double.isFinite(center.getY())
                || !Double.isFinite(center.getZ())) throw new IllegalArgumentException("The center must be a finite location in the modular world");
        if (!Double.isFinite(horizontalSize) || horizontalSize <= 0)
            throw new IllegalArgumentException("The modular world's horizontal size must be positive and finite");
        this.center = center.clone();
        this.horizontalSize = horizontalSize;
        this.worldFolder = worldFolder;
        this.eliteMobs = eliteMobs;
        interpretedSigns.forEach(this::addSign);
    }

    private EliteMobsHook eliteMobs() {
        return eliteMobs != null ? eliteMobs : EliteMobsHookSelector.select();
    }

    public Location getCenter() { return center.clone(); }

    /** Square bands share the generated footprint; exact boundaries belong to the less difficult band. */
    public String getDifficultyId(Location location) {
        Objects.requireNonNull(location, "location");
        if (!world.equals(location.getWorld()) || !Double.isFinite(location.getX()) || !Double.isFinite(location.getZ()))
            throw new IllegalArgumentException("Difficulty requires a finite location in the modular world");
        double distance = Math.max(Math.abs(location.getX() - center.getX()), Math.abs(location.getZ() - center.getZ()));
        if (distance < horizontalSize / 6) return "2";
        if (distance < horizontalSize / 3) return "1";
        return "0";
    }

    void addSign(ModulePasting.InterpretedSign interpretedSign) {
        for (String signText : interpretedSign.text()) {
            if (signText.contains("[spawn]"))
                spawnLocations.add(ModulePasting.entitySpawnLocation(interpretedSign.location()));
            else if (signText.contains("[exit]")) {
                processExitLocations(interpretedSign);
            } else if (signText.contains("[trapped_chest]")) {
                trappedChestLocations.add(blockLocation(interpretedSign));
            } else if (signText.contains("[chest]")) {
                chestLocations.add(blockLocation(interpretedSign));
            } else if (signText.contains("[barrel]")) {
                barrelLocations.add(blockLocation(interpretedSign));
            } else
                otherLocations.add(interpretedSign);
        }
    }

    private Location blockLocation(ModulePasting.InterpretedSign sign) {
        return new Location(world,
                (int) sign.location().getX(),
                (int) sign.location().getY(),
                (int) sign.location().getZ());
    }

    public static String extractPoolText(String input) {
        return SpawnPoolsConfig.extractPoolName(input);
    }

    private void processExitLocations(ModulePasting.InterpretedSign interpretedSign) {
        if (interpretedSign.text().size() < 3
                || interpretedSign.text().get(1).isBlank()
                || interpretedSign.text().get(2).isBlank()) {
            Logger.warn("Failed to get both exit clipboard filenames from sign " + interpretedSign.location());
            exitLocations.add(new ExitLocation(blockLocation(interpretedSign),
                    "genericelevator_up",
                    "genericelevator_down"));
            return;
        }

        exitLocations.add(new ExitLocation(blockLocation(interpretedSign),
                interpretedSign.text().get(1),
                interpretedSign.text().get(2)));
    }

    public List<Block> spawnChests() {
        List<Block> chests = new ArrayList<>();
        for (Location chestLocation : chestLocations) {
            chestLocation.getBlock().setType(Material.CHEST);
            chests.add(chestLocation.getBlock());
        }
        return chests;
    }

    public List<Block> spawnBarrels() {
        List<Block> barrels = new ArrayList<>();
        for (Location barrelLocation : barrelLocations) {
            barrelLocation.getBlock().setType(Material.BARREL);
            barrels.add(barrelLocation.getBlock());
        }
        return barrels;
    }

    /** 出口の部品の図面。ファイルの更新日時と大きさが変わらない限り読み直さない。 */
    private record CachedComponent(long lastModified, long length, StructureSchematic schematic) { }

    private static final Map<String, CachedComponent> componentCache = new ConcurrentHashMap<>();

    //todo: maybe this should go into extractioncraft later
    public List<Location> spawnInaccessibleExitLocations() {
        return spawnExitLocations(exitLocation -> exitLocation.clipboardFilenameUp);
    }

    public List<Location> spawnAccessibleExitLocations() {
        return spawnExitLocations(exitLocation -> exitLocation.clipboardFilenameDown);
    }

    private List<Location> spawnExitLocations(Function<ExitLocation, String> componentFilename) {
        List<Location> randomizedLocations = new ArrayList<>();
        if (exitLocations.isEmpty()) return randomizedLocations;

        File componentsFolder = new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath()
                + File.separatorChar + "components");
        List<ExitPaste> pastes = new ArrayList<>();
        for (ExitLocation exitLocation : exitLocations) {
            File exitLocationsFile = new File(componentsFolder, componentFilename.apply(exitLocation) + ".schem");
            if (!exitLocationsFile.exists()) {
                Logger.warn("Failed to find elevator file");
                continue;
            }
            randomizedLocations.add(exitLocation.location);
            pastes.add(new ExitPaste(exitLocationsFile, exitLocation.location));
        }
        if (pastes.isEmpty()) return randomizedLocations;

        // ファイルの読み込みと貼り付けの依頼は呼び出したスレッドを止めずに WorkerPool で行う。
        // 戻り値（出口の位置）は貼り付けの完了を待たない。
        WorkerPool.instance().execute(() -> {
            for (ExitPaste paste : pastes) pasteComponent(paste);
        });
        return randomizedLocations;
    }

    private record ExitPaste(File file, Location location) { }

    private void pasteComponent(ExitPaste paste) {
        StructureSchematic schematic = loadComponent(paste.file());
        if (schematic == null) return;
        PasteRequest request = PasteRequest.builder(world, paste.location(), schematic, NoMarkerHandler.INSTANCE)
                .schematicOffset(new Vector(schematic.getOffsetX(), schematic.getOffsetY(), schematic.getOffsetZ()))
                .schematicName(paste.file().getName())
                .build();
        try {
            Services.pasteEngine().start(request);
        } catch (RuntimeException e) {
            Logger.warn("Failed to paste exit component " + paste.file().getName() + ": " + e.getMessage());
        }
    }

    private static StructureSchematic loadComponent(File file) {
        String key = file.getAbsolutePath();
        CachedComponent cached = componentCache.get(key);
        if (cached != null && cached.lastModified() == file.lastModified() && cached.length() == file.length())
            return cached.schematic();
        try {
            StructureSchematic schematic = SchematicReader.read(file);
            if (schematic == null) return null;
            componentCache.put(key, new CachedComponent(file.lastModified(), file.length(), schematic));
            return schematic;
        } catch (IOException | RuntimeException e) {
            Logger.warn("Failed to read exit component " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    public void spawnOtherEntities() {
        Location targetLoc = center != null ? center : new Location(world, 0, 64, 0);
        com.magmaguy.betterstructures.util.PlatformScheduler.runRegion(targetLoc, () -> {
            for (ModulePasting.InterpretedSign otherLocation : List.copyOf(otherLocations))
                spawnOtherEntitiesAt(otherLocation);
            //got to keep the memory clear for this one, unfortunately
            otherLocations.clear();
            generationFinished();
        });
    }

    void spawnOtherEntitiesAt(ModulePasting.InterpretedSign otherLocation) {
        EliteMobsHook hook = eliteMobs();
        for (String string : otherLocation.text())
            if (string.contains("pool")) {
                String parsedString = extractPoolText(string) + ".yml";
                SpawnPoolsConfigFields spawnPoolsConfigFields = SpawnPoolsConfig.getConfigFields(parsedString);
                if (spawnPoolsConfigFields == null) {
                    Logger.warn("Could not find spawn pool " + parsedString);
                    continue;
                }
                String bossFilename = SpawnPoolsConfig.pickBossFilename(spawnPoolsConfigFields);
                if (bossFilename == null) {
                    Logger.warn("Spawn pool " + parsedString + " has no entries");
                    continue;
                }
                if (!hook.isAvailable()) {
                    if (!missingEliteMobsWarned) {
                        Logger.warn("Spawn pool " + parsedString + " requests bosses, but EliteMobs is not enabled. Its bosses were not spawned.");
                        missingEliteMobsWarned = true;
                    }
                    continue;
                }
                if (!hook.bossExists(bossFilename)) {
                    Logger.warn("Spawn pool " + parsedString + " references missing boss " + bossFilename);
                    continue;
                }
                Location spawnLocation = ModulePasting.entitySpawnLocation(otherLocation.location());
                if (!hook.isInstancedBoss(bossFilename)) {
                    hook.spawnPoolBoss(spawnLocation, bossFilename);
                } else {
                    scheduledInstancedEntities.add(new ScheduledInstancedEntity(spawnLocation, bossFilename,
                            parsedString, spawnPoolsConfigFields.getMaxLevel()));
                }
            }
        otherLocations.remove(otherLocation);
    }

    /**
     * 予約しておいたインスタンス型のボスを出す。戻り値の要素は EliteMobs の {@link InstancedBossEntity}
     * （公開 API なので署名は変えない。EliteMobs が無ければ空のリスト）。
     */
    @SuppressWarnings("unchecked")
    public List<InstancedBossEntity> spawnInstancedEntities() {
        List<Object> spawned = new ArrayList<>();
        EliteMobsHook hook = eliteMobs();
        for (ScheduledInstancedEntity scheduled : scheduledInstancedEntities) {
            Object entity = hook.spawnInstancedBoss(scheduled.location, scheduled.bossFilename, scheduled.level,
                    getDifficultyId(scheduled.location), scheduled.originalSpawnPool);
            if (entity != null) spawned.add(entity);
        }
        return (List<InstancedBossEntity>) (List<?>) spawned;
    }

    public void generationFinished() {
        Bukkit.getServer().getPluginManager().callEvent(new WorldGenerationFinishEvent(this));
    }

    private record ExitLocation(Location location, String clipboardFilenameUp, String clipboardFilenameDown) {
    }

    private record ScheduledInstancedEntity(
            Location location,
            String bossFilename,
            String originalSpawnPool,
            int level
    ){}
}
