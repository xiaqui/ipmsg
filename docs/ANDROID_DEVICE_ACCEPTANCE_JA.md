# Android M0〜M3 実機検収（2026-10-16）
実装と配送は完了。ここにある実機検査は未実施であり、Workによるコード検査で代替しない。
基準: PR #2 merged / main 57e627dd03edde76f53be10f178be74088ada38f / Actions 38761412863 / artifact 11986061803 / 署名APK配布済み。**再配送不要**。詳細は WORK_PROGRESS.md。

## 準備
- 同一の通常Wi-Fi（ゲスト端末間分離なし）、時計自動設定、最新版の既配布APK。
- 第一組は Android 8 XZ1 Compact と Android 14 Ace III。その後 Android 6 Z3TC、Android 7 Z4T。
- 端末名を別々に設定、両方で「常駐受信」を有効化。Android 13+の通知許可も確認。
- 起動→10〜30秒探索→未承認端末の指紋を双方の実機画面で全桁照合し承認する。照合前に承認しない。

## 実行・記録表
各項目を端末A→B / B→Aで再現し、OS、時刻、結果、スクリーンショット、ログ（秘密鍵等を除外）を記録する。
1. 発見: 未承認候補が出る/未承認の本文受信を拒否/相互承認と指紋一致。
2. 文章: 日本語・改行・絵文字の個別送受信、SQLite受信履歴、保存後ACKによる到着済み（既読ではない）。
3. 同報: 3台で全端末と選択式同報、受信先ごと独立到着確認。送信者自身も1通だけ。
4. 自分宛て: Wi-Fi無効・受信停止中にも送受信履歴に各1件。
5. 保留再送: 受信側のWi-Fiを切り35秒待って送信。保留と送信アプリ再起動後の保持。Wi-Fi復帰/IP変更後に1通だけ受信。
6. ACK喪失: 配送中の回線切断/再送でも重複受信しない。
7. 承認解除: 解除後は受信拒否・保留配送停止。なりすまし/指紋不一致を拒否。
8. 常駐: ホーム・別アプリ・消灯10/30分・復帰・OS再起動・アプリ更新・手動停止/強制停止。省電力設定と通知許可の差を記録。
9. LAN隔離: ゲストWi-Fi等で発見できない時、誤って配達済みとしない。
10. OS互換: Z3TC(6)、Z4T(7)、XZ1 Compact(8)、Ace III(14)で起動/Keystore/探索/送受信/通知。

## 判定
基本通信の実機合格は「少なくとも2台で相互承認と双方向テキスト・ACKが成功」したとき。Windows実装開始のゲートはこの合格とユーザーの別途明示指示の両方。
Doze中の即時配信は保証しない。不具合は端末/OS/再現手順/期待/実際/ログを docs/WORK_PROGRESS.md に追記する。
既存詳細: docs/TESTING.md。

## LAN探索・オンライン判定の重点検収（2026-10-17）

### 現時点の実機観測

- Xperia XZ1 Compact と Rakuten Hand は通信成功例がある。一方でXZ1 Compact側のオンライン表示は不安定。
- Xperia Ace III宛ては「保留0回」のまま。これはTCPを0回失敗した意味ではなく、送信処理が相手をONLINEと判断できず、TCP試行へ進んでいない可能性が高い。
- XZ1 CompactではNoRoot Firewall/VPNが探索を妨げた例がある。無効化後も診断画面のVPN欄で状態を必ず記録する。
- この修正後APKによる3端末の再検証は未実施。コード／Robolectric検査を実機合格とは扱わない。

### Nearby Games修正との比較

Nearby Gamesの `LAN_NSD_MULTICAST_DISCOVERY_FIX_2026-10-16_JA.md` は、NSD/mDNS受信に必要な `WifiManager.MulticastLock` の取得・解放と、発見0件／resolve失敗の区別を主眼とする。ipmsgはNSDではなく署名付きUDPブロードキャストを使うが、MulticastLockが探索受信に影響する点は共通である。ipmsgは常駐受信サービス中にLockを保持し、取得不能でもサービスを落とさず診断へ表示する。

今回のipmsg固有原因は、Wi-Fi/Ethernetの `onAvailable` と `onLinkPropertiesChanged` のたびに全peerの最終発見時刻を0へ戻していたこと。Android実機のcallback churnでONLINEがちらつき、保留配送がTCP試行前に止まり得た。通常のcallbackでは最終発見時刻を保持し、有効IPv4プレフィックスが失われた時または全LAN消失時だけ無効化する。

### LAN診断の読み方

トップ画面の「LAN診断」を開き、必要なら「再探索・再送」を押す。本文、公開鍵、秘密鍵は表示しない。

| 観測 | 主に疑う箇所 | 次の確認 |
| --- | --- | --- |
| UDP送信0 | サービス停止、LANなし、送信処理未起動 | 常駐受信、受信ポート、LAN欄 |
| UDP送信あり・UDP受信0 | Firewall/VPN、AP isolation、端末休止、ブロードキャスト遮断 | 双方のVPN欄、同一SSID、ゲストWi-Fiでないこと |
| UDP受信あり・探索検証拒否 | 時計ずれ、JSON/版、公開鍵、署名、候補制限／鍵不一致 | 拒否段階と端末時計、指紋 |
| 探索検証成功後に最終発見が35秒超 | hello継続受信、回線callback、相手サービス停止 | 直近イベントとIPv4 prefix |
| ONLINE・TCP接続失敗 | 古いIP、42124/TCP遮断、受信サービス停止 | TCP試行／接続失敗、相手の受信ポート |
| TCP接続成功・ACK段階失敗 | 本文送信、受信側認証／DB保存、ACK返送／検証 | ACK・送信段階失敗と相手側TCP受信／ACK送信 |
| ACK受信あり | 相手DB保存後の到着確認済み | 送信履歴が到着済みになること |
| 保留0回・オンライン待ち | TCP未試行 | UDP受信、署名成功、最終発見時刻を先に確認 |

スクリーンショットは双方で同時刻に採り、端末名、OS、SSID（秘密情報は隠す）、VPN、UDP送受信数、探索検証、端末別最終発見、TCP、ACK、保留件数を記録する。

### 修正後の最短再検証

1. XZ1 Compact／Rakuten Hand／Ace IIIを同じ通常Wi-Fiへ接続し、NoRoot Firewall等のVPNを一度無効化する。
2. 全端末で常駐受信をON。LAN診断でUDP/TCP待受中、MulticastLock保持、IPv4 prefixを確認する。
3. XZ1 Compact↔Rakuten Handを双方向送信し、TCP接続とACKを確認する。
4. XZ1 Compact↔Ace IIIを双方向送信する。保留のままなら両端末の診断を採る。
5. Wi-Fi OFF→35秒→ON、同一prefixでのcallback churn、別SSID／別prefixへの切替を試す。同一prefixでは不要なoffline化が起きず、prefix変更時は旧IPをONLINEとして保持しないことを確認する。
