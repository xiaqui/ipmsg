# Work進捗・再開手順

更新: 2026-10-17 JST（最新CI記録は2026-10-16 UTC）
状態: **Android実機LAN探索・オンライン判定の診断／局所修正をPR #4でmainへマージ済み。Windows版は開始していない。Artifact容量回復後のAPK生成・配送と実機検証が未完了。**

## GitHub Releases直接添付の実証実験（2026-10-17 JST）

- 目的: Actions Artifact容量制限を迂回し、同一buildジョブで署名APKをテスト用GitHub prereleaseへ直接添付する。
- 変更: 既存のunit test／Lint／assemble／署名証明書／package／minSdk／SHA-256検査を維持し、`actions/upload-artifact` を使用しない。
- 安全条件: PR／通常build workflowはread-only。Release専用workflowだけwrite権限を持ち、mainへの識別付きmerge pushまたはmain refのworkflow_dispatchだけでprereleaseを作成する。同じRelease jobでAPKを再取得し、サイズ、SHA-256、署名証明書を再照合する。
- Google Drive固定ID `1QFFoHsyjE6MmXe023jpEzW29KMTX4oLN` は変更しない。Windows版開発も開始しない。
- 共通手順: [GITHUB_RELEASE_APK_DELIVERY_JA.md](GITHUB_RELEASE_APK_DELIVERY_JA.md)
- 状態: 実装・PR・独立レビュー・mainマージ・テストRelease実行前。成功値は実行後に追記する。

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
