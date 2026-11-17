package com.magmaguy.betterstructures.runtime;

/**
 * 流量制御の現在の設定の置き場。{@code DefaultConfig} が読み込みのたびに {@link #set} し、
 * runtime の各クラスが呼び出しのたびに {@link #get()} で読む（再読み込みが次の仕事から効く）。
 */
public final class RuntimeSettings {
    private static volatile FlowSettings current = FlowSettings.defaults();

    private RuntimeSettings() {
    }

    public static FlowSettings get() {
        return current;
    }

    public static void set(FlowSettings settings) {
        current = settings == null ? FlowSettings.defaults() : settings;
    }
}
