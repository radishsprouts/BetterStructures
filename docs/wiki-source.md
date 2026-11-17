# BetterStructures wiki 用資料

- プラグイン名: BetterStructures 2.7.12（Folia 向け改造版。本番の jar は `BetterStructures.jar`）
- 最終更新日: 2026-11-17
- 資料が合っているコミット: db5ab14
- この資料を使っている wiki のページ: kadosaba-site の wiki/structures.md

> wiki(角鯖の公式サイトのプレイヤー向け Wiki)を書く時に、ソースを全部読み直さずに済ませるための資料。README・仕様書ではない。プレイヤーから見える事実だけを書く。本番の値は 2026-11-17 に `res_world/plugins/BetterStructures/` と `res_world/logs/latest.log` で確かめた。

## どんな機能か

バニラには無い建造物（気球・鉱山・墓地・神殿・浮島など）を、ワールドに自動で置く。**資源サーバーだけに入っている。一般サーバーには入っていない**（2026-11-17・本番の plugins の jar で確認）。WorldEdit は入っておらず、Folia 向けの独自の生成で置いている（起動ログ「WorldEdit not detected. Running Folia native structure generation engine.」）。

## プレイヤーの使い方

見つける機能で、操作は無い。探検して見つける。

## 数値

### 出るワールド（`ValidWorlds.yml`）

`New worlds spawn structures: true`、`world: true`、`world_nether: true`、`world_the_end: true`（資源ワールド・ネザー・エンドのすべて）。

### 出る間隔・場所（`config.yml`）

| 項目 | キー | 本番の値 |
|---|---|---|
| 地上の間隔 | `distanceSurface` | 27 |
| 浅い地下の間隔 | `distanceShallow` | 22 |
| 深い地下の間隔 | `distanceDeep` | 22 |
| 空の間隔 | `distanceSky` | 90 |
| 海面の間隔 | `distanceLiquid` | 60 |
| ダンジョンの間隔 | `distanceDungeonV2` | 80 |
| スポーン周辺の禁止 | `spawnProtectionRadius` | 100（ブロック） |
| 置く高さ | `lowestYNormalCustom` `highestYNormalCustom` | -60〜320（ネザーは 4〜120、エンドは 0〜320）。空は 80〜120 |

間隔の単位は設定ファイルに書かれていない（確認待ち）。

`generators/` の生成設定は、地上（草原・砂漠・雪原・荒野・エンド・ネザーなどのバイオーム別）、浅い地下・深い地下・鍾乳洞・繁茂洞窟、空（バイオーム別・ネザー・エンド）、海面（オーバーワールド・ネザー）。各設定に宝箱の中身の表（`treasures/` の `treasure_overworld_surface.yml` など）が指定されている。

### 使われる建造物

`content_packages/` は 12 個が `isEnabled: true` だが、本番の `schematics/` に実体があるのは 4 つ。

| 実体のあるパッケージ | 主な内容 | 使われるか |
|---|---|---|
| 103_default_structures | 気球・船・いかだ・貯水槽・畑・ダンジョン・墓地・浮島・大きな浮島・鉱山（連絡路・鉱石の山・貯蔵庫・捨て場・地表の掘り穴）・墓・井戸・道標 | 使われる |
| exploration | 橋のかかった洞窟・神殿・塔・水上の家・小さな水の神殿・浮遊要塞・地下神殿・井戸・旗艦 | 使われる |
| caves_and_lost_civilizations_free | 円形のダンジョン・水晶の洞窟・失われた湖・鉱山・牢獄・遺跡・小さなダンジョン・縦穴の洞窟・旗艦 | 使われる |
| BetterStructures Free Elite Shrines（`em_*`） | ボスのいる神殿・塔・ダンジョン・玉座の間など | **使われない**（起動ログに「requires an unavailable mob integration; this schematic will not be used.」。EliteMobs が入っていないため） |

実体の無いパッケージ（プレミアム系など）は、生成されない。ダンジョンのモジュール生成は、モジュールが無く動かない（ログ「No start modules exist, you need to install or make modules first!」）。

## プレイヤーに出るメッセージ（原文のまま）

通常の遊びでは出ない。

## 権限と、使えるサーバー

| 項目 | 一般 | 資源 |
|---|---|---|
| 建造物の自動生成 | 使えない（入っていない） | 資源ワールド・ネザー・エンドで有効 |

## 管理者専用の機能

- `/betterstructures`（`/bs`）系: 設定画面（SetupMenu）・コンテンツパッケージのダウンロードと有効化・事前生成（`percentageOfTickUsedForPregeneration: 0.1`）・建造物の手動生成
- アカウント連携（`/nightbreaklogin` 等）。トークンは本番では未設定

## wiki に書いてはいけないこと

- 管理者専用の機能（上の節）、コンテンツパッケージの名前や入手先
- 建造物の名前のファイル名（`betterstructures_*`）
- プラグイン名

## 確認待ちの点

- 出る間隔の単位（チャンクかブロックか）
- 建造物の中の宝箱の中身（建造物ごとにどの表が使われるか）と、敵が出るか
- すでに歩いた場所（既存のチャンク）にも後から出るか
- 建造物の中での破壊を制限する保護があるか（EliteMobs 用の保護の設定はあるが、EliteMobs が入っていない）
- 建造物の呼び名は、ファイル名からの意訳（ゲーム内で見た目を確かめていない）

## 根拠

| 事実 | 場所 |
|---|---|
| 出るワールド | `M:\minecraft\server\kado_server\res_world\plugins\BetterStructures\ValidWorlds.yml` |
| 間隔・高さ・スポーン周辺の禁止 | `M:\minecraft\server\kado_server\res_world\plugins\BetterStructures\config.yml` |
| 使われない建造物 | `M:\minecraft\server\kado_server\res_world\logs\latest.log`（2026-11-17 の起動時、`em_*` の警告） |
