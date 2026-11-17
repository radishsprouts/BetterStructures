package com.magmaguy.betterstructures.runtime;

import com.magmaguy.betterstructures.integration.RegionProtection;
import com.magmaguy.betterstructures.paste.PasteEngine;

/**
 * 段B の単位どうしが、相手の実装クラスを直接参照せずに実体を受け取るための受け渡し口。
 *
 * <p><b>設定するのは</b>: onEnable の配線（統合時に本体が {@code BetterStructures} へ足す）。
 * PasteEngine は U2 の {@code paste.NativePasteEngine}、RegionProtection は U4 の
 * {@code integration.RegionProtectionSelector.select()} を入れる。
 * <b>使うのは</b>: U1・U3・U5。試験では偽の実装を {@code set*} で入れ、終わったら {@link #clear()} する。
 *
 * <p>どのスレッドから読んでもよい（volatile）。
 */
public final class Services {

    private static volatile PasteEngine pasteEngine;
    private static volatile RegionProtection regionProtection = RegionProtection.none();

    private Services() {
    }

    /** 貼り付けエンジン。未設定（起動前・停止後）なら IllegalStateException。 */
    public static PasteEngine pasteEngine() {
        PasteEngine engine = pasteEngine;
        if (engine == null) {
            throw new IllegalStateException("PasteEngine is not installed");
        }
        return engine;
    }

    /** 貼り付けエンジン。未設定なら null（停止処理のように、無くても続ける所で使う）。 */
    public static PasteEngine pasteEngineOrNull() {
        return pasteEngine;
    }

    public static void setPasteEngine(PasteEngine engine) {
        pasteEngine = engine;
    }

    /** 保護区域の連携。WorldGuard が無ければ {@link RegionProtection#none()}。 */
    public static RegionProtection regionProtection() {
        return regionProtection;
    }

    public static void setRegionProtection(RegionProtection protection) {
        regionProtection = protection == null ? RegionProtection.none() : protection;
    }

    /** 停止時と試験の後片付けで呼ぶ。 */
    public static void clear() {
        pasteEngine = null;
        regionProtection = RegionProtection.none();
    }
}
