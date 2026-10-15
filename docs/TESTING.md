# M0〜M3 検査・実機検収手順

## 自動検査
JDK17 / Gradle8.7: `gradle testDebugUnitTest lintDebug assembleDebug`
Robolectric SDK23/28/34で同じ検査を実行：
- M1: 署名付き発見、時計範囲、なりすまし拒否、暗号本文、両端認証、改ざん拒否。
- M2: 複数宛先独立状態、自分宛て重複除去、履歴再オープン、未承認拒否、UTF-8上限。
- M3: ACK欠落後の同一ID再受信、DB再起動後の重複排除、内容衝突拒否、承認解除。
- M3: バックオフ・オフライン・再送予約、実TCPソケットで暗号化TEXT→永続保存→認証ACK。
- フレーム長上限と中断されたフレームの拒否。LintでAPI23互換性を検査。

これは実Android端末、Wi-Fi電波、Android Keystore実装、OSスリープの検証とは異なる。

## 実機（未実施の項目を実施済みとしない）
対象：Z3TC Android6 / Z4T Android7 / XZ1 Compact Android8 / Ace III Android14。
1. APKを2台へ導入。端末名、受信開始、通知許可を設定。同じWi-Fi、端末時計自動設定。
2. 10〜30秒で双方に未承認が出る。承認前の送信ができず、相手端末の指紋全桁を照合して双方で承認。
3. A→B、B→A、日本語／絵文字／改行を送信。受信箱に1件、送信側は到着確認済み。返信が個別。
4. 自分宛てを機内モード・受信停止中に送信。送信／受信それぞれ1件。
5. 3台目を承認し、複数／全端末で対象名と数を確認。自分も1通。各宛先の到着が独立。
6. BのWi-Fiを切って35秒待つ。AでB宛てを送信して保留確認。アプリ再起動後も保留が残る。
7. BのWi-Fi復帰・IP変更後、再発見と再送、1件だけ到着。保留の他宛先に影響しない。
8. 送信中にWi-Fiを切りACK欠落を発生。復帰後も受信1件だけ。必要なら送信履歴の保留再送。
9. B側でAの承認を解除。A→Bは到着済みにならない。AでもB解除後は送信不可／保留送信停止。
10. ホーム／他アプリ／画面消灯10分・30分後の受信、通知許可拒否時、バッテリー最適化ON/OFFで差を記録。
11. 受信を有効にして再起動、アプリ更新、手動停止、強制停止後の再開を検査。
12. 端末間分離Wi-Fiで発見せず、既存保留を誤って到着扱いにしない。
13. 指紋不一致、未知送信元、TCP巨大長、署名偽造が本文保存・通知を起こさないことを確認。

## 通信・制約
IPv4 UDP/TCP42124。発見10秒、オンライン35秒、発見署名の時刻許容±2分。
本体はRSA2048/SHA256署名、RSA-OAEP(SHA1/MGF1)鍵包み、AES256-GCM。
秘密鍵はAndroid Keystore。承認は公開鍵ハッシュを固定し、再インストール時は再承認。
本文は端末内SQLiteに平文保存（アプリ専用、バックアップ無効）。ディスク暗号化・端末ロックはOSに依存。
発見で端末名と公開鍵をLANへ公開する。指紋照合なしの「承認」は安全を保証しない。
到着ACKは保存確認であり、開封・既読の意味を持たない。履歴を削除する機能はM3には設けていない。
自動発見はIPv4ローカルアドレスのみ。IPv6専用LAN・インターネット越し通信・従来IP Messengerとの互換は未対応。

## Android常駐の根拠
ユーザー操作で開始し、connectedDevice型FGSと継続通知を使用。
API26未満 startService、26以降 startForegroundService、29以降FGS型を明示。
API34以降 FOREGROUND_SERVICE_CONNECTED_DEVICE と CHANGE_WIFI_MULTICAST_STATE を宣言。
通知許可はAPI33以降にユーザー操作で要求。起動時の無断ダイアログ／無断省電力除外は行わない。
有効設定時のみBOOT_COMPLETED / MY_PACKAGE_REPLACEDで再開を試みる。強制停止・OS制御は迂回しない。
公式資料：
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://developer.android.com/about/versions/15/behavior-changes-15

FGSだけでDoze中の即時配達を保証しない。スリープ時の確証は実機検収まで未確認。
