package com.magmaguy.betterstructures.worldedit;

public final class PasteBudget {
    private PasteBudget() { }
    public static long nanosPerTick(double percentage) {
        return Math.max((long) (50_000_000D * percentage), 2_000_000L);
    }
}
