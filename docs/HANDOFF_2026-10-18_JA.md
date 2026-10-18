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

## 追補：2026-10-19 Android検収・NoRoot Firewall切り分け

### Android基本通信の検収状況

- **T1 緑●オンライン表示：合格。** #12を含むmain SHA `de7f542a07414724787bb616ff01806ce7cef6e8` からGitHub Releases直接生成（run `39110854067`、asset `652916953`、2,131,909 bytes）。社長がRelease APKをブラウザからダウンロードし、ChatGPTに添付。倫が既存Drive固定ID `1QFFoHsyjE6MmXe023jpEzW29KMTX4oLN` を上書き（サイズ一致）。XZ1で緑●表示の実機確認済み。**GitHub Release→Driveはユーザー介在の半自動経路。**
- **T2 長時間スリープ：ユーザー報告で合格。**
- **T4 アプリ強制停止→手動起動：再探索・オンライン表示復帰を画像で確認。** XZ1と他端末で緑●を確認。強制停止後の双方向メッセージACKについては写真単体からは未立証。OSの自動サービス再生成試験ではない。
- **T6 古いOS：Android 6 Z3 Tablet Compact（SGP612）とAndroid 7 Xperia Z4 Tablet、およびAndroid 8 XZ1・Android 14 Ace IIIの動作実績。** Android 6ではXZ1との送受信・ACK成功、Android 7もZ4 Tabletからの「仲間に入れて」と相手からの「おやすみ」を受信箱で確認し双方向送受信合格。
- **同報／保留自動再送：合格済み。** 以前の実機観察を参照。
- Wi-Fi明示OFF/ON、重複ACK喪失による重複排除の特殊ケースは独立に十分な実機検証が行われたとは言わない。基本通信の検収を無期限に遅らせる理由にはしない。
- 送信履歴ダイアログは開いた瞬間にDBから一度だけ一覧を生成するため、表示中に保留→到着確認済み／再試行回数が変わっても更新されない。**未実装UI改善候補**：画面表示中のみ定期更新し、状態・本文を別行にする。

### NoRoot Firewall VPN有効時の既知の非互換（Android 6 Z3TC）

**ユーザーが実機で確認した動作：**
- VPN ONでも、ipmsgの常駐受信をONにすると**他端末の名前と指紋（署名済みHELLO情報）は受信・表示できる**。
- 一方、VPN ONのZ3TCは他端末から発見・オンライン認識されず、**新規承認、通常メッセージ送信、受信とも成功しない**。
- VPNを停止すると相互承認と双方向のメッセージ通信が成功。ファイアウォール内でアプリを許可していてもVPN ONでは再現するとのユーザー報告。
- 説明に「VPN下ではUDPもすべて遮断」「発見できない」と書かない。**受信探索の一部は成功するが双方向通信が成立しない非対称状態**と表現する。

**Z3TCの診断写真（VPN ON、再探索・再送を複数回押下）：**
- `状態: Wi-Fi/LAN未接続・送信は保留`、`VPN: あり（既定経路）`、`LAN: なし`、`受信ポート: UDP待受中 / TCP待受中`。
- 累積UDP送信35／受信455、他端末HELLO受信439、署名検証成功439／拒否0、既知IPユニキャスト送信19、自己受信16。他端末の署名済みHELLOの履歴が残っている。
- 累積TCP接続試行9、成功3／失敗6、ACK受信2、ACK送信1、ACK送信段階失敗1。
- 最後のTCP失敗理由として `SocketException: Binding socket to network 102 failed: EPERM (Operation not permitted)` を記録。
- 「手動再探索 要求」はイベントが増えたが、再取得した診断画面ではUDP/TCP試行カウンターが増えず、LANは引き続き「なし」。**上記の成功・受信・エラーは累積履歴**であり、VPN ON後に通信成功した証拠ではない。
- コード `LanEngine.kt` の `networks()` はConnectivityManagerの非VPN Wi-Fi/Ethernetネットワークを列挙し、UDPで `network.bindSocket(socket)`、TCPで `network.socketFactory.createSocket()` を用いる。VPN ON時に非VPNネットワークが列挙・使用できず、指定ネットワークへのソケット結び付けがOSからEPERMで拒否された可能性がある。

**結論と次の一手：** 現象はVPNによる通信経路の制約とipmsgのネットワーク列挙／ソケット結び付け方式との相互作用が疑わしい。NoRoot Firewall内部のパケット遮断が唯一の原因と断定しない。VPN ON/OFFでAndroidが返すNetworkCapabilities、LinkProperties、デフォルトネットワーク、`bindSocket` 失敗時点を突き合わせる診断強化が次候補。VPNを無断で迂回する変更、認証・暗号化の弱体化はしない。**VPN停止時の通常環境の合格とは切り分け、P2候補の互換性課題として扱う。**

## 残件／優先順位

1. **#12の緑●表示修正はRelease生成・Drive固定ID上書き・XZ1実機確認まで完了済み。** 以降のAPKでは引き続きソースSHA、run、署名・サイズ・配送先を突合する。
2. Android基本通信は長時間消灯、復帰配送、Android 6/7の双方向通信までユーザーが合格報告済み。Wi-Fi明示OFF/ON・重複排除の特殊ケース、T4の双方向ACKの厳密な記録は未完扱い。NoRoot Firewall併用の互換性課題は通常動作と分離する。
3. 表示改善候補: 履歴の「状態」と「本文」を明確に分ける（状態「到着確認済み」、本文「保留」が一続きで誤読された）。追加仕様として記録、ユーザーが明示した軽微な緑丸UI修正とは別に扱う。
4. Windows版: Android二台以上の送受信実機成功条件は満たしたが、**明示的な開始指示があるまで実装しない**。`docs/WINDOWS_CLIENT_SPEC_JA.md`、`docs/SHARED_PROTOCOL_SPEC_JA.md`、`docs/FILE_TRANSFER_SPEC_JA.md` と既存実装を正本にし、互換プロトコル・署名・暗号化・ACKを堅持。次段はWindows基本通信、その後ファイル転送。
5. 将来: 開封するまで全画面を覆う封書、開封確認、家庭内放送、時限封書、iOS、Nearby Games連携。未実装と明記する。

## Androidホーム画面ウィジェット（追加構想・未実装）

- 第一候補は2×2「クイック投函」：全端末（自分含む）／自分宛て／受信箱と保留件数。宛先を選んだ状態でアプリの入力画面を開く。
- 将来候補は「受信箱」（未読・直近手紙・返信）と「LAN端末状況」（オンライン台数・保留件数）。
- Android 6以降の互換性、AppWidgetの文字入力制限、電池への影響、封書本文の秘匿を考慮。
- この追記はTODO登録のみ。コード実装・配送は別途のユーザー指示があるまで開始しない。

## 作業方針／引き継ぎ時の注意

- ユーザーのWork/Codex利用枠を節約する。倫がGitHub調査・仕様整理・軽微修正・レビュー整理を直接行い、長時間の環境操作やWindows実装等に必要なときだけWorkを割り当てる。CodexとWorkの利用枠共有に注意。
- レビューは原則実装後1回、P1/P2と修正差分だけ再確認。無限レビュー／修正ループを作らない。既存の実機確認と古い診断の混同禁止。
- 現行のAndroidにはオンライン判定と送信可否を分離したTCP fallbackが実装されている。通信障害と古いAndroidでの絵文字未対応を混同しない。
- **GitHub ReleaseからGoogle Driveへのバイナリ直接連携は、この会話で使用したGitHubコネクタ経由では確立していない。** ユーザー提供APKのチャット添付→Drive既存ID上書きは実証済み。ユーザーの手作業が発生するため、自動化経路は別途検討。Google Apps Scriptや公開配布の案は議論されたが未実装。リポジトリのPublic化も未実施。
- GitHub/Driveの**実際のSHA、ファイルID、現行コード**を更新前に再照合し、配送完了・実機合格は証拠があるときのみ記す。署名秘密鍵は公開・出力しない。
