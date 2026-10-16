# imgs/ — モバイルUIデザイン案

Claudeで作成したスマホ版 ipmsg のUIコンセプト画面（390×844 dp、@2x PNG）。実装の参考用で、仕様の正本ではありません。

| ファイル | 画面 |
|---|---|
| `ipmsg_mobile_01_members.png` | メンバー一覧 |
| `ipmsg_mobile_02_chat.png` | トーク |
| `ipmsg_mobile_03_compose.png` | 新規メッセージ |
| `ipmsg_mobile_04_sealed_received.png` | 封書の受信 |
| `ipmsg_mobile_05_settings.png` | 設定 |

## 現行実装・仕様との差分（採用前に要確認）

- 名前、ホスト名、IPアドレスはすべてサンプル。
- 設定画面のポート `2425` は例示。現行実装は UDP/TCP `42124`。
- 「封書」「開封確認」は未実装（README・TASK.md 参照）。デザイン上の先行表現。
- 現行の不変要件である「全端末」「自分宛て」、および公開鍵指紋の相互承認フローは、この案に含まれていない。
- 色・余白・フォントは案。フォントは IBM Plex Sans JP / IBM Plex Mono を想定（PNGは環境フォントで描画）。

## ソース（.dc.html）

各PNGと同名の `.dc.html` が元のデザインソースです（Claudeのデザインキャンバス形式）。中身はインライン `style` の静的なHTMLで、`<script src="./support.js">` が無くてもブラウザで開けば表示できます（画面間リンクは同名ファイルが存在しないため無効）。色・余白・文字サイズの値を実装へ移すときの参照用です。
