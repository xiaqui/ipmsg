# M6-1 進捗（2026-10-23 JST）

- 基準main: `f8e34783ec4842a9d97f40108b0070fd9386624b`。履歴本文コピー/個別削除、Android詳細ボタン、Windows自端末行/選択、共通ファイル契約とオフライン試験基盤を実装中。
- 削除は端末内1記録のみ。保留writeと削除を直列化。受信削除は本文を残さずdigest tombstoneを保持し再送による復活と改変を拒否する。既にネットワークへ送った本文の遠隔取消はできない。
- ローカル: Python17成功、Android生成vector1件はCI待ち。Ruff成功。独立レビューP1なし、P2のGUI停止/試験不足/契約サンプル不足を修正中。
- 未完了: Android CI/WindowsパッケージCI、最終レビュー判定、PRマージ、main由来両Release再取得SHA/サイズ/署名照合、実機検収。
- ファイル送受信本体はM6-2未実装。契約正本はFILE_TRANSFER_V1_CONTRACT_JA.md。

---
# M5 Windows版 進捗（2026-10-19 UTC）

- 状態: 実装ブランチ作成中。PR、独立レビュー、CI、mainマージ、Release配送は未完了。
- 基準main: `55d1d353535211a60c93f0aecb9f77cc4746221c`。
- 選定: Python 3.12 + PySide6。通信/保存/暗号コアをGUIから分離し、Windows秘密鍵はユーザー単位DPAPIで保護する。
- 実装: Android v1互換の署名UDP探索、RSA鍵指紋、双方承認、RSA-OAEP(SHA-1/MGF1)+AES-256-GCM、署名TCP、保存後ACK、SQLite履歴、重複排除、10秒〜5分の指数バックオフ、既知IP再送、全端末（自分含む）/自分宛て/個別/複数、トレイ通知、任意自動起動。
- ローカル確認: Python unittest 7件成功、compileall成功、diff check成功。手元の旧チェックアウトにはGradle Wrapperがなく、Android共有ベクトル試験とWindows EXEビルドはCI待ち。
- 配送: `actions/upload-artifact` は新規workflowに不使用。同一Windows jobで試験→PyInstaller→draft Release直添付→再取得SHA-256/サイズ一致→prerelease公開する。
- 未検証: Windows 11実機起動、VAIO↔AndroidのUDP/TCP/ACK、Firewall案内、SmartScreen、ログイン自動起動。M6ファイル転送、M7重要便、iOSは対象外。
- 再開点: PR CIを通し、独立レビューのP1/P2を修正後にmainへマージ。main runのsource SHA/run ID/Release tag・ID/asset ID/サイズ/SHA-256を本書に追記する。

---

# Work進捗・再開手順

更新: 2026-10-17 JST（最新CI記録は2026-10-17 UTC）
状態: **LAN探索・オンライン判定の第2段階修正と復帰後再送の追補はPR #9・#11でmainへマージ済み。コード、63件テスト、Lint、assemble、署名検査、独立レビューは完了。3端末での実機再検証と、この完成main SHAからのRelease APK生成・Drive配送は未実施。Windows/iOS版は開始していない。**

## 追加修正 PR #11（2026-10-17 JST）

- [PR #11](https://github.com/xiaqui/ipmsg/pull/11) をマージ。完成main SHA: `2d032dec5221ae60846ad71abf65bc1f747ddc32`。第2段階修正の復帰後再送に対する追加対応。
- NetworkのIPv4構成変更時、同一prefix内での実IPv4アドレス変更も検知して、該当する承認済みpeerの保留メッセージ再試行を最大10秒以内に短縮する。試行回数と指数バックオフの履歴は維持。
- 署名検証済みHELLOで端末を発見した時も、当該peerの保留を再試行対象とする。他peerのバックオフは短縮しない。
- CodexレビューのP2「prefixが同じ場合にアドレス変更を見逃す」を追加commit `d264b3cc2a30398b0b38bd429ad8225928fa197c` で修正、レビューthread返信・解決済み。
- 追加修正最終CI: [39015098756](https://github.com/xiaqui/ipmsg/actions/runs/39015098756) 成功。実機検証とこの完成main SHA以降のAPK Release/Drive配送は未実施。

## Android LAN探索・オンライン判定 第2段階（2026-10-17 JST）

- 実装PR: [#9](https://github.com/xiaqui/ipmsg/pull/9)
- PR最終HEAD: `e3667d690f67dc5bb3899d730ef7e77884449f8c`
- 完成main実装SHA（squash merge）: `bc4ec6127c65ae75654d967d222f8669597357a2`
- 最終PR CI: [39014364854](https://github.com/xiaqui/ipmsg/actions/runs/39014364854)、job `117343285124`。Robolectric 63件、失敗/エラー0、未実行0。Lint、assembleDebug、package=`com.xiaqui.ipmsg`、minSdk=23、共有署名のv1/v2と証明書SHA-256照合に成功。
- PR merge ref用の一時APKは2,129,971 bytes、SHA-256 `6a3222386c926abe9efb6556ad77ad993365a4a15a8499213508e37180bb48b8`。`source_sha=1994d48d1ea75a98f2d58cb0c051387931cb7fb3` はPR merge refであり完成main SHAではない。保存・Release添付・配送しておらず、修正版配送物として使用禁止。

### 実機データから確認済みの事実と未確定の仮説

- 確認済み: Hand ↔ Ace III、Hand → XZ1、XZ1 → Hand（ACKあり）には成功例がある。したがって「XZ1/Android 8はUDP/TCP通信不能」とは扱わない。
- 確認済み: 最新診断のXZ1はUDP送信48/受信48、探索検証成功0、直近受信元192.168.1.25（自端末IP）。ただし48件すべてが自己受信だった証拠ではない。過去のTCP成功もUDP探索成功の証拠ではない。
- コードで確認済み: 保留送信は従来ONLINE端末だけに絞られ、OFFLINE表示のAce IIIにはTCP試行が入らなかった。サービス開始/終了や回線状態処理で最終発見時刻を広く消す経路もあった。
- 仮説: NoRoot Firewall、端末固有の省電力、Wi-Fi再接続、callback順序がUDP受信継続へ影響する可能性はある。停止後に改善した事実はあるが、単独原因とは未確定。

### 局所修正

- UDP受信を送信元IP、時刻、`自己HELLO` / `他端末HELLO` / `破棄`、破棄理由で分類。署名成功/拒否と分け、本文・公開鍵・秘密鍵は記録しない。
- 既存のUDPブロードキャスト探索を維持し、承認済み端末の現在LAN内の既知IPv4へ署名HELLOをユニキャストする。
- OFFLINE表示でも、承認済みかつ現在LAN内の既知IPへ1端末1件/周期でTCP配送を試す。試行前に永続化し、10秒から最大5分の指数バックオフ、3秒接続/5秒読取/8秒総時間制限を維持する。
- callbackごとに全peerを即時OFFLINE化しない。現在の非VPN Wi-Fi/Ethernetを遅延再照合し、空状態を再確認した場合だけ全失効する。世代番号で古い確認予約とTOCTOUを無効化し、別prefixのpeerだけ選択失効する。
- UDPのタイムアウト以外のI/O障害は握りつぶさず、待受ソケットを診断記録付きで再生成する。短いサービス再起動では35秒以内の署名済み最終発見を保持し、受信機能を明示OFFにした場合は失効する。
- 診断画面をAndroid 8の小画面でスクロール・文字選択可能にし、絶対時刻と経過時間を併記した。コピー機能を維持。
- 公開鍵ID、HELLO署名検証、本文暗号化/署名、認証ACK、message ID照合、重複排除は変更していない。古いIPが別端末へ再割当てされても、意図した公開鍵で検証できるACKなしに配送済みにならない。

### レビューと残る実機確認

- 独立レビューで、API 23の`onAvailable(null LinkProperties) → old onLost`、古い空確認予約、世代検査後のTOCTOUをP2として検出し、遅延二段確認・世代無効化・同期副作用・回帰テストで解消。最終HEADの残存P1/P2なし。
- GitHub CodexレビューのP2「UDPソケットI/O障害を内側で握りつぶす」を修正し、inline threadを解決。全レビューthread解決済み。
- **未検証**: XZ1でのUDP内訳（48件の自己/他端末/破棄分類）、XZ1 ↔ Hand回帰、Hand ↔ Ace III回帰、XZ1 → Ace IIIのOFFLINE既知IP TCP試行・ACK、Wi-Fi OFF/ON、アプリ/サービス再起動、ネットワーク切替、消灯/省電力、NoRoot Firewall ON/OFF。実機で成功するまで「不具合修正確認済み」と報告しない。

### 完成main SHAからのAPK再生成・固定Drive配送（容量回復後）

1. mainが `bc4ec6127c65ae75654d967d222f8669597357a2` 以上であることを確認し、main refの `release-android-apk.yml` を手動実行する。PR run、一時APK、既存Release、古いArtifactを流用しない。
2. 同一job内のunit test、Lint、assembleDebug、alias、v1/v2署名、証明書SHA-256、package、minSdk、SHA256SUMS、provenance検査が全て成功し、非公開draftへ添付したAPKを再取得・再照合後にprerelease公開したrunだけを採用する。
3. run ID、main source SHA、Release ID/tag、APK asset ID、APKサイズ/SHA-256/証明書を本書へ追記する。Releaseの`ipmsg-debug.apk`以外を配送しない。
4. 検証済みAPKでDrive固定ID `1QFFoHsyjE6MmXe023jpEzW29KMTX4oLN` の内容を上書きし、固定URLと共有権限を維持する。rename/フォルダ移動は固定IDが変わらない範囲だけ可。
5. Driveから再取得し、Release assetとサイズ、SHA-256、byte-for-byte一致を確認してから配送完了とする。

現在のDrive固定IDは、2026-10-17 13:10:25 JSTにテストRelease asset `649374811`（source `a0da626b412b0224acdc6ddf47368f0e1ed975d6`、2,118,069 bytes、SHA-256 `a1a4de64fbfb65bdebd618b407d928540f8b56c078da36cd62b6c3471dcdb342`）で上書きし、再取得一致まで確認済み。ただしこれは第2段階修正前のAPKであり、今回の修正版として扱わない。

## GitHub Releases直接添付の実証実験（2026-10-17 JST）

- 結果: **成功**。Actions Artifactを使用せず、同一Release jobでテスト・Lint・ビルド・署名検査・draft添付・再取得検査・prerelease公開まで完結した。
- 実装PR: [#6](https://github.com/xiaqui/ipmsg/pull/6)（main `06f81c5142426a5e2d75897355a2f29dee6cbc5a`）、draft一覧反映遅延の修正PR: [#7](https://github.com/xiaqui/ipmsg/pull/7)（完成main SHA `a0da626b412b0224acdc6ddf47368f0e1ed975d6`）。
- 成功run: [38997429136](https://github.com/xiaqui/ipmsg/actions/runs/38997429136)、job `117292936850`。通常read-only build run [38997429134](https://github.com/xiaqui/ipmsg/actions/runs/38997429134) も成功。
- テスト: 57件、失敗0、未実行0。Lint／assembleDebug／package=`com.xiaqui.ipmsg`／minSdk=23が成功。
- 署名: v1/v2成功。証明書SHA-256 `dcfd2c8122274ec4148473c96b1853989c8d22e58e445d6ec4889ad5b34dffb2`。Releaseから再取得したAPKにも同じ証明書を再照合。
- Test prerelease: [ipmsg-test-a0da626b412b-run-38997429136-1](https://github.com/xiaqui/ipmsg/releases/tag/ipmsg-test-a0da626b412b-run-38997429136-1)
  - Release ID: `415550940`
  - APK asset ID: `649374811`
  - source SHA: `a0da626b412b0224acdc6ddf47368f0e1ed975d6`
  - APK: `ipmsg-debug.apk`、2,118,069 bytes
  - SHA-256: `a1a4de64fbfb65bdebd618b407d928540f8b56c078da36cd62b6c3471dcdb342`
  - Release APIのasset digestも同じ `sha256:a1a4de64fbfb65bdebd618b407d928540f8b56c078da36cd62b6c3471dcdb342`。
- 安全境界: PR／通常build workflowは `contents: read`。Release専用workflowだけ `contents: write` で、main ref以外の手動実行を拒否。APKはdraftのまま再取得検査し、全検査成功後だけprereleaseとして公開。
- 初回run [38996973493](https://github.com/xiaqui/ipmsg/actions/runs/38996973493) はdraft作成直後の一覧反映遅延で停止。Release ID `415548211`／APK asset ID `649359200` はdraftのまま非公開で、本番・配布物として扱わない。
- 独立レビュー: PR #6でPR書込権限、main外dispatch、公開前検査、旧Artifact手順をP1/P2として修正。PR #7で有界リトライと完全tag一致を再レビューし、最終残存P1/P2なし。全inline thread解決済み。
- Google Drive固定ID `1QFFoHsyjE6MmXe023jpEzW29KMTX4oLN` は後続作業でこのRelease assetへ上書き済み。サイズ・SHA-256・byte-for-byte一致を再取得確認した。固定IDとURLは維持。Windows版開発は開始していない。
- 共通手順: [GITHUB_RELEASE_APK_DELIVERY_JA.md](GITHUB_RELEASE_APK_DELIVERY_JA.md)

## Android LAN探索・オンライン判定修正（2026-10-17 JST）

- PR: https://github.com/xiaqui/ipmsg/pull/4
- 作業ブランチ: `fix/android-lan-diagnostics`
- PR最終HEAD: `e31475abf2143b970592cb8764bcd8885608547a`
- 完成main実装SHA（squash merge）: `38e5c831c2ab03ab44fb1ab412f69b673a528c57`
- PRマージ日時: 2026-10-17 JST（2026-10-16 UTC）
- 実機観測: Xperia XZ1 CompactはONLINE判定が不安定。Rakuten Handとの通信成功例あり。Ace III宛ては保留0回のままで、TCP失敗ではなくONLINE判定前で止まっている可能性が高い。
- Nearby GamesのNSD修正と比較。両者ともMulticastLockを必要とするが、ipmsgはNSDではなく署名付きUDPブロードキャストを使用する。
- 原因: `ConnectivityManager.NetworkCallback` の利用可能／LinkProperties変更のたびに全peerの `seen` を0にしており、実機のcallback churnでONLINEがちらついた。
- 局所修正: 通常callbackでは最終発見を保持し、全LAN消失または有効IPv4 prefix membership喪失時だけoffline化。callback時は即時hello／保留再評価。MulticastLock取得失敗は可視化して探索サービスを継続。
- LAN診断: UDP送受信、helloの検証成功／拒否段階、端末別最終発見時刻、TCP送信試行／接続／接続失敗、TCP受信、ACK送受信／送信・ACK段階失敗、VPN、LAN prefix、受信ポート、保留／オンライン待ちを表示。本文・公開鍵・秘密鍵は含めない。
- 独立レビュー: TCP/ACK段階未分離、prefix変更／複数LAN／IPv4消失、テスト期待値、Unicode制御文字による診断行偽装を検出し、全て実装／回帰テストへ反映。最終HEAD `e31475a` の再レビューは残存P1/P2なし。
- GitHub自動レビューのP1「Android実装は旧フェーズ範囲外」は、今回のユーザー明示指示とTASK.md先頭の現行指示で非該当。P2の診断端末名制御文字はASCII／Unicode区切り／bidi FORMATをコードポイント単位で可視エスケープして解決。2つのinline threadは返信・解決済み。
- 最終PR CI: https://github.com/xiaqui/ipmsg/actions/runs/38925651415 （job `117056381967`）。57件、失敗0、未実行0。Lint／assembleDebug／署名検査／package／minSdk検査は成功。job全体のfailureはArtifact upload 2件だけ。
- CIが一時生成した未配送APK: 2,118,074 bytes、SHA-256 `e217dde615650ae910dcd0dd69b0004843c6bee916bed2f3f734f817bbfbe138`。Artifact作成前に容量上限で失敗したため、このAPKは取得・配送可能な完成物として扱わない。
- Windows版開発は未着手。この修正ではAndroidコードと関連文書以外を変更しない。
- 詳細な実機採取項目: [ANDROID_DEVICE_ACCEPTANCE_JA.md](ANDROID_DEVICE_ACCEPTANCE_JA.md)

### Artifact容量制限中の扱い

GitHub ActionsのArtifactストレージ容量制限によるupload失敗は既知の環境要因であり、開発停止条件にしない。unit test、Lint、assembleDebug、署名／badging／SHA検査までの結果を合否判断し、artifact uploadだけの失敗は分離して記録する。容量回復前に古いAPKを修正版として再配送しない。 最終run `38925651415` では `ipmsg-debug` と `ipmsg-checks` の両uploadが `Artifact storage quota has been hit. Usage is recalculated every 6-12 hours.` で失敗した。

### 修正版APK生成・固定URL配送再開（Release経路）

1. 本節の完成main SHAを確認し、必ずmain refの `release-android-apk.yml` をworkflow_dispatchする。古いReleaseや過去artifactを流用しない。
2. 同一jobでunit test（SDK 23/28/34）、Lint、assembleDebug、署名alias／証明書SHA-256、package=`com.xiaqui.ipmsg`、minSdk=23が成功したrun IDを確定する。
3. workflowがdraftへ添付したAPKを再取得し、サイズ、SHA-256、署名証明書を再照合してからprerelease公開したことを確認する。source SHA、run ID、Release ID/tag、APK asset IDを記録する。
4. Releaseの `ipmsg-debug.apk` を取得し、同時添付の `SHA256SUMS`、`provenance.txt`、`signing.txt`、`badging.txt`と照合する。Source code ZIPをAPKとして扱わない。
5. APKのサイズ／SHA-256を記録し、Drive固定ID `1QFFoHsyjE6MmXe023jpEzW29KMTX4oLN` の内容を実APKで更新する。Drive上のrenameやフォルダ移動は固定IDが変わらない限り可。共有権限とURLを再確認する。
6. Driveから再取得し、Release assetとのサイズ、SHA-256、byte-for-byte一致を検証する。run／Release／asset／APK／Driveの値を本書へ追記する。
7. XZ1 Compact、Rakuten Hand、Ace IIIへ導入し、双方向UDP探索、署名、最終発見、TCP、ACK、VPNを診断画面で採取する。実機成功前に「実機修正済み」と断定しない。


## 実装／コミット／PR

- PR: https://github.com/xiaqui/ipmsg/pull/2 （squash merge済み）
- APKを生成したmain実装SHA: `57e627dd03edde76f53be10f178be74088ada38f`
- PR最終HEAD: `a5238ccd498781ba5399c572532e3c68a18a7986`
- この進捗／TASK更新は後続の文書変更。APKのソースSHAは上記実装SHAを参照する。
- 既存android由来の構成を使用。applicationId/namespace=`com.xiaqui.ipmsg`、表示名IP Messenger、minSdk23、target/compileSdk35、versionCode3／0.3.0。

| 範囲 | 実装 |
| --- | --- |
| M0 | 専用ID／表示名、Android6互換、CIのテスト・Lint・署名検査・来歴出力 |
| M1 | 署名付きIPv4 LAN発見、公開鍵指紋の双方照合／承認、暗号化個別テキスト |
| M2 | 全端末（承認済み＋自分）／複数／個別、自分宛て、SQLite送受信履歴、保存後の認証ACK |
| M3 | ユーザーONのconnectedDevice常駐受信、通知、Wi-Fi復帰、永続保留・再送・重複排除、承認解除 |

全画面封書・開封確認・ファイル・音声・時限封書・Nearby Games連携は未実装。要件はTASK.mdに保持。

## 検査／独立レビュー

- PR最終CI: https://github.com/xiaqui/ipmsg/actions/runs/38760905745 （成功）
- main配送用CI: https://github.com/xiaqui/ipmsg/actions/runs/38761412863 （成功）
- main build job ID: `116513346927`
- SDK23/28/34のRobolectricで **48件、失敗0、未実行0**。Lint／assembleDebug成功。
- 16項目を3 SDKで実行。署名付き発見、なりすまし／改ざん拒否、暗号化TCP実フレーム往復、独立同報と自分宛て、DB再オープン、保存失敗ロールバック、ACK欠落再受信、承認解除、再送バックオフ、UTF-8／JSON境界、通知PendingIntent分離、候補枠占有を検査。
- Android実サービスの常駐／Wi-Fi無線／Android Keystore実装／消灯を実機で確認した結果ではない。
- Codex独立レビュー対象: `6379e71626`。
- Copilot独立レビューを4回実施。対象SHA: `6379e71626`、`8803e16bf9`、`a34e396d2c`、`82323b78bb`。
- P1（別Store間の承認解除と探索の競合）は、探索更新から既存trusted列の書き戻しをなくして修正。
- P2: JSON最大エスケープ、LAN prefix、未知接続のワーカー占有／総時間制限、探索と配送分離、ACK失敗時通知、保留SQL検索、送信直前の承認再確認、常駐スイッチ、通知ルーティング、未承認候補の枠占有を修正。
- レビュー本文のPreviously missedも確認。「0 open findings」表示だけを合格と判断していない。12のinline threadは全て解決済み、本文にだけ記載された3項目にも対応済み。
- 最終の候補枠修正（be4393f）とCI条件更新は自己点検と回帰検査で検証。**この最終差分に対する独立再レビュー／人による暗号・サービス設計の承認を得た、とは扱わない。**
- 未承認候補はhostごと1件・全体16件。満杯時は古い未承認のみ入替、承認済みpeerは削除しない。64個の有効な署名付きIDを同一hostから投入しても、別hostの正規端末が登録・承認できることを検査。
- 実機手順: [TESTING.md](TESTING.md)

## 署名

main CIで4 Secretsの読み込み、alias照合、apksigner検証、キーストア証明書とのSHA-256一致を確認。
Secretsの値／秘密鍵は取得・記録していない。

- APK signature v1 / v2: 検証成功（min-sdk-version23）
- 証明書SHA-256: `dcfd2c8122274ec4148473c96b1853989c8d22e58e445d6ec4889ad5b34dffb2`
- package=`com.xiaqui.ipmsg`、minSdk23をaapt badgingで照合。

## APK／配布

必ず以下の鎖で取得する。古いPR用APKと混同しない。

| 項目 | 値 |
| --- | --- |
| mainソースSHA | 57e627dd03edde76f53be10f178be74088ada38f |
| Actions run ID | 38761412863 |
| artifact名／ID | ipmsg-debug / 11986061803 |
| artifact ZIPサイズ | 2,034,838 bytes |
| artifact ZIP SHA-256 | f7cea44afbbbdea41c2c438f3e79cff389ec932050a5f0e48327fc0d278a16bf |
| ZIP内のAPK | ipmsg-debug.apk |
| 実APKサイズ（CIで確認） | 2,105,118 bytes |
| 実APK SHA-256（CI／Drive再取得で確認） | 6ffef378b2d11152aed6eaedd2af7979aca41ec2125cc198c95ceb8d88421a0f |
| Driveファイル名／固定ID | ipmsg-debug.apk / 1QFFoHsyjE6MmXe023jpEzW29KMTX4oLN |
| Drive URL | https://drive.google.com/file/d/1QFFoHsyjE6MmXe023jpEzW29KMTX4oLN/view |
| Drive配送日時 | 2026-10-16 02:44 JST（2026-10-15 17:44 UTC） |
| 検査artifact | ipmsg-checks / 11986371376 |

配布リンク: https://github.com/xiaqui/ipmsg/actions/runs/38761412863/artifacts/11986061803

ZIPにはAPKのほかSHA256SUMS、provenance.txt、signing.txt、badging.txtを収録。
2026-10-16 JSTにartifact ZIPを再取得・展開し、ZIP内の実APKをDriveへ新規配送した。Drive上に同名 `ipmsg-debug.apk` が存在しないことを事前確認し、wolf-chess等の他アプリ用固定ファイルは上書きしていない。
Driveから再取得したAPKは **2,105,118 bytes**、SHA-256 `6ffef378b2d11152aed6eaedd2af7979aca41ec2125cc198c95ceb8d88421a0f` で、展開元とbyte-for-byte一致した。今後は上記固定IDを更新して同じURLを維持する。

### 前回配送が止まった環境上の理由（解消済み）

1. 初期Windows環境でgit cloneは `getaddrinfo() thread failed to start`、gh認証はinvalid。接続済みGitHub APIで実装・PR・CIを完走。
2. artifact取得URLをWindowsで取得しようとして名前解決に失敗。Node／ブラウザの実行系もTransport closed。
3. 途中でクラウドLinux環境に切替後も、権限設定に旧Windowsパスが残り、`workdir=/tmp, shell=/bin/bash`でもコマンド開始前に次のエラー：
   `invalid sandbox permission path URI: file:///C:/Users/xiaqu/Documents/Codex/2026-10-15/new-chat is invalid on linux`
4. 権限の迂回や鍵・トークンの取り出しは行っていない。GitHub APIでのAPK完成と記録までは進めた。
5. 2026-10-16 JSTの再開時にはLinux実行が正常化し、取得・展開・照合・Drive配送・Drive再取得照合を完了した。

### 次回以降の固定URL配送

1. 対象main SHAと成功run、Release ID/tag、APK asset IDを確定し、Releaseの `ipmsg-debug.apk` を取得。
2. Release assetを同時添付のSHA／署名／provenance記録と照合。Source code ZIPをAPK名にしない。
3. Drive固定ID `1QFFoHsyjE6MmXe023jpEzW29KMTX4oLN` の現在メタデータとファイル名 `ipmsg-debug.apk` を確認。
4. MIME `application/vnd.android.package-archive` の実APKで同じ固定IDを更新し、URLを維持。
5. Driveから再取得し、Release assetとのサイズ・SHA-256・byte-for-byte一致を確認して本書を更新。

wolf-chessの配送正本・事故例・完走ループ・自走配送資料の4本は参照済み。新しいツール定義を優先する。

## 実機／次の作業

Android6 Z3TC、Android7 Z4T、Android8 XZ1 Compact、Android14 Ace IIIでの導入・Keystore・相互発見・通信・消灯・再起動は**全て未検証**。
初回は同じWi-Fiで、双方の設定の「常駐受信を有効にする」をON、端末名を設定し、相手の設定にある指紋全桁を直接比較して双方で承認する。
詳細はTESTING.md。ゲストWi-Fiの端末間分離、IPv6専用LAN、時計の大きなずれ、OS省電力の制約に注意する。
M3の実装はここで停止。配送／実機検収以外でM4以降に進むには別の明示指示を待つ。


