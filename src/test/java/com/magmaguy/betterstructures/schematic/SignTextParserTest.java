package com.magmaguy.betterstructures.schematic;

import com.magmaguy.betterstructures.schematic.nbt.SimpleNbt;
import com.magmaguy.betterstructures.schematic.sign.SignTextParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignTextParserTest {

    @Test
    void readsLinesTypedInGame() {
        SimpleNbt.CompoundTag nbt = sign(List.of(
                new SimpleNbt.StringTag("[custom]"),
                new SimpleNbt.StringTag("Zombie_Plus"),
                new SimpleNbt.StringTag(""),
                new SimpleNbt.StringTag("")
        ));
        SignTextParser.ParsedSign parsed = SignTextParser.parse(nbt);
        assertEquals("[custom]", parsed.getFrontLine(0));
        assertEquals("Zombie_Plus", parsed.getFrontLine(1));
        assertEquals("", parsed.getFrontLine(2));
    }

    @Test
    void readsComponentLinesWrittenByPlugins() {
        // Spigot/Paper SignSide#setLine output: components, blanks wrapped as {"": ""}
        SimpleNbt.CompoundTag line1 = new SimpleNbt.CompoundTag(Map.of(
                "text", new SimpleNbt.StringTag(""),
                "extra", new SimpleNbt.ListTag(SimpleNbt.TAG_STRING, List.of(new SimpleNbt.StringTag("[custom]")))
        ));
        SimpleNbt.CompoundTag line2 = new SimpleNbt.CompoundTag(Map.of(
                "text", new SimpleNbt.StringTag(""),
                "extra", new SimpleNbt.ListTag(SimpleNbt.TAG_STRING, List.of(new SimpleNbt.StringTag("Zombie_Plus")))
        ));
        SimpleNbt.CompoundTag wrappedBlank1 = new SimpleNbt.CompoundTag(Map.of("", new SimpleNbt.StringTag("")));
        SimpleNbt.CompoundTag wrappedBlank2 = new SimpleNbt.CompoundTag(Map.of("", new SimpleNbt.StringTag("")));

        SimpleNbt.CompoundTag nbt = sign(List.of(line1, line2, wrappedBlank1, wrappedBlank2));
        SignTextParser.ParsedSign parsed = SignTextParser.parse(nbt);

        assertEquals("[custom]", parsed.getFrontLine(0));
        assertEquals("Zombie_Plus", parsed.getFrontLine(1));
        assertEquals("", parsed.getFrontLine(2));
    }

    @Test
    void readsJsonLinesFromOlderSchematics() {
        SimpleNbt.CompoundTag nbt = sign(List.of(
                new SimpleNbt.StringTag("{\"text\":\"[spawn]\"}"),
                new SimpleNbt.StringTag("{\"text\":\"\",\"extra\":[\"ZOMBIE\"]}"),
                new SimpleNbt.StringTag("{\"extra\":[{\"text\":\"[elite\",\"color\":\"red\"},\"mobs]\"],\"text\":\"\"}"),
                new SimpleNbt.StringTag("\"\"")
        ));
        SignTextParser.ParsedSign parsed = SignTextParser.parse(nbt);

        assertEquals("[spawn]", parsed.getFrontLine(0));
        assertEquals("ZOMBIE", parsed.getFrontLine(1));
        assertEquals("[elitemobs]", parsed.getFrontLine(2));
        assertEquals("", parsed.getFrontLine(3));
    }

    @Test
    void legacyTextLinesKeepTheirReader() {
        SimpleNbt.CompoundTag nbt = new SimpleNbt.CompoundTag(Map.of(
                "Text1", new SimpleNbt.StringTag("{\"text\":\"[spawn]\"}"),
                "Text2", new SimpleNbt.StringTag("{\"text\":\"VINDICATOR\"}")
        ));
        SignTextParser.ParsedSign parsed = SignTextParser.parse(nbt);

        assertEquals("[spawn]", parsed.getFrontLine(0));
        assertEquals("VINDICATOR", parsed.getFrontLine(1));
    }

    private static SimpleNbt.CompoundTag sign(List<SimpleNbt.Tag> messagesList) {
        SimpleNbt.CompoundTag frontText = new SimpleNbt.CompoundTag(Map.of(
                "messages", new SimpleNbt.ListTag(SimpleNbt.TAG_COMPOUND, messagesList),
                "color", new SimpleNbt.StringTag("black")
        ));
        return new SimpleNbt.CompoundTag(Map.of(
                "id", new SimpleNbt.StringTag("minecraft:sign"),
                "front_text", frontText
        ));
    }
}
