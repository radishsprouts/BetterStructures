package com.magmaguy.betterstructures.paste;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.magmaguy.magmacore.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 停止をまたいで貼り付けを続けるための記録（04章 G6）。
 *
 * <p>貼り付けごとに、図面の名前・anchor・回転・各種の旗・チャンクごとの進み具合（段と、その段の何番目まで）を持つ。
 * 走査の待ち行列（ワールド・cx・cz・種類）も同じファイルに載せる。
 * 書くのは 30 秒ごとに WorkerPool で（一時ファイルへ書いてから置き換える）。停止時（onDisable）だけ同期で最後の1回を書く。
 * 読むのは起動時で、非同期スレッドから。ファイルが壊れていたら {@code .corrupt} に退避して空として扱う（捨てない）。
 */
public final class PasteJournal {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** チャンク1つの進み具合。{@code stage} は {@link PasteStage} の名前、{@code cursor} はその段の何番目まで終えたか。 */
    public static final class ChunkProgress {
        public String stage = PasteStage.BLOCKS.name();
        public int cursor;

        public ChunkProgress() {
        }

        public ChunkProgress(PasteStage stage, int cursor) {
            this.stage = stage.name();
            this.cursor = cursor;
        }

        public PasteStage stage() {
            try {
                return PasteStage.valueOf(stage);
            } catch (IllegalArgumentException | NullPointerException e) {
                return PasteStage.BLOCKS;
            }
        }
    }

    /** 貼り付け1件の記録。 */
    public static final class Entry {
        public String sessionId;
        public String worldId;
        public String schematicName;
        public int rotationDegrees;
        public int baseX;
        public int baseY;
        public int baseZ;
        public int offsetX;
        public int offsetY;
        public int offsetZ;
        public boolean fillPedestal;
        public boolean clearTrees;
        public boolean announce;
        public int windowChunks;
        /** 岩盤の置き換え先の材質名（無ければ null）。 */
        public String bedrockReplacement;
        /** チャンクの鍵（{@code Chunk#getChunkKey} 式）を文字列にしたもの → 進み具合。 */
        public Map<String, ChunkProgress> chunks = new LinkedHashMap<>();
    }

    /** 走査の待ち行列の1件。 */
    public static final class ScanEntry {
        public String worldId;
        public int chunkX;
        public int chunkZ;
        public String kind;

        public ScanEntry() {
        }

        public ScanEntry(String worldId, int chunkX, int chunkZ, String kind) {
            this.worldId = worldId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.kind = kind;
        }
    }

    /** ファイルの中身全体。 */
    public static final class Data {
        public int version = 1;
        public List<Entry> pastes = new ArrayList<>();
        public List<ScanEntry> scans = new ArrayList<>();
    }

    private final Path file;
    private final Object ioLock = new Object();

    public PasteJournal(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** 書く。一時ファイルへ書いてから置き換えるので、書きかけを読むことはない。 */
    public void write(Data data) throws IOException {
        String json = GSON.toJson(data);
        synchronized (ioLock) {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 読む。ファイルが無ければ空。壊れていたら退避して空。 */
    public Data read() {
        synchronized (ioLock) {
            if (!Files.exists(file)) return new Data();
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Data data = GSON.fromJson(json, Data.class);
                if (data == null) return new Data();
                if (data.pastes == null) data.pastes = new ArrayList<>();
                if (data.scans == null) data.scans = new ArrayList<>();
                return data;
            } catch (IOException | JsonParseException e) {
                Logger.warn("貼り付けの記録 " + file + " を読めませんでした。.corrupt に退避して空から始めます: " + e.getMessage());
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
                return new Data();
            }
        }
    }
}
