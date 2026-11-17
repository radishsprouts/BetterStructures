package com.magmaguy.betterstructures.schematic.reader;

import com.magmaguy.betterstructures.integration.SchematicImport;
import com.magmaguy.magmacore.util.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 読み込みで解釈できず空気にしたブロックを、図面名とブロック名ごとに数え、
 * 読み込みの最後に {@link #logSummary()} で1回だけ警告する（黙って空気にしない）。
 * 複数のスレッドから {@link #unresolvedBlock} を呼んでよい。
 */
public final class UnresolvedBlockReport implements SchematicImport.ConversionReport {
    private static final int MAX_SCHEMATICS_LISTED = 10;
    private static final int MAX_BLOCKS_LISTED = 5;

    private final Map<String, Map<String, LongAdder>> bySchematic = new ConcurrentHashMap<>();

    @Override
    public void unresolvedBlock(String schematicName, String blockName) {
        bySchematic.computeIfAbsent(schematicName, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(blockName, key -> new LongAdder())
                .increment();
    }

    public boolean isEmpty() {
        return bySchematic.isEmpty();
    }

    /** 数えた内容を整形した警告文。何も無ければ null。 */
    public String summary() {
        if (bySchematic.isEmpty()) return null;
        Map<String, Map<String, Long>> sorted = new TreeMap<>();
        long total = 0;
        for (Map.Entry<String, Map<String, LongAdder>> schematic : bySchematic.entrySet()) {
            Map<String, Long> blocks = new TreeMap<>();
            for (Map.Entry<String, LongAdder> block : schematic.getValue().entrySet()) {
                long count = block.getValue().sum();
                blocks.put(block.getKey(), count);
                total += count;
            }
            sorted.put(schematic.getKey(), blocks);
        }

        List<String> lines = new ArrayList<>();
        int listed = 0;
        for (Map.Entry<String, Map<String, Long>> schematic : sorted.entrySet()) {
            if (listed++ >= MAX_SCHEMATICS_LISTED) break;
            List<String> blocks = new ArrayList<>();
            int blocksListed = 0;
            for (Map.Entry<String, Long> block : schematic.getValue().entrySet()) {
                if (blocksListed++ >= MAX_BLOCKS_LISTED) {
                    blocks.add("ほか" + (schematic.getValue().size() - MAX_BLOCKS_LISTED) + "種");
                    break;
                }
                blocks.add(block.getKey() + " x" + block.getValue());
            }
            lines.add(schematic.getKey() + " (" + String.join(", ", blocks) + ")");
        }
        String omitted = sorted.size() > MAX_SCHEMATICS_LISTED
                ? " ほか" + (sorted.size() - MAX_SCHEMATICS_LISTED) + "個の図面。" : "";
        return "今のサーバーの版に変換できず空気に置き換えた種類のブロックがあります（図面 " + sorted.size()
                + "個）。該当の図面を新しい版で保存し直してください: " + String.join("; ", lines) + "." + omitted
                + " 合計 " + total + " ブロック。";
    }

    /** 読み込みの最後に1回だけ呼ぶ。何も無ければ何も出さない。 */
    public void logSummary() {
        String summary = summary();
        if (summary != null) Logger.warn(summary);
    }
}
