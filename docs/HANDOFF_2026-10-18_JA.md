# IP Messenger 引き継ぎ書（2026-10-18）

## 目的・現在地

対象: `xiaqui/ipmsg`。Android 6.0+（API 23+）の家庭内LAN専用「手紙型」メッセンジャー。チャット画面主体にしない。全端末（自分を含む）・自分宛て・個別／複数宛先・相互指紋承認・暗号化通信・受信箱／送信履歴・保留／自動再送・到着確認がAndroid基本実装。今後の順序は Android最終検収 → ユーザー指示後Windows → ファイル転送／封書 → iOS等。

**重要：2026-10-17深夜の実機結果により、旧 `docs/WORK_PROGRESS.md` の「実機検証すべて未完了」「Driveは旧版」という記載は一部古い。必ず本書と現行GitHub／Driveの実測値を優先し、未確認項目を勝手に合格扱いしない。**

## マージ済み実装と配布の履歴

- Android基本機能: PR #2（M0〜M3）。
- LAN診断／callback churn緩和: PR #4。
- 第2段階 LAN探索修正: PR #9、main実装 `bc4ec6127c65ae75654d967d222f8669597357a2`。既知IPへの署名HELLOユニキャスト、オフライン判定でも承認済み既知IPにTCP配送を試行、UDP詳細診断、LAN callback・待受ソケットの安定化。セキュリティを緩めない。
- 復帰時再送の補強: PR #11、main実装 `2d032dec5221ae60846ad71abf65bc1f747ddc32`。同一サブネット内IP変更も検知、当該宛先のバックオフを最大10秒以内に短縮。CI成功・レビューP2解消。
- Android 8以前の絵文字表示問題: PR #12、main実装 `bd445d1d68d5298ac4046230a474cf3aaac1ff07`。🟢絵文字を通常字形「●」＋色付きSpanに置換。**この変更を含むAPKはまだ配送・実機確認していない。** 通信ロジック変更なし。
- 既存LANメッセンジャー比較: PR #13、`docs/EXISTING_LAN_MESSENGERS_RESEARCH_JA.md` を追加（Syna、FeiQ Android、TransportChat、本家IP Messenger）。コードへの流用なし。ブランチ `design/mobile-ui-mockups` / PR #5のClaudeデザイン案は**不採用**。mainに取り込まない。
- Artifactストレージ制限対策として、署名APKを**Actions Artifactを経由せず**GitHub Releasesの検証済みprereleaseへ出す `.github/workflows/release-android-apk.yml` を導入（PR #6／#7）。テスト、Lint、署名／package／minSdk／ハッシュ／再取得照合を通したものだけを使う。
- 配送済みLAN修正APK: GitHub Actions run `39015422319`、Release tag `ipmsg-test-b87c43c2d149-run-39015422319-1`、Release asset `649912733`、`ipmsg-debug.apk`、**2,131,703 bytes**、SHA-256 `9ecdf11462da537b02ee012e8925c62583664bcd2d325d2e61d3ff73c702d9c0`。APKはユーザーがチャットへ提供し、倫がDrive既存ファイルIDに上書きした。Driveから再取得したサイズは2,131,703 bytes。以後、Drive固定ID維持が必須。
- Drive固定ID: `1QFFoHsyjE6MmXe023jpEzW29KMTX4oLN`、URL: https://drive.google.com/file/d/1QFFoHsyjE6MmXe023jpEzW29KMTX4oLN/view 。上記は**PR #12のUI修正前**のAPK。ファイル名 `ipmsg-debug.apk`。新規作成ではなく既存ファイルの**内容更新**を行う。

## 実機検証で確認した事実

端末: Xperia XZ1 Compact（SO-02K／Android 8）、Rakuten Hand（P710）、Xperia Ace III（SO-53C／Android 14）。

1. 旧版ではXZ1のUDP送受信48/48、探索成功0で自端末HELLOしか直近に観測できず、両端末OFFLINE。NoRoot Firewall（VPN）停止後に改善したことがあるが単独原因は断定できない。PR #4はcallback churnによるseen消失を修正。
2. 新版のXZ1ではUDP「他端末HELLO」受信132、探索検証成功132、拒否0、承認済み2台ONLINEを診断画面で観測（2026-10-17 17:30頃）。🟢絵文字が見えないが、オフライン文字の不在と診断上のONLINEから**表示互換性問題**と判明。PR #12でUIのみ対策済み。
3. XZ1↔Hand、XZ1↔Ace III、Hand↔Ace IIIで送受信成功例あり。XZ1のTCP送信5接続成功／失敗0、ACK受信3等を観測。途中でACK待機・検証のEOFException 2件が診断に出たが、その時点の保留は0件。再現確認は今後。
4. Handを意図的にスリープするとXZ1でHandがOFFLINE、復帰後に再度ONLINEになった（17:29〜17:30頃）。これは意図的操作を伴う成功例であり、任意の長時間放置条件での安定性保証とは別。
5. **2026-10-17 23:53〜23:56、XZ1から「保留」という本文をHandとAce IIIへ同報**。オンラインのAce IIIは早期に到着・送信側ACK済み、オフラインのHandは「保留／再送待ち（3回）」後、復帰して23:55に受信。23:56には送信側履歴で**両宛先「到着確認済み」**。**接続復帰後の保留自動配送は実機で成功**。ユーザーが「保留」という本文にしたため、履歴の「到着確認済み 保留」は状態＋本文でありバグではない。

## 残件／優先順位

1. **UI表示修正版 PR #12 を含む署名APKの生成と固定Drive IDへの上書き・実機検証**。APK更新のたびにソースSHA、Actions run、Release asset、APK SHA、Drive再取得を突合。変更が軽微なら他の更新と同梱してよい。
2. Android最終検収: 長時間消灯／Wi-Fi OFF→ON／サービス再起動／再接続、同報の宛先別ACK、重複受信防止、Android 6/7端末での機能・小画面表示。既に通った混在宛先・保留復帰配送試験をゼロから繰り返してWork枠を浪費しない。
3. 表示改善候補: 履歴の「状態」と「本文」を明確に分ける（状態「到着確認済み」、本文「保留」が一続きで誤読された）。追加仕様として記録、ユーザーが明示した軽微な緑丸UI修正とは別に扱う。
4. Windows版: Android二台以上の送受信実機成功条件は満たしたが、**明示的な開始指示があるまで実装しない**。`docs/WINDOWS_CLIENT_SPEC_JA.md`、`docs/SHARED_PROTOCOL_SPEC_JA.md`、`docs/FILE_TRANSFER_SPEC_JA.md` と既存実装を正本にし、互換プロトコル・署名・暗号化・ACKを堅持。次段はWindows基本通信、その後ファイル転送。
5. 将来: 開封するまで全画面を覆う封書、開封確認、家庭内放送、時限封書、iOS、Nearby Games連携。未実装と明記する。

## 作業方針／引き継ぎ時の注意

- ユーザーのWork/Codex利用枠を節約する。倫がGitHub調査・仕様整理・軽微修正・レビュー整理を直接行い、長時間の環境操作やWindows実装等に必要なときだけWorkを割り当てる。CodexとWorkの利用枠共有に注意。
- レビューは原則実装後1回、P1/P2と修正差分だけ再確認。無限レビュー／修正ループを作らない。既存の実機確認と古い診断の混同禁止。
- 現行のAndroidにはオンライン判定と送信可否を分離したTCP fallbackが実装されている。通信障害と古いAndroidでの絵文字未対応を混同しない。
- **GitHub ReleaseからGoogle Driveへのバイナリ直接連携は、この会話で使用したGitHubコネクタ経由では確立していない。** ユーザー提供APKのチャット添付→Drive既存ID上書きは実証済み。ユーザーの手作業が発生するため、自動化経路は別途検討。Google Apps Scriptや公開配布の案は議論されたが未実装。リポジトリのPublic化も未実施。
- GitHub/Driveの**実際のSHA、ファイルID、現行コード**を更新前に再照合し、配送完了・実機合格は証拠があるときのみ記す。署名秘密鍵は公開・出力しない。
