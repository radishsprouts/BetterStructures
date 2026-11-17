package com.magmaguy.betterstructures.integration;

import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematic.reader.SchematicReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 独自の NBT 読み込み（Sponge 形式 v1〜v3）で図面を読む実装。WorldEdit・NMS に依存しない。
 * 古い DataVersion の図面の旧名ブロックは {@code PaletteUpgrader} で変換し、
 * 変換できなかったものは {@link ConversionReport} へ報告して空気にする。
 *
 * <p>呼び出すスレッドは {@link SchematicImport} のとおり（起動・再読み込み用のワーカー）。
 */
public final class NativeSchematicImport implements SchematicImport {

    @Override
    public boolean canRead(Path file) {
        return file != null && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".schem");
    }

    @Override
    public StructureSchematic read(Path file, ConversionReport report) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("図面ファイルが見つかりません: " + file);
        return SchematicReader.read(file.toFile(), report == null ? ConversionReport.NONE : report);
    }

    /**
     * ネイティブで読めなかった図面だけを {@code fallback}（WorldEdit 実装）に任せる実装を返す。
     * 貼り付けは常に独自エンジンで、{@code fallback} は読み込みの補助にしか使わない。
     * {@code fallback} が null ならネイティブだけ。
     */
    public static SchematicImport withFallback(SchematicImport fallback) {
        return chain(new NativeSchematicImport(), fallback);
    }

    /**
     * {@code primary} で読み、読めなかった図面だけ {@code fallback} に任せる。
     * {@code fallback} が null なら {@code primary} をそのまま返す。
     */
    public static SchematicImport chain(SchematicImport primary, SchematicImport fallback) {
        SchematicImport nativeImport = primary;
        if (fallback == null) return nativeImport;
        return new SchematicImport() {
            @Override
            public boolean canRead(Path file) {
                return nativeImport.canRead(file) || fallback.canRead(file);
            }

            @Override
            public StructureSchematic read(Path file, ConversionReport report) throws IOException {
                IOException nativeFailure;
                try {
                    return nativeImport.read(file, report);
                } catch (IOException | RuntimeException failure) {
                    if (!fallback.canRead(file)) throw failure instanceof IOException io ? io : new IOException(failure);
                    nativeFailure = failure instanceof IOException io ? io : new IOException(failure);
                }
                try {
                    return fallback.read(file, report);
                } catch (IOException | RuntimeException | LinkageError fallbackFailure) {
                    nativeFailure.addSuppressed(fallbackFailure);
                    throw nativeFailure;
                }
            }
        };
    }
}
