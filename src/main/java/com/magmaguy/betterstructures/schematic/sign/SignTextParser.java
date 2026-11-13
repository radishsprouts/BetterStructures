package com.magmaguy.betterstructures.schematic.sign;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import org.bukkit.DyeColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Robust sign text parser that handles all historical and modern sign formats in Minecraft schematics:
 * 1. Legacy (MC <= 1.19): Text1, Text2, Text3, Text4 as JSON strings
 * 2. Modern plain text (MC >= 1.20): front_text.messages as plain StringTag list
 * 3. Modern JSON strings: front_text.messages containing JSON string components
 * 4. Modern Component CompoundTags: front_text.messages containing CompoundTags with text/extra
 *    (including Paper/Spigot empty line wrappers like {"": ""})
 * 5. Modern back_text (both sides of signs)
 * 6. Color, glowing text, and waxed properties
 */
public final class SignTextParser {

    private SignTextParser() {
    }

    public record ParsedSign(
            String[] frontLines,
            String[] backLines,
            DyeColor frontColor,
            DyeColor backColor,
            boolean frontGlowing,
            boolean backGlowing,
            boolean isWaxed
    ) {
        public String getFrontLine(int lineIndex) {
            if (lineIndex >= 0 && lineIndex < frontLines.length) {
                return frontLines[lineIndex] != null ? frontLines[lineIndex] : "";
            }
            return "";
        }

        public String getBackLine(int lineIndex) {
            if (lineIndex >= 0 && lineIndex < backLines.length) {
                return backLines[lineIndex] != null ? backLines[lineIndex] : "";
            }
            return "";
        }

        public boolean hasText() {
            for (String line : frontLines) {
                if (line != null && !line.isBlank()) return true;
            }
            for (String line : backLines) {
                if (line != null && !line.isBlank()) return true;
            }
            return false;
        }
    }

    /**
     * Parses a sign's NBT CompoundTag into a structured ParsedSign.
     * Guaranteed to never return null.
     */
    @NotNull
    public static ParsedSign parse(@Nullable SimpleNbt.CompoundTag nbt) {
        String[] frontLines = new String[]{"", "", "", ""};
        String[] backLines = new String[]{"", "", "", ""};
        DyeColor frontColor = DyeColor.BLACK;
        DyeColor backColor = DyeColor.BLACK;
        boolean frontGlowing = false;
        boolean backGlowing = false;
        boolean isWaxed = false;

        if (nbt == null) {
            return new ParsedSign(frontLines, backLines, frontColor, backColor, frontGlowing, backGlowing, isWaxed);
        }

        // Unwrap Sponge schematic "Data" tag if present
        SimpleNbt.CompoundTag dataTag = nbt.getCompound("Data");
        SimpleNbt.CompoundTag target = dataTag != null ? dataTag : nbt;

        isWaxed = target.getByte("is_waxed", (byte) 0) != 0;

        // Check for Modern 1.20+ front_text
        SimpleNbt.CompoundTag frontTextTag = target.getCompound("front_text");
        if (frontTextTag != null) {
            parseSignSide(frontTextTag, frontLines);
            frontColor = parseColor(frontTextTag.getString("color", "black"));
            frontGlowing = frontTextTag.getByte("has_glowing_text", (byte) 0) != 0;
        } else {
            // Check for Legacy Text1..Text4
            parseLegacyLines(target, frontLines);
        }

        // Check for Modern 1.20+ back_text
        SimpleNbt.CompoundTag backTextTag = target.getCompound("back_text");
        if (backTextTag != null) {
            parseSignSide(backTextTag, backLines);
            backColor = parseColor(backTextTag.getString("color", "black"));
            backGlowing = backTextTag.getByte("has_glowing_text", (byte) 0) != 0;
        }

        return new ParsedSign(frontLines, backLines, frontColor, backColor, frontGlowing, backGlowing, isWaxed);
    }

    private static void parseSignSide(SimpleNbt.CompoundTag sideTag, String[] outLines) {
        SimpleNbt.ListTag messages = sideTag.getList("messages");
        if (messages == null) return;

        for (int i = 0; i < Math.min(4, messages.size()); i++) {
            SimpleNbt.Tag lineTag = messages.get(i);
            outLines[i] = extractTextFromTag(lineTag);
        }
    }

    private static void parseLegacyLines(SimpleNbt.CompoundTag nbt, String[] outLines) {
        for (int i = 1; i <= 4; i++) {
            String raw = nbt.getString("Text" + i, "");
            if (!raw.isBlank()) {
                outLines[i - 1] = extractTextFromJsonString(raw);
            }
        }
    }

    /**
     * Extracts pure, unformatted plain text from an arbitrary Tag element inside messages list.
     */
    public static String extractTextFromTag(@Nullable SimpleNbt.Tag tag) {
        if (tag == null) return "";

        if (tag instanceof SimpleNbt.StringTag stringTag) {
            return extractTextFromJsonString(stringTag.asString());
        }

        if (tag instanceof SimpleNbt.CompoundTag compoundTag) {
            return extractTextFromCompound(compoundTag);
        }

        if (tag instanceof SimpleNbt.ListTag listTag) {
            StringBuilder sb = new StringBuilder();
            for (SimpleNbt.Tag item : listTag) {
                sb.append(extractTextFromTag(item));
            }
            return sb.toString();
        }

        return tag.getValue() != null ? tag.getValue().toString() : "";
    }

    /**
     * Recursively extracts plain text from a CompoundTag component (e.g. {text: "", extra: [...]}).
     */
    private static String extractTextFromCompound(SimpleNbt.CompoundTag compound) {
        // Special case: Paper/Spigot empty line wrap {"": ""} or {"": element}
        if (compound.containsKey("")) {
            return extractTextFromTag(compound.get(""));
        }

        StringBuilder sb = new StringBuilder();
        String text = compound.getString("text", "");
        if (!text.isEmpty()) {
            sb.append(text);
        }

        SimpleNbt.Tag extraTag = compound.get("extra");
        if (extraTag != null) {
            sb.append(extractTextFromTag(extraTag));
        }

        return sb.toString();
    }

    /**
     * Extracts plain text from a string that may be JSON (e.g. {"text":"foo"}) or plain text.
     */
    public static String extractTextFromJsonString(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return "";

        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("\"")) {
            return trimmed;
        }

        // If it is surrounded by quotes, remove outer quotes or parse as JSON
        try {
            JsonElement element = JsonParser.parseString(trimmed);
            return extractTextFromJsonElement(element);
        } catch (Exception notJson) {
            // Not valid JSON, return stripped string
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
            return trimmed;
        }
    }

    private static String extractTextFromJsonElement(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";

        if (element.isJsonPrimitive()) {
            String str = element.getAsString();
            if (str.startsWith("\"") && str.endsWith("\"") && str.length() >= 2) {
                return str.substring(1, str.length() - 1);
            }
            return str;
        }

        if (element.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                sb.append(extractTextFromJsonElement(item));
            }
            return sb.toString();
        }

        if (element.isJsonObject()) {
            StringBuilder sb = new StringBuilder();
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("text")) {
                sb.append(extractTextFromJsonElement(obj.get("text")));
            }
            if (obj.has("extra")) {
                sb.append(extractTextFromJsonElement(obj.get("extra")));
            }
            return sb.toString();
        }

        return "";
    }

    private static DyeColor parseColor(String colorName) {
        if (colorName == null || colorName.isBlank()) return DyeColor.BLACK;
        try {
            return DyeColor.valueOf(colorName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DyeColor.BLACK;
        }
    }
}
