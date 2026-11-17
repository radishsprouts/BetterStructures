package com.magmaguy.betterstructures.runtime;

import com.magmaguy.magmacore.util.Logger;

/**
 * runtime の警告・情報ログ。プラグインが動いていない環境（単体テスト）でも例外を出さず、標準エラーへ回す。
 */
final class RuntimeLog {
    private RuntimeLog() {
    }

    static void warn(String message) {
        try {
            Logger.warn(message);
        } catch (Throwable t) {
            System.err.println("[BetterStructures] " + message);
        }
    }

    static void info(String message) {
        try {
            Logger.info(message);
        } catch (Throwable t) {
            System.err.println("[BetterStructures] " + message);
        }
    }
}
