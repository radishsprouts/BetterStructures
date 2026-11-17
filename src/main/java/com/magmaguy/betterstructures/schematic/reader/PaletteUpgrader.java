package com.magmaguy.betterstructures.schematic.reader;

import org.bukkit.Bukkit;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 古い版で保存された図面のパレットのうち、今のサーバーが解釈できないブロック状態を新しい名前へ変換する。
 *
 * <p>WorldEdit が無い環境でも古い図面のブロックが空気にならないようにするための処理で、
 * 読み込み時（起動・{@code /bs reload}）にだけ、<b>直接解釈できなかったパレットの項目だけ</b>に対して行う。
 * 貼り付けの時の負荷にはならない。
 *
 * <p>順序:
 * <ol>
 *   <li>バニラの構造物 NBT を組み直し、元の DataVersion を付けて {@code StructureManager#loadStructure} に通す
 *       （バニラの DataFixer がかかる）。結果の {@code Structure#getPalettes()} から BlockData を読み戻す。</li>
 *   <li>1 が使えない（サーバーが無い・失敗）か、変換しきれなかった項目は、同梱の旧名→新名の対応表で置き換える。</li>
 * </ol>
 * 変換できなかった項目は結果に含めない（呼び出し側が空気にして報告する）。
 */
public final class PaletteUpgrader {

    /** バニラの DataFixer を通す部分。試験で差し替えられるように切り出してある。 */
    @FunctionalInterface
    public interface VanillaConverter {
        /**
         * @param dataVersion 図面の元の DataVersion
         * @param states      変換したいブロック状態の文字列（{@code minecraft:foo[a=b]}）
         * @return 変換できたものだけを入れた対応（キーは states の文字列）
         */
        Map<String, BlockData> convert(int dataVersion, List<String> states) throws Exception;
    }

    /** 旧名→新名。バニラの DataFixer が使えない時の保険で、ブロックの ID の改名だけを扱う。 */
    private static final Map<String, String> RENAMES = Map.ofEntries(
            Map.entry("minecraft:grass_path", "minecraft:dirt_path"),
            Map.entry("minecraft:grass", "minecraft:short_grass"),
            Map.entry("minecraft:sign", "minecraft:oak_sign"),
            Map.entry("minecraft:wall_sign", "minecraft:oak_wall_sign"),
            Map.entry("minecraft:magma", "minecraft:magma_block"),
            Map.entry("minecraft:melon_block", "minecraft:melon"),
            Map.entry("minecraft:silver_shulker_box", "minecraft:light_gray_shulker_box"),
            Map.entry("minecraft:snow_layer", "minecraft:snow"),
            Map.entry("minecraft:web", "minecraft:cobweb"),
            Map.entry("minecraft:waterlily", "minecraft:lily_pad"),
            Map.entry("minecraft:brick_block", "minecraft:bricks"),
            Map.entry("minecraft:stonebrick", "minecraft:stone_bricks"),
            Map.entry("minecraft:noteblock", "minecraft:note_block"),
            Map.entry("minecraft:fence", "minecraft:oak_fence"),
            Map.entry("minecraft:fence_gate", "minecraft:oak_fence_gate"),
            Map.entry("minecraft:wooden_door", "minecraft:oak_door"),
            Map.entry("minecraft:trapdoor", "minecraft:oak_trapdoor"),
            Map.entry("minecraft:wooden_button", "minecraft:oak_button"),
            Map.entry("minecraft:wooden_pressure_plate", "minecraft:oak_pressure_plate"),
            Map.entry("minecraft:lit_furnace", "minecraft:furnace"),
            Map.entry("minecraft:red_nether_brick", "minecraft:red_nether_bricks"),
            Map.entry("minecraft:nether_brick", "minecraft:nether_bricks"),
            Map.entry("minecraft:end_bricks", "minecraft:end_stone_bricks"),
            Map.entry("minecraft:chorus_flower_plant", "minecraft:chorus_plant"));

    private PaletteUpgrader() {
    }

    /** サーバーのバニラ変換を使って変換する。 */
    public static Map<String, BlockData> upgrade(int dataVersion, Collection<String> states) {
        return upgrade(dataVersion, states, PaletteUpgrader::convertWithServer);
    }

    public static Map<String, BlockData> upgrade(int dataVersion, Collection<String> states, VanillaConverter vanilla) {
        Map<String, BlockData> result = new LinkedHashMap<>();
        if (states == null || states.isEmpty()) return result;
        List<String> remaining = new ArrayList<>(new java.util.LinkedHashSet<>(states));

        if (dataVersion > 0) {
            try {
                Map<String, BlockData> converted = vanilla.convert(dataVersion, List.copyOf(remaining));
                if (converted != null) {
                    for (String state : remaining) {
                        BlockData data = converted.get(state);
                        if (data != null) result.put(state, data);
                    }
                    remaining.removeAll(result.keySet());
                }
            } catch (Exception | LinkageError ignored) {
                // サーバーが構造物の読み込みに応じない場合は、下の対応表で続ける
            }
        }

        for (String state : remaining) {
            BlockData data = convertWithTable(state);
            if (data != null) result.put(state, data);
        }
        return result;
    }

    /** 旧名の対応表だけで変換する（失敗は null）。 */
    static BlockData convertWithTable(String state) {
        ParsedState parsed = ParsedState.parse(state);
        String renamed = RENAMES.get(parsed.name());
        if (renamed == null) return null;
        return createOrNull(renamed + parsed.propertiesSuffix());
    }

    static BlockData createOrNull(String state) {
        try {
            return Bukkit.createBlockData(state);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- バニラの DataFixer を通す ----

    private static Map<String, BlockData> convertWithServer(int dataVersion, List<String> states) throws Exception {
        byte[] nbt = buildStructureNbt(dataVersion, states);
        Structure structure = Bukkit.getStructureManager().loadStructure(new ByteArrayInputStream(nbt));
        List<Palette> palettes = structure.getPalettes();
        if (palettes.isEmpty()) return Map.of();
        List<BlockState> blocks = palettes.get(0).getBlocks();

        Map<String, BlockData> result = new HashMap<>();
        // 位置 (i, 0, 0) に i 番目の項目を置いてあるので、位置で対応させる。位置で引けなければ並び順で対応させる。
        boolean positional = blocks.size() == states.size();
        if (positional) {
            boolean[] seen = new boolean[states.size()];
            for (BlockState block : blocks) {
                int x = block.getX();
                if (x < 0 || x >= seen.length || seen[x]) {
                    positional = false;
                    break;
                }
                seen[x] = true;
            }
            if (positional) {
                for (BlockState block : blocks) result.put(states.get(block.getX()), block.getBlockData());
                return result;
            }
        }
        if (blocks.size() == states.size()) {
            for (int i = 0; i < states.size(); i++) result.put(states.get(i), blocks.get(i).getBlockData());
        }
        return result;
    }

    /** {@code states} を 1 ブロックずつ横一列に並べた、元の DataVersion 付きのバニラ構造物 NBT（gzip）。 */
    static byte[] buildStructureNbt(int dataVersion, List<String> states) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(buffer))) {
            out.writeByte(10); // TAG_Compound（ルート）
            writeName(out, "");

            writeInt(out, "DataVersion", dataVersion);

            writeListHeader(out, "size", 3, 3);
            out.writeInt(states.size());
            out.writeInt(1);
            out.writeInt(1);

            writeListHeader(out, "palette", 10, states.size());
            for (String state : states) {
                ParsedState parsed = ParsedState.parse(state);
                writeString(out, "Name", parsed.name());
                if (!parsed.properties().isEmpty()) {
                    out.writeByte(10);
                    writeName(out, "Properties");
                    for (Map.Entry<String, String> property : parsed.properties().entrySet()) {
                        writeString(out, property.getKey(), property.getValue());
                    }
                    out.writeByte(0);
                }
                out.writeByte(0);
            }

            writeListHeader(out, "blocks", 10, states.size());
            for (int i = 0; i < states.size(); i++) {
                writeInt(out, "state", i);
                writeListHeader(out, "pos", 3, 3);
                out.writeInt(i);
                out.writeInt(0);
                out.writeInt(0);
                out.writeByte(0);
            }

            writeListHeader(out, "entities", 10, 0);
            out.writeByte(0); // ルートの終わり
        }
        return buffer.toByteArray();
    }

    private static void writeName(DataOutputStream out, String name) throws IOException {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void writeInt(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        writeName(out, name);
        out.writeInt(value);
    }

    private static void writeString(DataOutputStream out, String name, String value) throws IOException {
        out.writeByte(8);
        writeName(out, name);
        writeName(out, value);
    }

    private static void writeListHeader(DataOutputStream out, String name, int elementType, int length) throws IOException {
        out.writeByte(9);
        writeName(out, name);
        out.writeByte(elementType);
        out.writeInt(length);
    }

    /** {@code namespace:name[a=b,c=d]} を名前と性質に分ける。 */
    record ParsedState(String name, Map<String, String> properties) {
        static ParsedState parse(String state) {
            String trimmed = state.trim();
            int bracket = trimmed.indexOf('[');
            String name = bracket < 0 ? trimmed : trimmed.substring(0, bracket);
            if (!name.contains(":")) name = "minecraft:" + name;
            name = name.toLowerCase(Locale.ROOT);
            Map<String, String> properties = new LinkedHashMap<>();
            int close = trimmed.lastIndexOf(']');
            if (bracket >= 0 && close > bracket) {
                for (String pair : trimmed.substring(bracket + 1, close).split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) properties.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            return new ParsedState(name, properties);
        }

        String propertiesSuffix() {
            if (properties.isEmpty()) return "";
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (!first) builder.append(',');
                builder.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
            return builder.append(']').toString();
        }
    }
}
