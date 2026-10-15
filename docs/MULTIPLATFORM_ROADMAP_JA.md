# マルチプラットフォーム開発計画（2026-10-16）
優先: **Android → Windows → iOS**。既存Android 6.0対応とM0〜M3の機能は維持し、共通プロトコルで相互運用する。既存のM0〜M3はAndroid実装・CI・署名・Drive配送済み（PR #2、SHA 57e627dd03edde76f53be10f178be74088ada38f）。実機未検証。APKを再配送しない。

| マイルストーン | 対象 | 着手条件 |
|---|---|---|
| M0〜M3 | Android基盤・基本通信・常駐 | 実装/配布済み、実機検収が残る |
| M4 | Windows設計、現行v1通信仕様整理、共通ベクトル、配布設計 | 今回は**文書作成のみ**。Android実機結果を待ち、未確認事項を明記 |
| M5 | Windows基本クライアント（トレイ、相互通信） | Android2台の基本通信合格＋ユーザーの別途明示指示 |
| M6 | Android↔Windowsファイル転送 | M5実機互換・共通転送仕様確定 |
| M7 | 重要便（開封まで覆う）・開封確認・簡単返信 | 通信互換と安全設計の実機検証 |
| M8 | iOS版 | Windows版安定後・別途指示 |
| M9 | 家庭内放送・時限封書 | マルチ端末の安定通信後 |

Nearby Games招待連携は保留。仕様追加や前倒しはユーザーの指示を受けて行う。
詳細: ANDROID_DEVICE_ACCEPTANCE_JA.md、SHARED_PROTOCOL_SPEC_JA.md、WINDOWS_CLIENT_SPEC_JA.md、FILE_TRANSFER_SPEC_JA.md、IOS_FUTURE_PLAN_JA.md、WINDOWS_BUILD_DELIVERY_JA.md。
重要: プラットフォーム別の実装・テスト・独立レビュー・マージ・配布・実機成功を別々に報告。