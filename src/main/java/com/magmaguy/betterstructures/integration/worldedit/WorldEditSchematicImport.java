package com.magmaguy.betterstructures.integration.worldedit;

import com.magmaguy.betterstructures.integration.SchematicImport;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.schematic.reader.SchematicReader;
import com.magmaguy.betterstructures.integration.WorldEditCompatibility;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 独自の読み込み（{@code NativeSchematicImport}）で読めなかった図面（旧 MCEdit 形式の {@code .schematic} 等）を、
 * WorldEdit / FastAsyncWorldEdit で読んで {@link StructureSchematic} にする読み込み補助。
 *
 * <p><b>貼り付けには使わない。</b>貼り付けは WorldEdit/FAWE が入っていても常に独自エンジン（社長決定・2026-11-18）。
 * WorldEdit の Clipboard は、現在の Sponge 形式（v3）のバイト列に書き出して独自の読み込みに通すだけで、
 * この呼び出しの外へ出さない。
 *
 * <p>生成してよいのは WorldEdit または FastAsyncWorldEdit が有効な時だけ
 * （{@link WorldEditCompatibility#createFallbackImport()} が判定する）。
 * 呼び出すスレッドは {@link SchematicImport} のとおり（読み込み用ワーカー。リージョンスレッドでは呼ばない）。
 */
public final class WorldEditSchematicImport implements SchematicImport {

    @Override
    public boolean canRead(Path file) {
        if (file == null || !Files.isRegularFile(file)) return false;
        try {
            return ClipboardFormats.findByFile(file.toFile()) != null;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public StructureSchematic read(Path file, ConversionReport report) throws IOException {
        ClipboardFormat format = ClipboardFormats.findByFile(file.toFile());
        if (format == null) {
            throw new IOException("WorldEdit が形式を認識できません: " + file);
        }

        Clipboard clipboard;
        try (InputStream input = Files.newInputStream(file);
             ClipboardReader reader = format.getReader(input)) {
            clipboard = reader.read();
        } catch (IOException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            // WorldEdit の版のアダプターが原因なら、管理者に WorldEdit の更新を案内する
            WorldEditCompatibility.report(e, file.toFile());
            throw new IOException("WorldEdit が図面を読めませんでした: " + file + " (" + e + ")", e);
        }

        byte[] sponge;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(buffer)) {
                writer.write(clipboard);
            }
            sponge = buffer.toByteArray();
        } catch (IOException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            WorldEditCompatibility.report(e, file.toFile());
            throw new IOException("WorldEdit の図面を変換できませんでした: " + file + " (" + e + ")", e);
        }

        return SchematicReader.read(new ByteArrayInputStream(sponge), file.getFileName().toString(),
                report == null ? ConversionReport.NONE : report);
    }
}
