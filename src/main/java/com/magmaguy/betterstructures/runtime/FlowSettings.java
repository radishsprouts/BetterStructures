package com.magmaguy.betterstructures.runtime;

/**
 * 流量制御の設定値（config.yml の値を検証して束ねたもの）。runtime パッケージは Bukkit の設定に
 * 依存せず、この値だけを見る（テストで差し替えられる）。
 *
 * @param scanSlots              同時に走査する数（SCAN 枠）
 * @param pasteSlots             同時に貼り付ける数（PASTE 枠）
 * @param wfcSlots               同時に生成するダンジョンの数（WFC 枠）
 * @param chunkLoadLimit         チャンク読み込みのサーバー全体の同時実行数
 * @param chunkLoadLimitPerWorld チャンク読み込みのワールドごとの同時実行数
 * @param pasteTickFraction      1 tick のうち貼り付け等に使ってよい割合（{@code percentageOfTickUsedForPasting}）
 * @param budgetMinMillis        予算の下限（ミリ秒）。遅れで絞っても、これを下回らない
 * @param budgetRecoverMillis    遅れが無い窓が続いた時に、1窓ごとに戻す量（ミリ秒）
 * @param budgetBackoffFactor    遅れを検知した時に予算へ掛ける係数（0 より大きく 1 未満）
 * @param lagThresholdMillis     予約した時刻からの遅れがこれを超えたら「遅れ」とみなす（ミリ秒）
 * @param wfcWindowChunks        ダンジョンの貼り付けで、処理中のチャンクの先に hold する数 K
 * @param metricsEnabled         計測ログを出すか
 * @param metricsIntervalSeconds 計測ログの間隔（秒）
 */
public record FlowSettings(
        int scanSlots,
        int pasteSlots,
        int wfcSlots,
        int chunkLoadLimit,
        int chunkLoadLimitPerWorld,
        double pasteTickFraction,
        double budgetMinMillis,
        double budgetRecoverMillis,
        double budgetBackoffFactor,
        double lagThresholdMillis,
        int wfcWindowChunks,
        boolean metricsEnabled,
        int metricsIntervalSeconds
) {
    public static final int DEFAULT_SCAN_SLOTS = 4;
    public static final int DEFAULT_PASTE_SLOTS = 2;
    public static final int DEFAULT_WFC_SLOTS = 1;
    public static final int DEFAULT_CHUNK_LOAD_LIMIT = 8;
    public static final int DEFAULT_CHUNK_LOAD_LIMIT_PER_WORLD = 8;
    public static final double DEFAULT_PASTE_TICK_FRACTION = 0.2;
    public static final double DEFAULT_BUDGET_MIN_MILLIS = 0.5;
    public static final double DEFAULT_BUDGET_RECOVER_MILLIS = 0.25;
    public static final double DEFAULT_BUDGET_BACKOFF_FACTOR = 0.5;
    public static final double DEFAULT_LAG_THRESHOLD_MILLIS = 100.0;
    public static final int DEFAULT_WFC_WINDOW_CHUNKS = 4;
    public static final boolean DEFAULT_METRICS_ENABLED = false;
    public static final int DEFAULT_METRICS_INTERVAL_SECONDS = 60;

    /** 1 tick の長さ（ナノ秒）。 */
    public static final long TICK_NANOS = 50_000_000L;

    /** 貼り付けの割合の許容範囲（設定の説明 0.01〜1 と同じ）。範囲外は端に丸める。 */
    public static final double MIN_PASTE_TICK_FRACTION = 0.01;
    public static final double MAX_PASTE_TICK_FRACTION = 1.0;

    public FlowSettings {
        if (Double.isNaN(pasteTickFraction)) pasteTickFraction = DEFAULT_PASTE_TICK_FRACTION;
        pasteTickFraction = Math.min(MAX_PASTE_TICK_FRACTION, Math.max(MIN_PASTE_TICK_FRACTION, pasteTickFraction));
    }

    public static FlowSettings defaults() {
        return new FlowSettings(DEFAULT_SCAN_SLOTS, DEFAULT_PASTE_SLOTS, DEFAULT_WFC_SLOTS,
                DEFAULT_CHUNK_LOAD_LIMIT, DEFAULT_CHUNK_LOAD_LIMIT_PER_WORLD, DEFAULT_PASTE_TICK_FRACTION,
                DEFAULT_BUDGET_MIN_MILLIS, DEFAULT_BUDGET_RECOVER_MILLIS, DEFAULT_BUDGET_BACKOFF_FACTOR,
                DEFAULT_LAG_THRESHOLD_MILLIS, DEFAULT_WFC_WINDOW_CHUNKS,
                DEFAULT_METRICS_ENABLED, DEFAULT_METRICS_INTERVAL_SECONDS);
    }

    /** 1窓（50ms）で使ってよい最大のナノ秒。予算の上限。 */
    public long maxBudgetNanos() {
        return (long) (TICK_NANOS * pasteTickFraction);
    }

    public long minBudgetNanos() {
        return Math.min(maxBudgetNanos(), (long) (budgetMinMillis * 1_000_000.0));
    }

    public long recoverNanos() {
        return (long) (budgetRecoverMillis * 1_000_000.0);
    }

    public long lagThresholdNanos() {
        return (long) (lagThresholdMillis * 1_000_000.0);
    }
}
