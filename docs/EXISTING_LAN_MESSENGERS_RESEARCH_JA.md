# 既存LANメッセンジャー比較調査（2026-10-18）

## 調査目的
自作 `xiaqui/ipmsg` のWindows版・ファイル転送開発に先立ち、GitHub公開の類似アプリの設計・互換性・ライセンスを比較する。外部README等の公開資料をもとにした**予備調査**であり、コード監査・実機動作確認は未実施。

## 比較対象

| プロジェクト | 主なプラットフォーム／性格 | 探索・通信と特徴 | ライセンス／注意 |
| --- | --- | --- | --- |
| [NUSV/Syna-NUSV](https://github.com/NUSV/Syna-NUSV) | Android、Windows、macOS。Kotlin/Compose Multiplatform | UDPブロードキャスト＋マルチキャスト探索、E2EE、チャット、ファイル転送、オンライン状態。サーバー不要のLAN P2Pを標榜。READMEにAIエージェント利用の明記あり | GPL-3.0-only。実装を流用する場合はGPLの派生物・配布条件を要確認 |
| [zyqg/feiq-android](https://github.com/zyqg/feiq-android) | Android 7.0+。Windows FeiQ/IPMsg互換を志向 | ポート2425のUDP探索／TCP転送、再接続、ファイル／フォルダー、連絡先、会話UI。既存Windowsクライアントとの互換を重視 | リポジトリのLICENSEと個々の資産の利用条件は別途確認。READMEにWindowsグループプロトコル未解明等の制限を明記 |
| [AlsatianConsulting/TransportChat-Android](https://github.com/AlsatianConsulting/TransportChat-Android) | Android専用、オフラインLANチャット | E2EE、相手認証、ファイル共有、音声／ビデオ通話。Signal Protocol、SQLCipher、WebRTC等を採用 | READMEに「All rights reserved」。公開コードでも無断転用可能とは限らない。ライセンス文面・依存関係を要精査 |
| [shirouzu/ipmsg](https://github.com/shirouzu/ipmsg) | 本家Windows版の公開ソース | 古典的なサーバー不要IP Messenger。プロトコル資料あり。Android/iOS版はREADMEによればソース非公開 | Windows版公開ソースはBSDライセンス。ただし現行最新版や他OS実装と同一とは限らない |

出典：各GitHubリポジトリのREADME、LICENSE、protocol文書（上記リンク）。確認日2026-10-18。

## 自作ipmsgとの相違

- 自作は「会話・チャット」よりも「宛先を選択して手紙を届ける」ことを優先する。全端末同報（自分を含む）、自分宛て、個別／複数宛先、到着ACK、保留・復帰後再送が中核。
- 実装はAndroid 6（API 23）以降を想定。XZ1 Compact／Rakuten Hand／Ace IIIで実機確認が進んでいる。
- 同一Wi-Fiの署名付きUDP HELLO＋承認済み公開鍵による暗号化TCPメッセージ。現行ポート42124。FeiQ／本家IPMsgの2425とは**現時点でプロトコル互換を保証しない**。
- 「開封するまで全画面を覆う封書」、開封確認、時限封書、家庭内放送、ファイル転送、Windows/iOSクライアントは将来要件。未実装機能を実装済みと混同しない。
- 他プロジェクトがチャットUI主体でも、そのUI設計を自作へそのまま採用しない。

## Windows版／ファイル転送に向けて見るべき点

1. **Syna**：AndroidとWindowsで通信・UI以外の共通部分をどう分離しているか、LAN復帰・切断判定、ファイルのチャンク化・進捗・再試行・受信ファイルの安全な保存を調べる。GPLなのでコードのコピペではなく設計上の参考を基本とする。
2. **FeiQ Android**：Windows側との探索・ファイアウォール対策、ポート2425、ファイル／フォルダー転送とAndroidのファイルアクセス権限を調べる。ただし自作42124・独自暗号化プロトコルへの直接互換を前提としない。
3. **本家IPMsg**：`protocol.txt` を読み、封書・開封・ファイル添付等の歴史的設計と、Windows通知・常駐時のユーザー体験を比較する。必要なら互換モードの可否を別途設計判断する。
4. **TransportChat**：強い暗号化・添付・常駐の構成を確認する。著作権・ライセンス条件が不明なコードは取り込まない。

## 決定事項／保留

- 現行ipmsgの「手紙」中心UIとAndroid 6対応を維持し、LINE風チャット画面へ置き換えない。
- 既存の相互承認、署名、暗号化、ACK、メッセージIDでの重複排除を維持する。既存コードの認証を省略して互換性を得ない。
- Windows版はユーザーの明示的な着手指示があるまで実装開始しない。
- 他リポジトリからの実コード移植は、著作権・ライセンス・依存物・商標・帰属表示を確認するまで行わない。
- 本比較は公開文書に基づく一次調査。性能、Android最低対応、相互接続可能性、セキュリティの優劣を実測で断定しない。
