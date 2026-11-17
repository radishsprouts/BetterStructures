package com.magmaguy.betterstructures.runtime;

import com.magmaguy.betterstructures.util.PlatformScheduler;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 構造物の生成の流量制御を1か所にまとめたもの。走査・貼り付け・ダンジョン生成の同時実行数に枠を
 * 設け、入ってきた仕事を<b>捨てずに</b>順に処理する。
 *
 * <p>動く場所: グローバルリージョンの1tick周期のタイマー（{@link #startTimer()}）で
 * {@link #tick()} を呼ぶ。仕事を取り出して振り分けるだけで、ワールドには触れない。
 * 計算量は振り分けた件数に比例し、待ちが無い tick は {@code pending == 0} を見て即座に返る。
 * {@link #submit} と {@link Ticket#release()} は任意のスレッドから呼べる。
 *
 * <p>順序: 枠ごとに、ワールドを順番に回し（ワールド間で公平）、ワールドの中は古い順（FIFO）。
 * 同じ重複除去の鍵（グリッドのセル等）は、待ち中・実行中の間は二重に入れない。
 *
 * <p>使い方: 仕事は {@link Work#start} を持つ。枠が空くと {@code start} が呼ばれ、
 * そこで重い処理を適切なスレッド（WorkerPool・所有リージョン）へ回して<b>すぐ返る</b>こと。
 * 仕事が終わったら（成功・失敗・取り消しのどれでも）必ず {@link Ticket#release()} を1回呼ぶ。
 * 呼び忘れると枠が戻らないので、try/finally かコールバックの終端で確実に呼ぶ。
 */
public final class GenerationGovernor {

    /** 流量制御の枠の種類。 */
    public enum Lane {
        /** 地形走査 */
        SCAN,
        /** 貼り付け */
        PASTE,
        /** ダンジョン（WFC）の生成 */
        WFC
    }

    /** 枠の返却券。{@link #release()} は何度呼んでも1回分しか効かない。 */
    public interface Ticket {
        void release();
    }

    /**
     * 待たせる仕事1件。
     *
     * @param lane      使う枠
     * @param worldId   所属するワールド（順番の公平さの単位）
     * @param dedupeKey 重複除去の鍵（null なら除去しない）。グリッドのセルの鍵など
     * @param payload   停止時の記録（PasteJournal）などで中身を見るための任意の値
     * @param start     枠が空いた時に呼ぶ。重い処理は別スレッドへ回してすぐ返ること
     */
    public record Work(Lane lane, UUID worldId, Object dedupeKey, Object payload, Consumer<Ticket> start) {
        public Work {
            Objects.requireNonNull(lane, "lane");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(start, "start");
        }
    }

    private record DedupeId(Lane lane, UUID worldId, Object key) {
    }

    private static final class LaneState {
        final ConcurrentSkipListMap<UUID, ConcurrentLinkedQueue<Work>> queues = new ConcurrentSkipListMap<>();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger waiting = new AtomicInteger();
        /** 直近に順番を回したワールド。tick のスレッドだけが触る。 */
        UUID cursor;
    }

    private static volatile GenerationGovernor instance;

    private final Map<Lane, LaneState> lanes = new EnumMap<>(Lane.class);
    private final Set<DedupeId> dedupe = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pending = new AtomicInteger();
    private final java.util.function.Supplier<FlowSettings> settings;
    private PlatformScheduler.TaskHandle timer;

    public GenerationGovernor(java.util.function.Supplier<FlowSettings> settings) {
        this.settings = settings;
        for (Lane lane : Lane.values()) lanes.put(lane, new LaneState());
    }

    /** プラグイン全体で共有する本体。 */
    public static GenerationGovernor instance() {
        GenerationGovernor local = instance;
        if (local == null) {
            synchronized (GenerationGovernor.class) {
                local = instance;
                if (local == null) {
                    local = new GenerationGovernor(RuntimeSettings::get);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** グローバルリージョンの1tick周期のタイマーを始める（二重には始めない）。 */
    public synchronized void startTimer() {
        if (timer != null) return;
        timer = PlatformScheduler.runGlobalTimer(this::tick, 1L, 1L);
    }

    /** タイマーを止める。待ち行列は残す（{@link #clear()} で空にする）。 */
    public synchronized void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** 停止時に呼ぶ。タイマーを止め、共有の本体を手放す。 */
    public static void shutdown() {
        GenerationGovernor local = instance;
        instance = null;
        if (local != null) local.stopTimer();
    }

    /**
     * 仕事を入れる。捨てない。
     *
     * @return 入れた時 true。同じ重複除去の鍵が待ち中・実行中で、入れなかった時 false
     */
    public boolean submit(Work work) {
        Objects.requireNonNull(work, "work");
        if (work.dedupeKey() != null
                && !dedupe.add(new DedupeId(work.lane(), work.worldId(), work.dedupeKey()))) {
            return false;
        }
        LaneState state = lanes.get(work.lane());
        // 数を先に増やす。取り出す側が、入れた仕事に対して数の方が少ないと見ないようにするため。
        state.waiting.incrementAndGet();
        pending.incrementAndGet();
        state.queues.computeIfAbsent(work.worldId(), id -> new ConcurrentLinkedQueue<>()).add(work);
        return true;
    }

    /** 枠を埋められるだけ仕事を振り分ける。グローバルリージョンのタイマーから1tickごとに呼ぶ。 */
    public void tick() {
        if (pending.get() == 0) return;
        FlowSettings s = settings.get();
        dispatch(Lane.SCAN, s.scanSlots());
        dispatch(Lane.PASTE, s.pasteSlots());
        dispatch(Lane.WFC, s.wfcSlots());
    }

    private void dispatch(Lane lane, int slots) {
        LaneState state = lanes.get(lane);
        while (state.waiting.get() > 0 && state.running.get() < slots) {
            Work work = pollNext(state);
            if (work == null) return;
            state.running.incrementAndGet();
            state.waiting.decrementAndGet();
            pending.decrementAndGet();
            Ticket ticket = new TicketImpl(state, work);
            try {
                work.start().accept(ticket);
            } catch (Throwable t) {
                // 仕事の開始に失敗した。枠を返して先へ進む（呼び出し側が終わりを報告している前提）。
                RuntimeLog.warn("流量制御: 仕事の開始に失敗しました (" + lane + "): " + t);
                ticket.release();
            }
        }
    }

    /** ワールドを順番に回し、次の仕事を1件取り出す。 */
    private Work pollNext(LaneState state) {
        if (state.queues.isEmpty()) return null;
        UUID last = state.cursor;
        List<UUID> order = new ArrayList<>(state.queues.keySet());
        // 前回のワールドの次（鍵の順で大きい最初）から回す。無ければ先頭に戻る。
        int from = 0;
        if (last != null) {
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).compareTo(last) > 0) {
                    from = i;
                    break;
                }
            }
        }
        for (int i = 0; i < order.size(); i++) {
            UUID world = order.get((from + i) % order.size());
            ConcurrentLinkedQueue<Work> queue = state.queues.get(world);
            Work work = queue == null ? null : queue.poll();
            if (work != null) {
                state.cursor = world;
                return work;
            }
        }
        return null;
    }

    private final class TicketImpl implements Ticket {
        private final LaneState state;
        private final Work work;
        private final AtomicBoolean released = new AtomicBoolean();

        private TicketImpl(LaneState state, Work work) {
            this.state = state;
            this.work = work;
        }

        @Override
        public void release() {
            if (!released.compareAndSet(false, true)) return;
            state.running.decrementAndGet();
            if (work.dedupeKey() != null) {
                dedupe.remove(new DedupeId(work.lane(), work.worldId(), work.dedupeKey()));
            }
        }
    }

    /** いま実行中の数（枠の使用数）。 */
    public int running(Lane lane) {
        return lanes.get(lane).running.get();
    }

    /** 待っている数。 */
    public int waiting(Lane lane) {
        return lanes.get(lane).waiting.get();
    }

    /** 待っている仕事の写し（古い順ではなくワールドごとの塊）。停止時の記録（PasteJournal）用。 */
    public List<Work> pendingSnapshot(Lane lane) {
        List<Work> result = new ArrayList<>();
        for (ConcurrentLinkedQueue<Work> queue : lanes.get(lane).queues.values()) {
            result.addAll(queue);
        }
        return result;
    }

    /** 待っている仕事を全部取り除く（停止の最後に使う）。実行中のものには触れない。 */
    public int clear() {
        int removed = 0;
        for (Map.Entry<Lane, LaneState> entry : lanes.entrySet()) {
            LaneState state = entry.getValue();
            for (ConcurrentLinkedQueue<Work> queue : state.queues.values()) {
                Work work;
                while ((work = queue.poll()) != null) {
                    removed++;
                    state.waiting.decrementAndGet();
                    pending.decrementAndGet();
                    if (work.dedupeKey() != null) {
                        dedupe.remove(new DedupeId(work.lane(), work.worldId(), work.dedupeKey()));
                    }
                }
            }
        }
        return removed;
    }
}
