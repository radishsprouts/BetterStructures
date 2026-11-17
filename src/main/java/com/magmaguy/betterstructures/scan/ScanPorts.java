package com.magmaguy.betterstructures.scan;

import com.magmaguy.betterstructures.util.PluginChunkHolds;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * {@link ScanJob} がワールドとスレッドに触れる口をまとめたもの。本番は {@code BukkitScanPorts}、試験は偽の実装。
 * ScanJob 自身は Bukkit のスレッドの決まりを知らず、ここを通してだけ読み込み・スケジュールを行う。
 */
public interface ScanPorts {

    String worldName();

    World.Environment environment();

    /** {@link World#getMinHeight()} と同じ意味。 */
    int minY();

    /** {@link World#getMaxHeight()} と同じ意味。 */
    int maxY();

    /**
     * チャンクを（必要なら読み込み・生成して）hold し、スナップショットを取る。完了は任意のスレッドでよい
     * （本番は所有リージョン）。呼び出した側は、完了を待たずに続きを future につなぐこと。
     * 失敗（読み込みの失敗・取り消し）は future の例外で返す。
     */
    CompletableFuture<ChunkData> acquire(int chunkX, int chunkZ);

    /** 重い計算を WorkerPool で行う。 */
    void worker(Runnable task);

    /** 走査の途中で再読み込みやプラグインの停止があった（結果がもう使えない）か。 */
    boolean stale();

    /** この走査が hold しているチャンク。貼り付けへ引き継ぐ時、または捨てる時に使う。 */
    PluginChunkHolds.HoldSet holds();
}
