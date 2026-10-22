# M6-1 確定ファイル転送契約（2026-10-22）

本書は仕様とオフライン試験基盤。ファイル転送本体はM6-2で実装する。現行ネットワークへ新typeを送信しない。

## 互換・認証

TEXT/ACK v1とUDP/TCP42124の既存包絡を維持する。署名helloの任意caps=["file-transfer/1"]を能力広告に使い、承認後の暗号化CAPS要求/応答で再確認。能力欠落した旧版には拡張typeを送らず非対応を表示。相互承認済みの固定鍵だけを使用し、署名/from/toを毎フレーム検証する。

## メッセージ

すべてv=1/from/toを含む既存Protocol.seal包絡。transferId/fileIdは小文字RFC4122 UUID、再利用禁止。各宛先を独立転送する。

| type | 必須フィールドと意味 |
|---|---|
| CAPS | requestId,protocol=file-transfer/1,maxFiles=32,maxFileBytes=2147483648,chunkBytes=32768。双方上限の最小値 |
| FILE_OFFER | transferId,files:[fileId,name,mime,size,sha256]。最大32件/合計4GiB。承諾待ち |
| FILE_ACCEPT | transferId,selected:[fileId,offset,prefixSha256]。受信者が対象と保存先を明示選択した後だけ |
| FILE_CHUNK | transferId,fileId,seq,offset,data(Base64),sha256。生データ最大32768byte、seq=offset/32768、最終以外固定長 |
| FILE_CHUNK_ACK | transferId,fileId,nextOffset,prefixSha256。ディスク保存後のみ |
| FILE_RESUME | transferId,fileId,offset,prefixSha256。peer/offer全体hash/sizeと照合。不一致は新規一時ファイルでoffset0 |
| FILE_CANCEL | transferId,reason。終端。以後chunk拒否、再開禁止。再送は新transferId |
| FILE_COMPLETE | transferId,fileId,size,sha256。受信側で全体検証・fsync・確定を要求 |
| FILE_COMPLETE_ACK | transferId,fileId,sha256。確定成功後のみ。送信側保存完了の唯一の根拠 |
| FILE_ERROR | transferId,fileId（該当時）,code,retryable。未知peerへ応答しない |

OFFER→承諾待ち→転送中→検証→確定→完了。切断は一時停止、取消は終端。COMPLETE再送は同じhashなら再ACK。1TCP接続1要求/応答、フレーム80000byte、暗号化前JSON52000byte上限。offerが上限を超す場合batchを分割し各batchに新transferId。

## 暗号・再送・上限

各要求/応答/チャンクは既存RSA-OAEP SHA1/MGF1、AES256-GCM、RSA署名で独立保護。sealごとに新AES256鍵とランダム12byte IV。AES鍵を再利用しないため鍵ごとのnonce一意性を保証する。同じチャンク再送も新鍵/IV。seq/offsetをnonceに使わず鍵/nonceはresume状態へ保存しない。

相手ごと1チャンク、全体2相手。ファイル全体をメモリへ読み込まない。connect3秒/read5秒/総8秒、再試行10秒から最大5分の指数バックオフ。ACK前にoffsetを進めない。同じoffset/hashは再ACK、異なるデータ・飛び越し・負数・size超過は拒否。部分書込はACKせず切戻す。nextOffset/prefixHashを永続化して再開時照合。承認解除/取消を次write直前に再確認し、進行中処理との直列化を行う。

## 保存安全性・OS

受信者がbatchを明示承諾し保存先を選択。未承認/未承諾chunkは拒否。nameは表示名のみ。空名、dot/dotdot、slash/backslash/colon、NUL/制御文字、Windows予約名CON/PRN/AUX/NUL/COM1..9/LPT1..9（拡張子付き含む）、末尾空白/dot、255UTF8byte超を拒否。絶対/UNC/driveパス、symlinkで選択親外へ脱出を許可しない。MIMEはヒントのみ。APK等の自動実行/自動インストール禁止。

同名は上書きせずユニーク名。非公開一時ファイルへstream書込し、最終size/SHA256/fsync後に確定。同一volumeならatomic rename、非対応providerは確定失敗を明示。失敗時に最終名へ昇格しない。空き容量は未受信総byte+64MiB以上、書込時も不足を処理。0byteは空SHA256を検証。負数/非整数/上限超過を拒否。

Android6+はSAF OPEN_DOCUMENT/OPEN_DOCUMENT_TREEとContentResolver、永続URI権限を使用。URIを実パスへ変換しない。seek/rename/永続権限非対応providerは再開不可を示し新規保存を選ぶ。Windowsはファイル/保存先picker、非管理者書込権限、パス長、同名を考慮。送信元変更はSOURCE_CHANGEDで新offerが必要。

ERROR code: UNSUPPORTED,UNTRUSTED,DECLINED,INVALID_METADATA,INVALID_PATH,TOO_LARGE,NO_SPACE,PERMISSION_LOST,SOURCE_CHANGED,OFFSET_MISMATCH,HASH_MISMATCH,AUTH_FAILED,TIMEOUT,CANCELLED,IO_ERROR。AUTH_FAILEDは切断のみ。TIMEOUT/IO_ERRORは整合確認後のみretryable。他は利用者操作が必要。

## 基盤とM6-2試験計画

共通file-transfer-v1.jsonをAndroid/Pythonが読むオフライン契約試験を追加。暗号互換は既存双方向TEXT/ACKベクトルを継続。実ファイル送受信は未実装・未検証。

M6-2は画像/PDF/APK/ZIP/任意、0byte/2GiB/超過、32件/件数超過、改ざん署名/タグ/hash、切断/ACK喪失/再送/重複/順序不正、再起動/再開、取消競合、容量不足、SAF権限喪失/非seek、不正パス/予約名/衝突/リンク脱出、旧版相手を検査する。大容量はstream生成器でメモリ上限を測定する。
