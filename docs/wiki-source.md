# BetterStructures wiki 用資料

- プラグイン名: BetterStructures 2.7.12（Folia 向け改造版。本番の jar は `BetterStructures.jar`）
- 最終更新日: 2026-11-18
- 資料が合っているコミット: f0d47e9
- この資料を使っている wiki のページ: kadosaba-site の wiki/structures.md

> wiki(角鯖の公式サイトのプレイヤー向け Wiki)を書く時に、ソースを全部読み直さずに済ませるための資料。README・仕様書ではない。プレイヤーから見える事実だけを書く。本番の値は 2026-11-17 に `res_world/plugins/BetterStructures/` と `res_world/logs/latest.log` で確かめた（それ以降に入れた流量の設定項目と挙動の変更は、ソースで確かめたもので、本番では未確認）。

## どんな機能か

バニラには無い建造物（気球・鉱山・墓地・神殿・浮島など）を、ワールドに自動で置く。**資源サーバーだけに入っている。一般サーバーには入っていない**（2026-11-17・本番の plugins の jar で確認）。

WorldEdit・FastAsyncWorldEdit が無くても全機能が動く。入っていても、建造物を置く処理は常にプラグイン自身の仕組みで行い、WorldEdit は独自の仕組みで読めない図面を読む時の補助にだけ使う。本番の資源サーバーには WorldEdit は入っておらず、Folia 向けの独自の生成で置いている（起動ログ「WorldEdit not detected. Running Folia native structure generation engine.」）。

生成が追いつかない時も、順番待ちの建造物を捨てずに順に処理するので、遅れても出る。置く処理に使う時間には上限があり、サーバーの遅れを検知したら自動で絞る。サーバーを止めた時に置く途中だった建造物を、次の起動で続きから置く仕組みもある（確認待ちの点を参照）。

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

### 置く処理の流量（`config.yml`。ソースの既定値。本番の値は未確認）

管理者向けの設定。建造物は順番待ちに入り、上限を超えた分は捨てられず順に処理される。

| 項目 | キー | 既定値 |
|---|---|---|
| 1 tick のうち置く処理に使ってよい割合の上限 | `percentageOfTickUsedForPasting` | 0.2（0.01〜1）。本番は 0.2（1 tick の 20%＝10ms） |
| 同時に地形を調べる数 | `generationScanSlots` | 4（1〜64） |
| 同時に置く建造物の数 | `generationPasteSlots` | 2（1〜64） |
| 同時に生成するダンジョンの数 | `generationDungeonSlots` | 1（1〜16） |
| チャンク読み込みの同時数（サーバー全体） | `chunkLoadLimit` | 8（1〜256） |
| チャンク読み込みの同時数（ワールドごと） | `chunkLoadLimitPerWorld` | 8（1〜256） |
| 遅れた時の置く時間の下限 | `pasteBudgetMinMilliseconds` | 0.5 ミリ秒（0.05〜50） |
| 遅れが無くなった時に 1 tick ごとに戻す量 | `pasteBudgetRecoverMilliseconds` | 0.25 ミリ秒（0.01〜50） |
| 遅れた時に置く時間へ掛ける係数 | `pasteBudgetBackoffFactor` | 0.5（0.01〜0.99） |
| 「遅れ」とみなす予定からのずれ | `pasteLagThresholdMilliseconds` | 100 ミリ秒（1〜5000） |
| ダンジョンを置く時に、置いているチャンクの先で読み込んだままにする数 | `dungeonPasteWindowChunks` | 4（1〜64） |
| 流量の記録をコンソールに出す | `runtimeMetricsLogging` | false |
| その記録の間隔 | `runtimeMetricsIntervalSeconds` | 60 秒（5〜3600） |

範囲外の値は既定値になる。

### 事前生成（`config.yml`）

| 項目 | キー | 既定値 |
|---|---|---|
| 事前生成に使ってよい 1 tick の割合 | `percentageOfTickUsedForPregeneration` | 0.1（0.01〜1）。本番は 0.1 |
| TPS がこれを下回ると事前生成を一時停止 | `pregenerationTPSPauseThreshold` | 12.0 |
| TPS がこれ以上に戻ると再開 | `pregenerationTPSResumeThreshold` | 14.0 |

事前生成のチャンク読み込みは、上の `chunkLoadLimit` の枠を他の処理と分け合う。tick の遅れを検知した時と、順番待ちが多い時も一時停止する。TPS による停止は Folia では使われない（ソースのコメント）。

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

通常の遊びでは出ない。管理者に出るもの:

| 場面 | メッセージ |
|---|---|
| `/bs generateModules` で `isWorldGeneration: true`（Folia） | `Folia では、新しいワールドを作って生成する設定（isWorldGeneration: true）のダンジョン生成は今は使えません。この設定（<ファイル名>）では生成しませんでした。isWorldGeneration: false の設定は、実行した人の足元にそのまま生成されます。` |
| `/bs generateModules` で存在しないファイル | `File <ファイル名> not found! The world won't generate.` |
| `/bs reload` 成功 | `Reloaded BetterStructures content.` |
| `/bs reload` が既に走っている時 | `A BetterStructures content reload is already running. Your reload was queued.` |
| 起動の途中で `/bs reload` | `BetterStructures is still initializing. Try reloading when it finishes.` |
| 読み直し中に他のコマンド | `BetterStructures content is reloading. Try that command again when the reload finishes.` |
| `/bs reload` 失敗 | `Failed to reload BetterStructures content. Check the console.`（プラグインは無効になる） |
| `/bs pregenerate` 開始 | `Starting chunk pregeneration with shape: <形>, center: <中心>, radius: <半径> bloc...`（続けて `Progress will be reported in the console every 30 seconds.` と `Use /betterstructures cancelPregenerate to cancel if needed.`） |
| `/bs cancelPregenerate` | `Cancelled <数> pregeneration process(es) in world: <ワールド名>` |

## 権限と、使えるサーバー

| 項目 | 一般 | 資源 |
|---|---|---|
| 建造物の自動生成 | 使えない（入っていない） | 資源ワールド・ネザー・エンドで有効 |

| 権限 | できること |
|---|---|
| `betterstructures.*` | `/bs` の管理操作すべて（reload・pregenerate・cancelPregenerate・place 等） |
| `betterstructures.initialize` | 初回設定メニューの操作 |
| `betterstructures.setup` | `/bs setup` |
| `betterstructures.generatemodules` | `/bs generateModules` |
| `betterstructures.warn` / `betterstructures.teleport` | 生成の警告の受け取り / 生成された建造物へのテレポート |

いずれも既定は op のみ（`plugin.yml`）。

## 管理者専用の機能

- `/betterstructures`（`/bs`）系: 設定画面（SetupMenu）・コンテンツパッケージのダウンロードと有効化・事前生成（`percentageOfTickUsedForPregeneration: 0.1`）・建造物の手動生成
- 初回設定メニュー（Recommended Setup / Manual Setup / Use Current Content の 3 つのボタン）。開く時だけでなく、ボタンを押した時にも権限（`betterstructures.*` か `betterstructures.initialize`）を確かめる。WorldEdit の有無でボタンは変わらない
- `/bs reload`: 設定と図面を読み直す。走っている貼り付けは取り消さず、持っている図面で最後まで置く
- `/bs pregenerate`（事前生成）と `/bs cancelPregenerate`（取り消し）
- `/bs generateModules <module.yml>`（ダンジョンの生成。権限 `betterstructures.generatemodules`）
  - 設定が `isWorldGeneration: false` なら、実行した人の足元に生成する
  - 設定が `isWorldGeneration: true`（新しいワールドを作って生成）は、Folia では今は使えず、生成せずに案内が出る
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
- 停止をまたぐ再開: 記録の仕組み（30 秒ごとに書く・停止時に最後の 1 回を書く・起動時に続きから流す）はあるが、起動時にそれを呼ぶ配線は別の担当が入れている最中で、この資料の時点のソースでは確かめていない。記録ファイルの場所と名前も未確認
- 他のリージョンにまたがって消えていた建造物も出るようになった、という点: 計画で決まった内容で、コードの現物では確かめていない
- 流量の設定項目が本番の `config.yml` に入った後の値（本番の jar は旧版）
- 建造物の呼び名は、ファイル名からの意訳（ゲーム内で見た目を確かめていない）

## 根拠

| 事実 | 場所 |
|---|---|
| 出るワールド | `M:\minecraft\server\kado_server\res_world\plugins\BetterStructures\ValidWorlds.yml` |
| 間隔・高さ・スポーン周辺の禁止 | `M:\minecraft\server\kado_server\res_world\plugins\BetterStructures\config.yml` |
| WE が無くても動く・起動ログ | `src/main/java/com/magmaguy/betterstructures/worldedit/WorldEditCompatibility.java:88-91` |
| 流量の設定項目・既定値・範囲 | `src/main/java/com/magmaguy/betterstructures/config/DefaultConfig.java:232-298`、`src/main/java/com/magmaguy/betterstructures/runtime/FlowSettings.java:36-48` |
| 置く処理の割合の既定 0.2 | `src/main/java/com/magmaguy/betterstructures/config/DefaultConfig.java:56,133` |
| 事前生成の設定と一時停止の条件 | `src/main/java/com/magmaguy/betterstructures/config/DefaultConfig.java:58-62,134-136`、`src/main/java/com/magmaguy/betterstructures/util/ChunkPregenerator.java:36-50` |
| generateModules の案内（Folia） | `src/main/java/com/magmaguy/betterstructures/modules/WFCGenerator.java:110-115`、`src/main/java/com/magmaguy/betterstructures/commands/GenerateModulesCommand.java:25,34-45` |
| reload のメッセージ・貼り付けを取り消さない | `src/main/java/com/magmaguy/betterstructures/BetterStructures.java:446,452,462-484,527-530,591` |
| 初回設定メニューの権限確認 | `src/main/java/com/magmaguy/betterstructures/menus/BetterStructuresFirstTimeSetupMenu.java:85,133,152,176-178` |
| 事前生成のメッセージ | `src/main/java/com/magmaguy/betterstructures/commands/PregenerateCommand.java:72-77`、`CancelPregenerateCommand.java:42` |
| 権限 | `src/main/resources/plugin.yml:27-48`、各 `commands/*Command.java` の `setPermission` |
| 停止をまたぐ再開の記録 | `src/main/java/com/magmaguy/betterstructures/paste/PasteJournal.java:11-18`、`paste/NativePasteEngine.java:22-23,131-177` |
| 使われない建造物 | `M:\minecraft\server\kado_server\res_world\logs\latest.log`（2026-11-17 の起動時、`em_*` の警告） |
