package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.integration.RegionProtection;
import com.magmaguy.betterstructures.runtime.FlowSettings;
import com.magmaguy.betterstructures.runtime.GenerationGovernor;
import com.magmaguy.betterstructures.runtime.ThreadTickBudget;
import com.magmaguy.betterstructures.schematic.model.StructureBlock;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.world.ChunkMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 貼り付けエンジンの試験の土台。サーバー（MockBukkit）の上に、次を用意する。
 * <ul>
 *   <li>チケットを記録し、ブロックの読み書きの瞬間にそのチャンクのチケットが付いているかを検査するワールド</li>
 *   <li>手で進められる「リージョンのタスク置き場」（今の tick のぶんと、次の tick のぶん）と、手で進める時計</li>
 *   <li>ブロックに触れるたびに時計を進める（予算を使い切る場面を作るため）</li>
 * </ul>
 */
final class PasteTestKit {

    /** ブロックを読み書きした時にそのチャンクのチケットが無かった記録。 */
    final List<String> ticketViolations = new ArrayList<>();
    /** チケットの付け外しの履歴（"+x,z" / "-x,z"）。 */
    final List<String> ticketHistory = new ArrayList<>();
    final Set<String> tickets = new HashSet<>();
    final AtomicLong clock = new AtomicLong(1_000_000_000L);
    /** ブロック1回に触れるごとに進める時計のナノ秒。 */
    volatile long costPerAccessNanos = 0L;
    /** チケットの検査を行うか（試験自身の読み取りでは切る）。 */
    volatile boolean checking;

    final ServerMock server;
    final Plugin plugin;
    final RecordingWorld world;
    final GenerationGovernor governor;
    final ThreadTickBudget budget;
    final PasteEnvironment env;
    final NativePasteEngine engine;

    /** 次の tick で動かすタスク（run も runLater(…,1) も同じ。実行中に積まれたものは次の tick）。 */
    private List<Runnable> pending = new ArrayList<>();
    final List<Long> laterDelays = new ArrayList<>();
    final List<Long> globalDelays = new ArrayList<>();
    private final List<Runnable> globalLater = new ArrayList<>();

    /** 読み込みを失敗させる条件（チャンク x,z と何回目か → 例外を返すなら非 null）。 */
    BiFunction<Long, Integer, Throwable> loadFailure = (key, attempt) -> null;
    private final Map<Long, Integer> loadCalls = new HashMap<>();
    final List<Long> loadedKeys = new ArrayList<>();
    RegionProtection protection = RegionProtection.none();

    PasteTestKit(Path journalPath) {
        // MockBukkit の getWorld(UUID) は登録したワールドをそのまま返さないので、同一のものを返すようにする。
        final World[] registered = new World[1];
        server = MockBukkit.mock(new ServerMock() {
            @Override
            public World getWorld(java.util.UUID uid) {
                World w = registered[0];
                return w != null && w.getUID().equals(uid) ? w : null;
            }
        });
        plugin = MockBukkit.createMockPlugin("PasteEngineFixture");
        MetadataHandler.PLUGIN = plugin;
        world = new RecordingWorld();
        world.setName("paste-engine-test");
        server.addWorld(world);
        registered[0] = world;
        governor = new GenerationGovernor(FlowSettings::defaults);
        budget = new ThreadTickBudget(clock::get, FlowSettings::defaults);
        env = new PasteEnvironment(
                (w, x, z) -> {
                    long key = Chunk.getChunkKey(x, z);
                    int call = loadCalls.merge(key, 1, Integer::sum);
                    Throwable failure = loadFailure.apply(key, call);
                    loadedKeys.add(key);
                    return failure == null
                            ? CompletableFuture.completedFuture(null)
                            : CompletableFuture.failedFuture(failure);
                },
                new PasteEnvironment.Regions() {
                    @Override
                    public void run(World w, int chunkX, int chunkZ, Runnable task) {
                        pending.add(guard(chunkX, chunkZ, task));
                    }

                    @Override
                    public void runLater(World w, int chunkX, int chunkZ, Runnable task, long delayTicks) {
                        laterDelays.add(delayTicks);
                        pending.add(guard(chunkX, chunkZ, task));
                    }

                    @Override
                    public boolean isOwned(World w, int chunkX, int chunkZ) {
                        return false;
                    }
                },
                Runnable::run,
                (task, delay) -> {
                    globalDelays.add(delay);
                    globalLater.add(task);
                },
                () -> budget,
                governor,
                journalPath,
                () -> 1L,
                () -> protection,
                w -> true);
        engine = new NativePasteEngine(env);
    }

    /** チャンクの所有リージョンで動くタスクが、その間そのチャンクのチケットを持っていることを検査する。 */
    private Runnable guard(int chunkX, int chunkZ, Runnable task) {
        return () -> {
            String key = chunkX + "," + chunkZ;
            // 書き込みのタスクだけ検査する（完了後の闘技場保護・通知のタスクはブロックに触れないので対象外）。
            if (checking && task instanceof ChunkPasteTask && !tickets.contains(key)) {
                ticketViolations.add("タスク開始時にチケットが無い: " + key + " 現在=" + tickets + " 履歴=" + ticketHistory);
            }
            task.run();
        };
    }

    // ---- 進め方 ----------------------------------------------------------------------------

    /** 1 tick ぶん進める: 時計を 50ms 進め、流量制御とサーバーのタスクを流し、今の tick のタスクを全部実行する。 */
    void tick() {
        clock.addAndGet(FlowSettings.TICK_NANOS);
        governor.tick();
        server.getScheduler().performOneTick();
        List<Runnable> run = pending;
        pending = new ArrayList<>();
        List<Runnable> global = new ArrayList<>(globalLater);
        globalLater.clear();
        checking = true;
        try {
            for (Runnable task : global) task.run();
            for (Runnable task : run) task.run();
        } finally {
            checking = false;
        }
    }

    /** 全部終わるか上限の tick に達するまで進める。 */
    int tickUntil(java.util.function.BooleanSupplier done, int maxTicks) {
        int ticks = 0;
        while (!done.getAsBoolean() && ticks < maxTicks) {
            tick();
            ticks++;
        }
        return ticks;
    }

    boolean idle() {
        return pending.isEmpty() && globalLater.isEmpty();
    }

    void close() {
        PluginChunkHolds.shutdown();
        MetadataHandler.PLUGIN = null;
        MockBukkit.unmock();
    }

    // ---- 図面 ------------------------------------------------------------------------------

    /** 図面づくり。座標 → BlockData を返す関数と、ブロックエンティティ・看板を後から足せる。 */
    static final class SchemBuilder {
        private final short w;
        private final short h;
        private final short l;
        private final BlockData[] blocks;
        private final Map<Integer, SimpleNbt.CompoundTag> entities = new HashMap<>();
        private final Map<Integer, SignTextParser.ParsedSign> signs = new HashMap<>();
        private final List<com.magmaguy.betterstructures.schematic.model.StructureEntity> mobs = new ArrayList<>();

        SchemBuilder(int w, int h, int l) {
            this.w = (short) w;
            this.h = (short) h;
            this.l = (short) l;
            this.blocks = new BlockData[w * h * l];
            fill(Material.AIR);
        }

        SchemBuilder fill(Material material) {
            for (int i = 0; i < blocks.length; i++) blocks[i] = Bukkit.createBlockData(material);
            return this;
        }

        private int index(int x, int y, int z) {
            return (y * l + z) * w + x;
        }

        SchemBuilder set(int x, int y, int z, Material material) {
            blocks[index(x, y, z)] = Bukkit.createBlockData(material);
            return this;
        }

        SchemBuilder set(int x, int y, int z, String blockData) {
            blocks[index(x, y, z)] = Bukkit.createBlockData(blockData);
            return this;
        }

        SchemBuilder layer(int y, Material material) {
            for (int z = 0; z < l; z++)
                for (int x = 0; x < w; x++) set(x, y, z, material);
            return this;
        }

        SchemBuilder nbt(int x, int y, int z, SimpleNbt.CompoundTag tag) {
            entities.put(index(x, y, z), tag);
            return this;
        }

        SchemBuilder sign(int x, int y, int z, Material material, String... lines) {
            set(x, y, z, material);
            String[] front = new String[4];
            for (int i = 0; i < 4; i++) front[i] = i < lines.length ? lines[i] : "";
            signs.put(index(x, y, z), new SignTextParser.ParsedSign(front, new String[4],
                    org.bukkit.DyeColor.BLACK, org.bukkit.DyeColor.BLACK, false, false, false));
            return this;
        }

        SchemBuilder entity(double x, double y, double z, String id, SimpleNbt.CompoundTag tag) {
            mobs.add(new com.magmaguy.betterstructures.schematic.model.StructureEntity(x, y, z, 0f, 0f, id, tag));
            return this;
        }

        StructureSchematic build() {
            return new StructureSchematic(w, h, l, 0, 0, 0, blocks, entities, signs, mobs);
        }
    }

    /** [m] と書かれた看板を目印として扱う試験用の処理。目印は空気にして位置を記録する。 */
    static final class TestMarkers implements MarkerHandler {
        final List<String> handled = new ArrayList<>();
        final PlainSignPolicy policy;

        TestMarkers() {
            this(PlainSignPolicy.KEEP_TEXT);
        }

        TestMarkers(PlainSignPolicy policy) {
            this.policy = policy;
        }

        @Override
        public boolean isMarker(StructureBlock block) {
            return block.isSign() && block.parsedSign().getFrontLine(0).equals("[m]");
        }

        @Override
        public void handleMarker(World world, int x, int y, int z, StructureBlock block) {
            world.getBlockAt(x, y, z).setType(Material.AIR, false);
            handled.add(x + "," + y + "," + z);
        }

        @Override
        public PlainSignPolicy plainSignPolicy() {
            return policy;
        }
    }

    PasteRequest.Builder request(StructureSchematic schematic, int x, int y, int z, MarkerHandler handler) {
        return PasteRequest.builder(world, new Location(world, x, y, z), schematic, handler).schematicName("test");
    }

    // ---- ワールド --------------------------------------------------------------------------

    final class RecordingWorld extends WorldMock {
        @Override
        public boolean isChunkLoaded(int x, int z) {
            return true;
        }

        @Override
        public ChunkMock getChunkAt(int x, int z) {
            ChunkMock chunk = mock(ChunkMock.class);
            when(chunk.getX()).thenReturn(x);
            when(chunk.getZ()).thenReturn(z);
            when(chunk.getWorld()).thenReturn(this);
            when(chunk.isLoaded()).thenReturn(true);
            when(chunk.getEntities()).thenAnswer(call -> getEntities().stream()
                    .filter(e -> (e.getLocation().getBlockX() >> 4) == x && (e.getLocation().getBlockZ() >> 4) == z)
                    .toArray(org.bukkit.entity.Entity[]::new));
            return chunk;
        }

        @Override
        public BlockMock getBlockAt(int x, int y, int z) {
            clock.addAndGet(costPerAccessNanos);
            if (checking) {
                String key = (x >> 4) + "," + (z >> 4);
                if (!tickets.contains(key)) {
                    ticketViolations.add("ブロックに触れた時にチケットが無い: " + key + " (" + x + "," + y + "," + z + ")");
                }
            }
            return super.getBlockAt(x, y, z);
        }

        @Override
        public boolean addPluginChunkTicket(int x, int z, Plugin owner) {
            ticketHistory.add("+" + x + "," + z);
            return tickets.add(x + "," + z);
        }

        @Override
        public boolean removePluginChunkTicket(int x, int z, Plugin owner) {
            ticketHistory.add("-" + x + "," + z);
            return tickets.remove(x + "," + z);
        }
    }
}
