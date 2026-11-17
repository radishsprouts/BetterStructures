package com.magmaguy.betterstructures.paste;

import com.magmaguy.betterstructures.chests.ChestContents;
import com.magmaguy.betterstructures.schematic.model.StructureSchematic;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 貼り付け1件の依頼。{@link PasteEngine#start(PasteRequest)} に渡す。作ったら変更しない。
 *
 * <p><b>作るのは</b>: U1（地形走査の決定段）と U3（ダンジョンのモジュール）。
 * <b>使うのは</b>: U2（PasteEngine の実装）。
 *
 * <p>図面({@link #schematic()})は<b>回転済み</b>のものを渡す（{@code StructureSchematic#rotate} の結果）。
 * 回転角は停止をまたぐ再開の記録（PasteJournal）のために {@link #rotationDegrees()} にも入れる。
 *
 * @param world                   貼り付け先
 * @param baseLocation            図面の原点（anchor）
 * @param schematicOffset         図面のオフセット（null なら図面自身のオフセットを使う側が補う）
 * @param schematic               回転済みの図面
 * @param schematicName           図面の名前（PasteJournal 用。再開時に図面を引き直す鍵）
 * @param rotationDegrees         回転角（0・90・180・270）
 * @param markerHandler           看板の目印の扱い
 * @param chestContents           宝箱の中身（null 可）
 * @param barrelContents          樽の中身（null 可）
 * @param pedestalMaterialProvider 台座材（引数は「下側か」。null なら台座を作らない）。
 *                                 台座材の標本は呼び出し側が走査の段でスナップショットから取って渡す
 * @param bedrockReplacement      岩盤の置き換え先（建造物は台座材、モジュールは STONE。null なら置き換えない）
 * @param fillPedestal            各チャンクのブロックの段の後に台座列を充填するか
 * @param clearTrees              地表の木を除去するか
 * @param heldChunks              走査の段で取った hold。渡すとエンジンが所有を引き継ぐ
 *                                ({@link PluginChunkHolds#transfer})。null なら自分で取る
 * @param windowChunks            0 なら範囲全体を hold する。1 以上なら今処理しているチャンクと
 *                                その先 K 個だけを hold する窓付き（WFC 用。05章）
 * @param announce                完了時に {@code betterstructures.warn} 権限者へ通知するか
 * @param onFinished              完了・取り消し・失敗の通知（null 可）。完了時は貼り付け先の
 *                                リージョンのスレッドで呼ぶ。{@code BuildPasteCompleteEvent} は
 *                                建造物の呼び出し側（U1）がここで出す
 */
public record PasteRequest(
        World world,
        Location baseLocation,
        Vector schematicOffset,
        StructureSchematic schematic,
        String schematicName,
        int rotationDegrees,
        MarkerHandler markerHandler,
        ChestContents chestContents,
        ChestContents barrelContents,
        Function<Boolean, Material> pedestalMaterialProvider,
        Material bedrockReplacement,
        boolean fillPedestal,
        boolean clearTrees,
        PluginChunkHolds.HoldSet heldChunks,
        int windowChunks,
        boolean announce,
        Consumer<PasteResult> onFinished
) {

    public PasteRequest {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(baseLocation, "baseLocation");
        Objects.requireNonNull(schematic, "schematic");
        Objects.requireNonNull(markerHandler, "markerHandler");
        if (schematicName == null) schematicName = "";
        if (windowChunks < 0) windowChunks = 0;
    }

    /** 必須項目だけを渡して、残りを既定値にした依頼を作る。 */
    public static Builder builder(World world, Location baseLocation, StructureSchematic schematic, MarkerHandler markerHandler) {
        return new Builder(world, baseLocation, schematic, markerHandler);
    }

    /** {@link PasteRequest} の組み立て役。 */
    public static final class Builder {
        private final World world;
        private final Location baseLocation;
        private final StructureSchematic schematic;
        private final MarkerHandler markerHandler;
        private Vector schematicOffset;
        private String schematicName = "";
        private int rotationDegrees;
        private ChestContents chestContents;
        private ChestContents barrelContents;
        private Function<Boolean, Material> pedestalMaterialProvider;
        private Material bedrockReplacement;
        private boolean fillPedestal;
        private boolean clearTrees;
        private PluginChunkHolds.HoldSet heldChunks;
        private int windowChunks;
        private boolean announce;
        private Consumer<PasteResult> onFinished;

        private Builder(World world, Location baseLocation, StructureSchematic schematic, MarkerHandler markerHandler) {
            this.world = world;
            this.baseLocation = baseLocation;
            this.schematic = schematic;
            this.markerHandler = markerHandler;
        }

        public Builder schematicOffset(Vector value) {
            this.schematicOffset = value;
            return this;
        }

        public Builder schematicName(String value) {
            this.schematicName = value;
            return this;
        }

        public Builder rotationDegrees(int value) {
            this.rotationDegrees = value;
            return this;
        }

        public Builder contents(ChestContents chest, ChestContents barrel) {
            this.chestContents = chest;
            this.barrelContents = barrel;
            return this;
        }

        public Builder pedestalMaterialProvider(Function<Boolean, Material> value) {
            this.pedestalMaterialProvider = value;
            return this;
        }

        public Builder bedrockReplacement(Material value) {
            this.bedrockReplacement = value;
            return this;
        }

        public Builder fillPedestal(boolean value) {
            this.fillPedestal = value;
            return this;
        }

        public Builder clearTrees(boolean value) {
            this.clearTrees = value;
            return this;
        }

        public Builder heldChunks(PluginChunkHolds.HoldSet value) {
            this.heldChunks = value;
            return this;
        }

        public Builder windowChunks(int value) {
            this.windowChunks = value;
            return this;
        }

        public Builder announce(boolean value) {
            this.announce = value;
            return this;
        }

        public Builder onFinished(Consumer<PasteResult> value) {
            this.onFinished = value;
            return this;
        }

        public PasteRequest build() {
            return new PasteRequest(world, baseLocation, schematicOffset, schematic, schematicName, rotationDegrees,
                    markerHandler, chestContents, barrelContents, pedestalMaterialProvider, bedrockReplacement,
                    fillPedestal, clearTrees, heldChunks, windowChunks, announce, onFinished);
        }
    }
}
