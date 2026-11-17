package com.magmaguy.betterstructures.integration;

import com.magmaguy.betterstructures.integration.worldedit.WorldEditSchematicImport;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.integration.WorldEditCompatibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldEdit/FAWE がある構成（既存の test の sourceSet）で、ネイティブで読めない図面だけ WorldEdit 実装が使われること。
 * 貼り付けは WorldEdit があっても独自エンジンなので、WorldEdit 実装は「読む」以外の入口を持たない
 * （{@link SchematicImport} は読み込みの契約しか持たない）。
 */
class SchematicImportSelectionTest {

    @TempDir
    Path directory;

    @BeforeEach
    void startServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void stopServer() {
        MockBukkit.unmock();
    }

    @Test
    void nativeResultIsUsedAndTheFallbackIsNotTouchedWhenTheNativeReadWorks() throws IOException {
        Path file = directory.resolve("fine.schem");
        Files.write(file, new byte[] {1});
        StructureSchematic expected = tiny();
        FakeImport nativeLike = new FakeImport(true, expected, null);
        FakeImport fallback = new FakeImport(true, tiny(), null);

        SchematicImport chain = chain(nativeLike, fallback);

        assertSame(expected, chain.read(file, SchematicImport.ConversionReport.NONE));
        assertEquals(1, nativeLike.reads.size());
        assertEquals(0, fallback.reads.size(), "ネイティブで読めた図面で WorldEdit 実装は使わない");
    }

    @Test
    void onlyWhatTheNativeReaderCannotReadGoesToTheFallback() throws IOException {
        Path file = directory.resolve("legacy.schem");
        Files.write(file, new byte[] {1});
        StructureSchematic fromFallback = tiny();
        FakeImport nativeLike = new FakeImport(true, null, new IOException("Missing Palette"));
        FakeImport fallback = new FakeImport(true, fromFallback, null);

        SchematicImport chain = chain(nativeLike, fallback);

        assertSame(fromFallback, chain.read(file, SchematicImport.ConversionReport.NONE));
        assertEquals(1, nativeLike.reads.size());
        assertEquals(1, fallback.reads.size());
    }

    @Test
    void failureIsReportedWhenTheFallbackCannotReadItEither() throws IOException {
        Path file = directory.resolve("bad.schem");
        Files.write(file, new byte[] {1});
        FakeImport nativeLike = new FakeImport(true, null, new IOException("native broke"));
        FakeImport fallback = new FakeImport(true, null, new IOException("worldedit broke"));

        IOException failure = assertThrows(IOException.class,
                () -> chain(nativeLike, fallback).read(file, SchematicImport.ConversionReport.NONE));

        assertEquals("native broke", failure.getMessage());
        assertEquals("worldedit broke", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void theNativeFailureIsKeptWhenTheFallbackDoesNotRecognizeTheFile() throws IOException {
        Path file = directory.resolve("garbage.schem");
        Files.write(file, new byte[] {1});
        FakeImport nativeLike = new FakeImport(true, null, new IOException("native broke"));
        FakeImport fallback = new FakeImport(false, tiny(), null);

        assertThrows(IOException.class, () -> chain(nativeLike, fallback).read(file, SchematicImport.ConversionReport.NONE));
        assertEquals(0, fallback.reads.size(), "WorldEdit 実装が読めないと言った図面は渡さない");
    }

    @Test
    void withoutAFallbackOnlyTheNativeReaderIsUsed() {
        SchematicImport chain = NativeSchematicImport.withFallback(null);
        assertInstanceOf(NativeSchematicImport.class, chain);
    }

    @Test
    void worldEditImplementationIsOnlyCreatedWhenWorldEditOrFaweIsEnabled() {
        assertNull(WorldEditCompatibility.createFallbackImport(), "WorldEdit も FAWE も無ければ生成しない");
        assertFalse(WorldEditCompatibility.isWorldEditPresent());

        MockBukkit.createMockPlugin("WorldEdit");
        assertInstanceOf(WorldEditSchematicImport.class, WorldEditCompatibility.createFallbackImport());
    }

    @Test
    void fastAsyncWorldEditAloneAlsoEnablesTheFallback() {
        MockBukkit.createMockPlugin("FastAsyncWorldEdit");
        assertInstanceOf(WorldEditSchematicImport.class, WorldEditCompatibility.createFallbackImport());
    }

    @Test
    void worldEditImplementationRecognizesLegacyFormatThatTheNativeReaderDoesNot() throws IOException {
        Path legacy = directory.resolve("castle.schematic");
        Files.write(legacy, mcEditSchematic());
        Path sponge = directory.resolve("modern.schem");
        Files.write(sponge, new byte[] {1, 2, 3});

        WorldEditSchematicImport worldEdit = new WorldEditSchematicImport();
        NativeSchematicImport nativeImport = new NativeSchematicImport();

        assertTrue(worldEdit.canRead(legacy), "旧 MCEdit 形式は WorldEdit が認識する");
        assertFalse(nativeImport.canRead(legacy), "旧 MCEdit 形式をネイティブは読み込み対象にしない");
        assertTrue(nativeImport.canRead(sponge));
        assertFalse(worldEdit.canRead(directory.resolve("missing.schematic")));
    }

    // ---- 部品 ----

    private static SchematicImport chain(SchematicImport nativeLike, SchematicImport fallback) {
        return NativeSchematicImport.chain(nativeLike, fallback);
    }

    private static StructureSchematic tiny() {
        return new StructureSchematic((short) 1, (short) 1, (short) 1, 0, 0, 0, null, null, null, null);
    }

    /** 旧 MCEdit 形式（Materials=Alpha の gzip NBT）の最小の図面。 */
    private static byte[] mcEditSchematic() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(buffer))) {
            out.writeByte(10);
            writeName(out, "Schematic");
            out.writeByte(2);
            writeName(out, "Width");
            out.writeShort(1);
            out.writeByte(2);
            writeName(out, "Height");
            out.writeShort(1);
            out.writeByte(2);
            writeName(out, "Length");
            out.writeShort(1);
            out.writeByte(8);
            writeName(out, "Materials");
            writeName(out, "Alpha");
            out.writeByte(7);
            writeName(out, "Blocks");
            out.writeInt(1);
            out.writeByte(1);
            out.writeByte(7);
            writeName(out, "Data");
            out.writeInt(1);
            out.writeByte(0);
            out.writeByte(9);
            writeName(out, "Entities");
            out.writeByte(10);
            out.writeInt(0);
            out.writeByte(9);
            writeName(out, "TileEntities");
            out.writeByte(10);
            out.writeInt(0);
            out.writeByte(0);
        }
        return buffer.toByteArray();
    }

    private static void writeName(DataOutputStream out, String name) throws IOException {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static final class FakeImport implements SchematicImport {
        final boolean readable;
        final StructureSchematic result;
        final IOException failure;
        final List<Path> reads = new ArrayList<>();

        FakeImport(boolean readable, StructureSchematic result, IOException failure) {
            this.readable = readable;
            this.result = result;
            this.failure = failure;
        }

        @Override
        public boolean canRead(Path file) {
            return readable;
        }

        @Override
        public StructureSchematic read(Path file, ConversionReport report) throws IOException {
            reads.add(file);
            if (failure != null) throw failure;
            assertNotNull(result);
            return result;
        }
    }
}
