# IP Messenger

Android 6.0 (API 23) 以降の家庭内LANメッセンジャー。既存の xiaqui/android 由来の構成を使用しています。

## M0〜M3の機能
- 同じWi-Fi/LANで自動発見（IPv4 UDP/TCP 42124）。中央サーバー・アカウント不要。
- 双方で公開鍵のSHA-256指紋を全桁照合して承認。承認前の本文受信を拒否。
- 個別／複数同報、目立つ「全端末」、常設の「自分宛て」。送信前に宛先一覧と台数を確認。
- AES-256-GCM暗号化＋RSA署名、Android Keystoreで端末秘密鍵を保管。
- SQLiteに履歴と保留キューを保存。受信保存後の認証済みACKで「到着確認済み」。
- ACK欠落時の再送、永続IDによる重複排除、Wi-Fi再接続後の発見、承認解除。
- ユーザーが設定で開始する常駐受信。通知からアプリへ復帰。停止操作と省電力設定案内。

重要便・開封確認・ファイル・音声・時限封書・Nearby Games連携は未実装。製品要件は TASK.md に保持。

## 使い方
1. 2台とも同じ通常Wi-Fiに接続し、設定で端末名を変更して「常駐受信を開始」。Android 13以降は到着通知を許可。
2. 約10秒待ち、未承認端末をタップ。相手の設定に表示される指紋と全桁を直接照合。双方で承認する。
3. 端末を選択、または「全端末」「自分宛て」で本文を入力し、宛先確認後に送信。
4. 到着は受信端末の保存ACKで判定。オフライン宛ては保留となり、相手復帰時に順次再送。
5. 返信は受信箱の手紙から個別宛先を選択。グループスレッドは作らない。

相手端末で承認されていない場合も保留のままです。承認解除した宛先の保留は送信しません。再承認すると再送対象になります。
自分宛てはネットワークも受信サービスも不要です。全端末は承認済みのオフライン端末＋自分を含み、未発見・未承認端末への配達は約束しません。
ゲストWi-Fiの端末間分離、ファイアウォール、端末時計のずれ（2分以上）は発見に影響します。IPv6専用LANは対象外です。
再起動後は受信を有効にしていた場合のみ復帰を試みます。強制停止後はアプリを開いて受信を再開してください。OSの省電力・Doze・メーカー制御により消灯中の即時受信は保証しません。

## ビルド
JDK 17、Gradle 8.7、Android SDK 35を使用：
`gradle testDebugUnitTest lintDebug assembleDebug`

applicationId/namespace: `com.xiaqui.ipmsg`、versionCode=3、versionName=0.3.0、minSdk=23、targetSdk=35。
CIは登録済みの4署名Secretsを必須とし、APK署名の証明書ダイジェストをキーストアと照合します。
GitHub Actions artifact `ipmsg-debug` に `ipmsg-debug.apk`、SHA256SUMS、provenance、署名／package検査結果を収録。
配送の追跡と実機未検証事項は [docs/WORK_PROGRESS.md](docs/WORK_PROGRESS.md)、手順は [docs/TESTING.md](docs/TESTING.md) を参照。
ipmsg専用のGoogle Drive固定配送先は未設定。他アプリのAPKは上書きしません。
