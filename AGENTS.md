# AGENTS.md — ipmsg 自走開発規約

このファイルは Work / Codex / その他の開発エージェント向け。**作業開始時に必ずルートの TASK.md を読み、そこに記載された今回の実行範囲を遵守する。** Work でもリポジトリの指示文書として参照可能だが、ツールが自動で常に読み込む保証はないため、TASK.md から明示参照する。

## 優先順
1. ユーザーの最新指示および TASK.md の冒頭「次期開発指示」（旧M0〜M3指示より優先）
2. 本 AGENTS.md の運用ルール
3. TASK.md の製品仕様・受入条件
4. docs/ の関連技術資料、既存コード

## 作業スタイル
- 対象リポジトリは **xiaqui/ipmsg**。既存の **xiaqui/android** テンプレートから作成済み。再生成や別リポジトリ化は禁止。
- まず現状のファイル、CI、未完了PR・コミットを調査し、作業の重複/上書きを避ける。
- 自律的に実装→テスト→PR/レビュー→P1/P2修正→再テスト→条件を満たせばマージ→APKビルド/配送→結果確認まで進む。可能な操作は人に差し戻さない。
- Work 自身の自己レビューだけで合格判定しない。利用できれば GitHub Copilot 等の独立レビューを受け、対象コミット、未解決指摘、CIを確認する。レビューを実行できない場合は**独立レビュー未実施**と記録し、合格したふりをしない。P1以上の未修正はマージ禁止、P2も修正または根拠つき判断を残す。
- フェーズ間の作業範囲は**TASK.mdの現行「次期開発指示」**で判定する。M0〜M3は完了、今回の指示は次期計画と文書整備のみ。ただし実機でしか検証できない事項は未検証として明記し、仮想環境の結果と混同しない。
- 実行途中で障害・使用制限に当たっても、解決可能なら調査・修正して再開。再開できない場合は再現方法と残作業を docs/WORK_PROGRESS.md に残す。

## 不変の製品要件
- Android 6 (minSdk 23) 〜 Android 14を対象にし、API 23で使えないライブラリ/Android APIを無条件に導入しない。ビルドだけでなくAPI互換性を検証。
- LAN内P2Pで動作、外部サーバー/ログイン不要。知らない端末からの受信を勝手に許可しない。
- トップの**「全端末」**と**「自分宛て」**を維持。個別、複数同報はグループチャットではない。
- 重要便は開封まで全画面封書を維持する予定。ただしAndroidの安全保護/ロック画面/システム操作を迂回しない。今回M3までのスコープ外なら先走って実装しない。
- Nearby Games招待連携は**保留**。双方のリポジトリに変更しない。
- 仕様変更・機能削除・対応OS切り捨てが必要なら勝手に実行せず、根拠と代替案を文書化する。

## セキュリティ・署名
- Secrets の値、鍵、トークン等をログ、コード、ドキュメントへ出さない。
- 既存Workflow は KEYSTORE_BASE64、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD を必須とする。**存在は設定画面またはCIの成功結果で確認し、YAML参照だけをもって設定済みと断定しない。**
- applicationId、namespace、アプリ名、versionCode、署名の一貫性を管理。テスト用/配送用のAPK混同を防止。
- Google Drive配送は ipmsg 固有の配送先が確定するまで、Nearby Games の既存固定ファイルIDへ絶対に上書きしない。
- 配送手順はまず **xiaqui/wolf-chess/docs/** の資料を読み、共通手順を **ipmsg固有に置換**する。少なくとも:
  - `docs/APK_ARTIFACT_DELIVERY_GUIDE_JA.md`（正本、SHA/run/artifact追跡）
  - `docs/APK_DELIVERY_JA.md`（誤配送例・サイズ/ハッシュ照合）
  - `docs/BUILD_DELIVERY_COMPLETION_LOOP_JA.md`（自走完走ループ）
  - `docs/CODEX_AUTONOMOUS_DELIVERY_JA.md`（参考）
  当該リポジトリにアクセス不能なら、その事実を記録し、既知手順の推測だけで配送済みとしない。
- commit SHA → Actions run ID → artifact ID → ZIP展開 → APK本体 → ipmsg専用配布先 → 再取得確認を徹底。古いartifactとZIP本体を誤配送しない。

## 進捗と完了報告
- **docs/WORK_PROGRESS.md** を作成/更新して、各マイルストーンの進捗、実装SHA、PR、テスト、レビュー、Actions run/artifact、署名確認、APK配布、未検証の実機項目、次の作業を記録する。
- 完了と未完了を区別し、実機未検証なら未検証と記す。
- M0〜M3の実装・署名APK配送は完了した。新たな配送や旧タスクの繰り返しをしない。Android実機で相互通信が成功し、ユーザーが別途Windows実装を明示指示するまで **Windows版コード着手禁止**。Nearby Games連携も保留。

## 2026-10-16 次期マルチプラットフォーム方針

- 優先 Android→Windows→iOS。Android 6.0サポート維持。旧M0〜M3実装の進捗、Copilotレビュー、配送記録は消さない。
- main SHA `57e627dd03edde76f53be10f178be74088ada38f`、PR #2 merged、run `38761412863`、artifact `11986061803`、Drive配送完了。**APK再配送禁止（ユーザー確認済み）**。実機検証と最終差分の独立再レビューは未完了。
- 新規文書: `docs/ANDROID_DEVICE_ACCEPTANCE_JA.md`、`docs/SHARED_PROTOCOL_SPEC_JA.md`、`docs/WINDOWS_CLIENT_SPEC_JA.md`、`docs/FILE_TRANSFER_SPEC_JA.md`、`docs/MULTIPLATFORM_ROADMAP_JA.md`、`docs/WINDOWS_BUILD_DELIVERY_JA.md`、`docs/IOS_FUTURE_PLAN_JA.md`。正本のAndroidコードにない事実を推測してプロトコル仕様に追加しない。
- Python+PySide6はWindowsクライアント候補。選定前に他方式やWindows秘密鍵保護/起動時常駐/配布を比較する。
- M4設計のみ実行可能。M5以降のWindows実装はAndroid実機双方向通信成功とユーザー別途明示指示が必要。iOSはさらに後。
