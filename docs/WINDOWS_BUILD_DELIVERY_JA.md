# Windows版ビルド・配送計画（実行前ドラフト）
**Windows版未実装。今回の作業ではビルドも配布も実施しない。**
技術選定後、GitHub Actions `windows-latest` に依存バージョンを固定し、プロトコルテスト/GUI起動試験/署名・マルウェア対策の確認を行う。
PySide6採用ならPyInstaller成果物(EXE/zip またはインストーラ)のサイズ、必要ランタイム、署名、Defender誤警告の実機検証。MSI/MSIXなども候補。Windows起動時の自動起動はユーザー許可制。
コミットSHA→workflow run ID→artifact ID→成果物→Windows専用のDrive固定ID→再取得サイズ/ハッシュ一致で配送確認。**既存のAndroid版Drive ID 1QFFoHsyjE6MmXe023jpEzW29KMTX4oLNも、Nearby GamesのIDも使わない。**
新規配布先が確定するまで別アプリのファイルを上書きしない。デバッグ成果物と一般配布版を混同しない。
共通参照: xiaqui/wolf-chess/docs/APK_ARTIFACT_DELIVERY_GUIDE_JA.md, APK_DELIVERY_JA.md, BUILD_DELIVERY_COMPLETION_LOOP_JA.md（APK固有部分はWindows用に読み替え）。