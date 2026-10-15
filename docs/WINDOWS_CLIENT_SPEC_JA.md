# Windows 11 クライアント仕様・設計指示
対象: VAIO (Windows 11)。**設計のみ、実装開始は実機でAndroid間通信成功＋ユーザー別途指示の後**。

## 目的・必須機能
同一LANでAndroidと相互発見し、**同一のv1通信プロトコル**、双方指紋承認、RSA署名/AES-GCM暗号、個別送信・複数独立同報・自分宛て、SQLite送受信履歴、保存後ACK、保留/再送/重複排除を実現する。Windows通知、タスクトレイ常駐、Windows起動時の自動起動を設定で切替可能にする。トップに「全端末」「自分宛て」。

## 技術候補と決定ゲート
- **Python + PySide6**: UIとトレイ通知、開発速度、既存のPython経験には適合。ただしcryptographyでのRSA OAEP SHA1/MGF1とJava互換、PyInstaller等の配布容量/AV誤検知/更新、起動時常駐の信頼性を比較する。
- **.NET / C# (WPF/WinUI 等)**: Windows通知・起動時常駐・署名配布の統合が容易になり得る。暗号APIと互換検証が必要。
- GUIと通信コアを分離し、headlessなテストでAndroid v1プロトコルとの互換を先に確認。開発者が比較表、試作コスト、セキュリティと配布方式を提示して採用を決めるまで、Python+PySide6は**候補**。
- P2Pで中央サーバー不要。Windows FirewallのUDP/TCP42124は**プライベートネットワーク限定**の許可を案内し、可能ならインストーラ設定はユーザー承認制。公衆ネットワークに自動開放しない。
- Windows側の秘密鍵はOS保護ストレージ(DPAPI/CNG等)を候補として比較。平文の秘密鍵ファイルを無警告で置かない。

## 状態・UI
発見済み/未承認/承認済み/オフライン、fingerprint全桁表示と相互確認、個別・全端末・自分宛て、送受信履歴、PENDING/DELIVERED。ACK≠既読。
タスクトレイの開く/受信ON-OFF/終了を用意。通知クリックで該当メッセージ、二重起動禁止。Windows起動時自動起動は明示的opt-in。
OS復帰/スリープ・ネットワーク再接続・IP変更時に探索/再送を回復。ユーザーがアプリを終了したら通信停止する。

## 受入条件
1. Android実機2台の基本相互通信が成功。
2. Windowsから署名helloを送信してAndroidが候補に表示、逆方向の発見も確認。
3. 指紋相互承認後、A→W/W→A TEXTと暗号化ACKを検証。
4. Windowsタスクトレイ/通知/起動時設定/SQLite復元/再送/重複受信防止。
5. 自動テスト+共通プロトコルテストベクトルと、実機Android-Windowsの双方向LAN試験。

## 開発/配送（計画のみ）
GitHub Actions windows-latestで依存固定→ユニット/互換テスト→パッケージング→artifact。PySide6採用時はPyInstaller onedir/onefile、Windows Defender警告、Code Signing要否を評価。署名証明書をAndroid keystoreから転用しない。配布先は **Windows版専用**ファイル名・固定Drive IDを発行/確認後に使用。対象commit SHA→run→artifact→実ファイル→Drive更新→再取得比較の追跡を採用。**Windowsインストーラ/EXEの生成・配送は今回はしない。**