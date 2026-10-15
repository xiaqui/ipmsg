# Work進捗・再開手順

更新: 2026-10-16 JST（CI記録は2026-10-15 UTC）
状態: **M0〜M3の実装・mainマージ・自動検査・署名APK生成まで完了。Drive配送と実機検証は未完了。M4以降には進んでいない。**

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
| 実APK SHA-256（CIで確認） | 6ffef378b2d11152aed6eaedd2af7979aca41ec2125cc198c95ceb8d88421a0f |
| 検査artifact | ipmsg-checks / 11986371376 |

配布リンク: https://github.com/xiaqui/ipmsg/actions/runs/38761412863/artifacts/11986061803

ZIPにはAPKのほかSHA256SUMS、provenance.txt、signing.txt、badging.txtを収録。
ZIPの認証済みダウンロード参照の取得まで成功。**ローカルZIP展開・APK再取得ハッシュ照合・Driveアップロード／Drive再取得は未実施。ZIPをAPKに改名していない。**
ipmsg専用のDrive固定file IDは未作成。他アプリのDriveファイルは一切上書きしていない。

### 配送が止まった環境上の理由

1. 初期Windows環境でgit cloneは `getaddrinfo() thread failed to start`、gh認証はinvalid。接続済みGitHub APIで実装・PR・CIを完走。
2. artifact取得URLをWindowsで取得しようとして名前解決に失敗。Node／ブラウザの実行系もTransport closed。
3. 途中でクラウドLinux環境に切替後も、権限設定に旧Windowsパスが残り、`workdir=/tmp, shell=/bin/bash`でもコマンド開始前に次のエラー：
   `invalid sandbox permission path URI: file:///C:/Users/xiaqu/Documents/Codex/2026-10-15/new-chat is invalid on linux`
4. 権限の迂回や鍵・トークンの取り出しは行っていない。GitHub APIでのAPK完成と記録までは進めた。

### 配送だけを再開する場合

1. 実行環境のパス／権限の不整合が直ったセッションで上記run/artifactを再取得。
2. ZIP SHA-256を照合して展開し、実APKの2,105,118 bytes／SHA-256を照合。
3. Google Driveを検索してipmsg専用固定先の有無を確認。無ければ新規専用APKファイルを作成してそのIDを保存する。wolf-chess等の固定IDは使わない。
4. MIME `application/vnd.android.package-archive`、名前 `ipmsg-debug.apk` で実APKを配送。
5. Driveを再取得し、同一file ID・サイズ・更新時刻・可能ならSHA-256を照合。次回以降は同じIDへ更新。
6. この文書とREADMEへ固定ID／URL・実際の配送／再取得結果を追記。

wolf-chessの配送正本・事故例・完走ループ・自走配送資料の4本は参照済み。新しいツール定義を優先する。

## 実機／次の作業

Android6 Z3TC、Android7 Z4T、Android8 XZ1 Compact、Android14 Ace IIIでの導入・Keystore・相互発見・通信・消灯・再起動は**全て未検証**。
初回は同じWi-Fiで、双方の設定の「常駐受信を有効にする」をON、端末名を設定し、相手の設定にある指紋全桁を直接比較して双方で承認する。
詳細はTESTING.md。ゲストWi-Fiの端末間分離、IPv6専用LAN、時計の大きなずれ、OS省電力の制約に注意する。
M3の実装はここで停止。配送／実機検収以外でM4以降に進むには別の明示指示を待つ。
