package com.magmaguy.betterstructures.worldedit;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Temporary regression coverage for the schematic reload/cache ticket.
 * 中身を調べると失敗する偽の Clipboard は Mockito で作る（テストコード自身ではリフレクションを使わない）。
 * SchematicClipboardCache 自体は段C で削除される（その時にこのテストも削除する）。
 */
final class SchematicClipboardCacheTicketTest {
    @AfterEach
    void clearCaches() { SchematicClipboardCache.shutdown(); }

    @Test
    void unchangedFileHitsCacheAndChangedSameSizeFileMisses(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("airballoon_barren.schem");
        Files.write(source, new byte[] {1, 2, 3, 4});
        SchematicDiskCache.LoadedClipboard loaded = captured(source);
        SchematicClipboardCache cache = new SchematicClipboardCache();

        cache.put(source.toFile(), loaded);
        assertSame(loaded.clipboard(), cache.get(source.toFile()), "an unchanged schematic should be reused");
        verifyNoInteractions(loaded.clipboard());

        var modified = Files.getLastModifiedTime(source);
        Files.write(source, new byte[] {4, 3, 2, 1});
        Files.setLastModifiedTime(source, modified);
        assertNull(cache.get(source.toFile()),
                "a same-size rewrite must miss even when the filesystem timestamp is coarse");
    }

    @Test
    void deletedSchematicIsRemovedByRetainOnly(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("deleted.schem");
        Files.write(source, new byte[] {7});
        SchematicClipboardCache cache = new SchematicClipboardCache();
        cache.put(source.toFile(), captured(source));
        Files.delete(source);

        assertEquals(1, cache.retainOnly(List.of()), "removed files should be evicted");
        assertNull(cache.get(source.toFile()));
    }

    @Test
    void replacementAfterParsingCannotGiveOldClipboardTheNewIdentity(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("replaced.schem");
        Files.write(source, new byte[] {1, 2, 3});
        var loaded = captured(source);
        Files.write(source, new byte[] {4, 5, 6});
        var cache = new SchematicClipboardCache();
        cache.put(source.toFile(), loaded);
        assertNull(cache.get(source.toFile()));
    }

    private static SchematicDiskCache.LoadedClipboard captured(Path source) throws Exception {
        // キャッシュは Clipboard の中身を見ない。見たら verifyNoInteractions で失敗する。
        Clipboard clipboard = mock(Clipboard.class);
        return new SchematicDiskCache.LoadedClipboard(clipboard, new SchematicDiskCache.SourceIdentity(
                Files.size(source), Files.getLastModifiedTime(source).to(java.util.concurrent.TimeUnit.NANOSECONDS),
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(source)))));
    }
}
