package com.magmaguy.betterstructures.integration;

import com.magmaguy.betterstructures.schematic.model.StructureSchematic;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 図面ファイルを {@link StructureSchematic} に読む。WorldEdit の型はここに現れない。
 *
 * <p><b>呼び出すスレッド</b>: 起動・{@code /bs reload} の読み込み用の WorkerPool スレッド。
 * ワールドには触れない。リージョンスレッドとグローバルリージョンでは呼ばない。
 *
 * <p><b>実装するのは</b>: U4。
 * <ul>
 *   <li>{@code NativeSchematicImport}: 今の SchematicReader（旧名ブロックの版の変換を含む）</li>
 *   <li>{@code integration/worldedit/WorldEditSchematicImport}: ネイティブで読めない図面
 *       （旧 MCEdit 形式等）だけを読む補助。WorldEdit/FAWE が入っている時だけ生成される。
 *       貼り付けには使わない</li>
 * </ul>
 */
public interface SchematicImport {

    /** 読み込みで解釈できず空気にしたブロックの報告先。読み込みの最後に1回だけ警告するために数える。 */
    interface ConversionReport {
        /** 何もしない報告先。 */
        ConversionReport NONE = (schematicName, blockName) -> { };

        void unresolvedBlock(String schematicName, String blockName);
    }

    /** この実装で読める形式か（拡張子・先頭の判定。ファイルを全部読まない）。 */
    boolean canRead(Path file);

    /**
     * 図面を読む。
     *
     * @throws IOException 読めない・壊れている
     */
    StructureSchematic read(Path file, ConversionReport report) throws IOException;
}
