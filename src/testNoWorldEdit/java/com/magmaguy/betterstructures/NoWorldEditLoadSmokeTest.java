package com.magmaguy.betterstructures;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * WorldEdit / FAWE がクラスパスに無くても、WorldEdit を参照しないクラスが読み込めて呼べることの煙試験。
 * 本格的な読み込みテスト（PasteEngine 等）は、そのクラスができた後の段C で足す。
 */
class NoWorldEditLoadSmokeTest {
    @Test
    void pluginMainClassLoadsAndInitializesWithoutWorldEdit() {
        // クラスの読み込み・検証・静的初期化が NoClassDefFoundError にならないこと。
        assertFalse(BetterStructures.isReloading());
    }
}
