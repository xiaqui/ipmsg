# Work進捗（2026-10-15）

## 範囲
TASK.md / AGENTS.md / wolf-chess docs/RIN.md を参照。M0〜M3のみ。
既存main 285c1ffcd045bd15d718afd40eb5e3f9b4a1fd5b を起点とし、未完了PRなしを確認。
wolf-chess の配送手順4本を参照済み。他アプリの固定Drive IDは流用しない。

## 実装状況
- M0：専用ID com.xiaqui.ipmsg、表示名、minSdk23、versionCode3。CIにLint・署名証明書照合・SHA256/provenanceを追加。
- M1：署名付きIPv4 UDP発見、公開鍵指紋承認、暗号化TCPテキスト送受信。
- M2：全端末（承認済み＋自分）／複数／個別、自分宛て、SQLite履歴、保存後の認証ACK。
- M3：ユーザー開始connectedDevice常駐、通知、省電力案内、再接続発見、永続保留・再送・重複排除。
- 重要便／開封／ファイル／音声／時限封書／Nearby Gamesは未実装のまま保持。

## 検査
SDK23/28/34 RobolectricにM1/M2/M3の自動検査、実TCPフレーム往復を追加。
実行結果、独立レビュー、PR、CI、マージ、署名確認、artifactは次回更新に記録。現時点では成功未確認。

## 環境
ローカルgit cloneは getaddrinfo() thread failed to start で失敗。gh認証はinvalid。
接続済みGitHub APIで取得・branch/commit/PR/CI追跡を行う。ローカルJDK/Android SDKは確認できず、CIで検査。
署名Secretsの一覧APIは未使用。実際のCI署名とキーストア証明書照合で有効性を確認予定。

## 配送
artifact名 ipmsg-debug。実APK ipmsg-debug.apk、SHA256SUMS、provenance.txt、signing.txt、badging.txtを収録予定。
ipmsg専用Drive固定先は未設定。署名APK完成まで継続し、利用可能な権限で新規専用配布先を検討する。
最新SHA/run/artifactを必ず追跡する。現時点ではAPK生成／配送未完了。

## 実機
Android6/7/8/14、Keystore、LAN、消灯、再起動の実機検証は全て未実施。
docs/TESTING.md の手順に従う。M3終了時に停止する。
