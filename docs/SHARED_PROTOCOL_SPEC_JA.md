# 共有LAN通信プロトコル v1（Android現行実装の解析）
2026-10-16。正本コード: `app/src/main/java/com/xiaqui/ipmsg/Protocol.kt`、`LanEngine.kt`、`Store.kt`。**現行実装の記録であり将来の提案と混在させない。** AndroidからWindows/iOSへ互換実装する際はコードと突き合わせて更新する。

## 発見
- IPv4 UDP **42124** に約10秒間隔でブロードキャスト（255.255.255.255＋各NICの計算済ブロードキャスト）。LAN側はIPv4同一サブネット判定、VPN対象外。IPv6専用未対応。
- UTF-8 JSON packet: `{"body":"<JSON文字列>","sig":"<Base64署名>"}`。
- 署名対象body文字列: `{"v":1,"name":"<40文字まで>","key":"<RSA公開鍵X.509 DERのBase64>","time":<Unixミリ秒>}`。JSONのキー順や空白は**発信側が生成した文字列をそのまま署名検証**し、パースして再直列化しない。
- `SHA256withRSA` (RSA PKCS#1 v1.5)。公開鍵はRSA 2048-bit、X.509 SubjectPublicKeyInfo DERのBase64（改行なし）。端末ID/公開鍵指紋は `SHA256(publicKey.encoded)` の小文字64桁hex。発見データ最大4096byte。時刻の許容は受信時±120000ms。
- 初回は未承認候補。人が**双方の公開鍵指紋の全桁**を照合し各端末で承認。承認済み鍵を自動差し替えしない。発見で本文送信しない。
- `Peer.online` は `seen` から35秒以内。探索アドレス更新と「信頼済み」列の更新を分離。

## メッセージ
- IPv4 TCP **42124**。フレームはビッグエンディアン4byte符号付き整数のサイズ(1..80000)＋その長さのUTF-8 JSON。1接続にTEXT要求1件、同じ接続でACK1件。
- 包絡JSON `{"payload":"<JSON文字列>","sig":"<Base64署名>"}`。署名は送信者秘密鍵による **payload文字列そのもの**のRSA SHA256署名。
- payload文字列: `{"from":"<送信者公開鍵のSHA256 hex>","iv":"<12byte Base64>","key":"<RSA-OAEP暗号化AES鍵 Base64>","cipher":"<AES-GCM暗号文+タグ Base64>"}`。
- 新規AES-256鍵を各送信で生成。RSA/ECB/OAEPWithSHA-1AndMGF1Paddingで受信者RSA鍵にラップ。AES/GCM/NoPadding、ランダム12byte IV、128bit認証タグ。AAD指定なし。暗号文の末尾にGCMタグを含むJava Cipher形式。**OAEPのSHA-1とMGF1パラメータをPython/OpenSSL側で実装・相互検証すること**。
- 復号後のJSONに `v=1`, `from`, `to`（両者の鍵指紋）が入る。署名と送信者IDを信頼済み鍵で検証し、toが自端末IDであることを検証。
- TEXT平文JSON: `type:"TEXT"`, `id:"<UUID文字列>"`, `body:"<UTF-8本文>"`, `v:1`, `from`, `to`。本文は空白のみ不可、UTF-8で8192bytes以下。暗号化前JSONは52000bytes以下。
- ACK平文JSON: `type:"ACK"`, `id:"<同一message id>"`, `v:1`, `from`, `to`。ACK自身も受信者鍵で暗号化、ACK発信者署名付き。**ACKは受信DBへの保存完了を示し、開封/既読ではない。**
- message IDはAndroid側UUID.randomUUID().toString()で生成。受信判定は正規表現 `[0-9a-f-]{36}`（現行は厳密UUID構文ではない）。SQLiteの受信重複キーは(id,peer,incoming)。重複IDで本文が異なる場合は拒否。
- 受信側は保存後にACKを返す。送信はACK検証後にDELIVERED。未ACK分はPENDINGのまま、10秒×2^試行回数（最大300秒）で再送。オンライン相手の保留をtick毎に最大16件処理。
- 自分宛てはネットワークに出さずSQLite送受信に記録。

## 状態・エラー
- TCP connect timeout 3秒、read timeout 5秒、受信接続の総制限約3秒、送信接続総制限8秒。
- 受信は承認済みhostかつ同一サブネットでなければワーカー確保前に拒否。承認/公開鍵はStoreの登録に依存。
- 無効な長さ・署名・タグ・鍵・バージョン・宛先・本文・IDは拒否。期待するプロトコルエラー応答は定義されていない（接続は閉じられ、送信側で保留再送）。未知の拡張typeはv1では未対応。
- Android秘密鍵は Android Keystore の alias `ipmsg-identity-v1` のRSA2048署名/復号鍵。Windowsの鍵保管方式は未決定。DBは端末内SQLiteに本文を保存。

## クロスプラットフォーム互換テスト（未作成）
Python実装前に**固定テスト鍵はテスト専用**として、公開鍵DER→fingerprint、署名付きhello（正常/時刻超過/改ざん）、暗号包絡（固定AES鍵+IV/可変nonce）、TEXT/ACKフレーム、UTF-8、本文長、サイズ超過、重複ACK/再送を**AndroidとWindowsで相互に生成・検証**するテストベクトルを追加する。実運用鍵をテストに用いない。
現行実装はランダム鍵/IVを自動生成するため、固定ベクトル生成にはテスト用乱数・暗号注入ポイントが必要。**ベクトル未生成を「検証済み」と書かない。**

## 拡張に際しての制約
v1を後方互換性なく変更しない。v2を作る場合は発見・本文のバージョン交渉、旧端末への挙動と安全な移行を明文化する。旧IP Messengerとの互換は対象外。