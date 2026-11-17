package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.runtime.GenerationGovernor;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * BetterStructures 自前の貼り付けエンジン（WorldEdit/FAWE を使わない。04章 G7）。
 *
 * <p>配線: 統合時に {@code Services.setPasteEngine(new NativePasteEngine(this))}。
 * 前回の停止で途中だった貼り付けは、起動後に {@link #setResumer} で図面の引き直し方を渡してから
 * {@link #resumeFromJournal()} を呼ぶと続きから流れる（コンストラクタでは始めない）。
 * 停止（onDisable）は {@link #shutdown()}。
 *
 * <p>スレッド: 世界を書き換える仕事は全部、そのチャンクの所有リージョンで
 * {@link com.magmaguy.betterstructures.runtime.ThreadTickBudget} の範囲だけ行う。
 * 図面を舐める計画づくりと記録の書き込みは WorkerPool。メイン・リージョン・グローバルのスレッドでは待たない
 * （唯一の例外は {@link #shutdown()} の最後の記録の書き込み）。
 */
public final class NativePasteEngine implements PasteEngine {

    /**
     * 記録から貼り付けを作り直す。図面の引き直し（{@code entry.schematicName}・{@code rotationDegrees}）や、
     * {@code entry.worldId} からの世界の取得（{@code Bukkit.getWorld(UUID)}）などを行う。
     * WorkerPool のスレッドから呼ばれる（世界のブロックには触れないこと）。
     */
    @FunctionalInterface
    public interface Resumer {
        /** 作り直せない（図面が無い等）時は null。記録はそのまま残る。 */
        PasteRequest rebuild(PasteJournal.Entry entry);
    }

    /** 記録を書く間隔(tick)。30 秒。 */
    static final long JOURNAL_PERIOD_TICKS = 600L;

    private final PasteEnvironment env;
    private final PasteJournal journal;
    private final Set<PasteSession> sessions = ConcurrentHashMap.newKeySet();
    private final List<PasteJournal.Entry> unresumed = new ArrayList<>();
    private final List<PasteJournal.ScanEntry> restoredScans = new ArrayList<>();
    private final AtomicBoolean writeQueued = new AtomicBoolean();
    private volatile boolean shutdown;
    private volatile Resumer resumer;
    private volatile Supplier<List<PasteJournal.ScanEntry>> scanQueueSource;
    private volatile Consumer<List<PasteJournal.ScanEntry>> scanQueueRestorer;
    private volatile boolean lastWriteEmpty = true;
    private PlatformScheduler.TaskHandle journalTimer;

    /** 本番用。記録はプラグインのデータフォルダの {@code paste-journal.json}。 */
    public NativePasteEngine(Plugin plugin) {
        this(PasteEnvironment.production(plugin.getDataFolder().toPath().resolve("paste-journal.json")));
        GenerationGovernor.instance().startTimer();
        this.journalTimer = PlatformScheduler.runAsyncTimer(this::writeJournalAsync, JOURNAL_PERIOD_TICKS, JOURNAL_PERIOD_TICKS);
    }

    /** 試験用。外の仕組みを差し替えられる。 */
    NativePasteEngine(PasteEnvironment env) {
        this.env = env;
        this.journal = new PasteJournal(env.journalPath());
    }

    // ---- 貼り付け --------------------------------------------------------------------------

    @Override
    public PasteHandle start(PasteRequest request) {
        return submit(new PasteSession(this, env, request, PasteSession.newId(), null));
    }

    /** 記録から作り直した依頼で、途中の続きから貼り付ける。 */
    public PasteHandle resume(PasteRequest request, PasteJournal.Entry entry) {
        return submit(new PasteSession(this, env, request, entry.sessionId, entry));
    }

    private PasteHandle submit(PasteSession session) {
        if (shutdown) {
            session.cancel();
            return session;
        }
        sessions.add(session);
        World world = session.world();
        boolean accepted = env.governor().submit(new GenerationGovernor.Work(
                GenerationGovernor.Lane.PASTE, world.getUID(), null, session, session::begin));
        if (!accepted) {
            // 重複除去の鍵を付けていないので起きないが、受け付けられなければ捨てずに取り消しとして終える。
            session.cancel();
        }
        return session;
    }

    @Override
    public int pendingCount() {
        return sessions.size();
    }

    void sessionEnded(PasteSession session) {
        sessions.remove(session);
        if (!shutdown) queueJournalWrite();
    }

    // ---- 再開 ------------------------------------------------------------------------------

    public void setResumer(Resumer resumer) {
        this.resumer = resumer;
    }

    /** 走査の待ち行列を記録に載せるための取り出し口（U1/本体が接続する）。 */
    public void setScanQueueSource(Supplier<List<PasteJournal.ScanEntry>> source) {
        this.scanQueueSource = source;
    }

    /** 記録にあった走査の待ち行列を受け取る口（U1/本体が接続する）。未設定の間は記録に保持して書き戻す。 */
    public void setScanQueueRestorer(Consumer<List<PasteJournal.ScanEntry>> restorer) {
        this.scanQueueRestorer = restorer;
    }

    /**
     * 前回の停止で途中だった貼り付けを、記録から続きで始める。読み込みも図面の引き直しも WorkerPool で行い、
     * 呼んだスレッドは待たない。{@link #setResumer} が未設定なら警告を出して記録をそのまま保持する（捨てない）。
     */
    public void resumeFromJournal() {
        env.async().execute(() -> {
            try {
                PasteJournal.Data data = journal.read();
                handleScans(data.scans);
                for (PasteJournal.Entry entry : data.pastes) {
                    resumeOne(entry);
                }
            } catch (Throwable t) {
                Logger.warn("貼り付けの記録からの再開に失敗しました: " + t);
            }
        });
    }

    private void handleScans(List<PasteJournal.ScanEntry> scans) {
        if (scans.isEmpty()) return;
        Consumer<List<PasteJournal.ScanEntry>> restorer = scanQueueRestorer;
        if (restorer != null) {
            restorer.accept(List.copyOf(scans));
        } else {
            synchronized (restoredScans) {
                restoredScans.addAll(scans);
            }
        }
    }

    private void resumeOne(PasteJournal.Entry entry) {
        Resumer local = resumer;
        if (local == null) {
            Logger.warn("貼り付けの記録 " + entry.schematicName + " を再開する手段がまだ設定されていません。記録は保持します。");
            keepUnresumed(entry);
            return;
        }
        PasteRequest request;
        try {
            request = local.rebuild(entry);
        } catch (Throwable t) {
            Logger.warn("貼り付けの記録 " + entry.schematicName + " を作り直せませんでした。記録は保持します: " + t);
            keepUnresumed(entry);
            return;
        }
        if (request == null) {
            Logger.warn("貼り付けの記録 " + entry.schematicName + " の図面が見つかりません。記録は保持します。");
            keepUnresumed(entry);
            return;
        }
        resume(request, entry);
    }

    private void keepUnresumed(PasteJournal.Entry entry) {
        synchronized (unresumed) {
            unresumed.add(entry);
        }
    }

    // ---- 記録の書き込み --------------------------------------------------------------------

    private PasteJournal.Data snapshot() {
        PasteJournal.Data data = new PasteJournal.Data();
        for (PasteSession session : sessions) {
            // 計画ができる前の貼り付けも、図面名と anchor を記録する（続きは最初から）。
            data.pastes.add(session.toEntry());
        }
        synchronized (unresumed) {
            data.pastes.addAll(unresumed);
        }
        Supplier<List<PasteJournal.ScanEntry>> source = scanQueueSource;
        if (source != null) {
            data.scans.addAll(source.get());
        }
        synchronized (restoredScans) {
            data.scans.addAll(restoredScans);
        }
        return data;
    }

    private void queueJournalWrite() {
        if (!writeQueued.compareAndSet(false, true)) return;
        env.async().execute(() -> {
            writeQueued.set(false);
            writeJournalNow();
        });
    }

    private void writeJournalAsync() {
        if (shutdown) return;
        boolean idle = sessions.isEmpty() && lastWriteEmpty && scanQueueSource == null;
        if (idle) return;
        queueJournalWrite();
    }

    /** 今の状態を記録へ書く。WorkerPool のスレッドから呼ぶ（停止時だけ例外で、呼んだスレッドで書く）。 */
    void writeJournalNow() {
        try {
            PasteJournal.Data data = snapshot();
            journal.write(data);
            lastWriteEmpty = data.pastes.isEmpty() && data.scans.isEmpty();
        } catch (IOException | RuntimeException e) {
            Logger.warn("貼り付けの記録を書けませんでした: " + e.getMessage());
        }
    }

    // ---- 停止 ------------------------------------------------------------------------------

    /**
     * 停止（onDisable）。走っている貼り付けを記録に残したまま止め、hold を返し、最後の記録を<b>同期で</b>1回書く
     * （これが待ってよい唯一の場所）。次の起動で {@link #resumeFromJournal()} が続きから流す。
     */
    @Override
    public void shutdown() {
        shutdown = true;
        if (journalTimer != null) {
            journalTimer.cancel();
            journalTimer = null;
        }
        List<PasteSession> running = new ArrayList<>(sessions);
        for (PasteSession session : running) {
            session.suspend();
        }
        PasteJournal.Data data = snapshot();
        try {
            journal.write(data);
        } catch (IOException | RuntimeException e) {
            Logger.warn("停止時に貼り付けの記録を書けませんでした: " + e.getMessage());
        }
        sessions.clear();
    }
}
