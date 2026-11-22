# アプリ使用中のLAN通信と常駐受信

2026-11-22。対象はAndroid版。プロトコル、保存スキーマ、Windows版は変更しない。

## 所有権と状態遷移

`LanRuntime` がプロセス内で一つの `LanEngine` を所有する。MainActivityの表示中かつ画面ON・ロック解除済みの期間と、常駐受信ONのMessengerServiceを独立した所有者とする。Activityインスタンスごとのtokenで管理し、古いActivityの終了が新しいActivityの所有権を消さない。

| 常駐設定 | 画面状態 | 通信 | 永続常駐通知 |
| --- | --- | --- | --- |
| OFF | 表示中・画面ON・ロック解除済み | 探索・本文・ファイル・保留再送 | 受信サービス通知なし |
| OFF | 非表示・消灯・ロック（ホーム、他アプリ、終了） | 新規通信を停止、保存処理を完了して資源解放 | 手動一時停止時の再開操作用通知のみ |
| ON | 表示中 | 同じエンジンで通信 | 既存の常駐通知 |
| ON | 非表示・消灯 | サービス所有で通信継続 | 既存の常駐通知 |
| OFF | 端末再起動 | 開くまで通信なし、OFFを維持 | 手動一時停止時のみ再開操作を表示 |
| ON | 端末再起動 | 既存BootReceiverによるFGS開始を試行 | OS制約に従う |

- 画面内の設定・履歴ダイアログはActivityを非表示にしない。外部ファイル選択や外部表示中はOFFで画面が非表示なら停止し、戻ると再開する。通信のために常駐設定をONへ変更しない。
- `onStart`/`onStop`と画面ON/OFF・ロック状態で所有権を増減し、`onDestroy`でも解放を保証する。分割画面でpauseされても表示中なら継続する。
- 所有者が最後に消えた時点でUDP/TCP待受・接続を閉じ、タイマー・送信・受信ワーカーを停止する。新しい所有者が戻っても、古いワーカーとファイル最終保存が終了するまで再bindしない。
- 停止中の送信は認証済みACKなしに到着扱いにしない。保留キューと受信済みファイルのoffset/hash/一時ファイルは維持し、次回は既存の再送・重複排除・FILE_RESUMEを使う。完了済みデータのSAF書き出しは通信停止後も完了させる。
- 停止完了時に発見時刻を失効させ、画面上もエンジン未稼働中はONLINEを出さない。再起動後の発見は実際の署名HELLOに基づく。
- 通知一時停止はサービス所有権だけを解放する。画面が表示中・画面ONなら通信を継続し、画面を離れた時点で停止する。
- 初期化・停止待ちは専用の直列workerで実行する。画面専用通信のためのService/FGS起動はしない。既存のON時だけFGSを使う。

## 自動試験と実機の境界

`LanSessionControllerTest`は所有権の共有、停止待ちと再生成の競合、ON/OFF連打、初期化中の画面離脱、初期化失敗後の再試行を検査する。`ForegroundLanTest`はAPI23/26/28/29/34でサービス・通知一時停止・boot、実エンジン停止とポート再利用、保留本文と部分受信ファイルの再開を検査する。`ForegroundLanTransportTest`は実Activityライフサイクルと同一runner内の署名HELLO/暗号化TEXT/認証ACK/保留再送/部分受信後のFILE_RESUMEを通す（Android NetworkのルーティングだけをJVM用に置換。製品がloopback端末を拒否する仕様に合わせ、受信はrunner自身の非loopback IPv4を使用）。既存の本文/暗号/ACK/重複排除/ファイル転送・Android/Windowsベクトル試験も維持する。

実機ではAndroid6/7/8/9/10の必要な組み合わせで、OFF表示中の双方向本文・ファイル・別端末からの探索、ホーム/消灯後の不受信、復帰時の再探索・再送、通知一時停止、ONでの背景受信、Wi-Fi復帰、転送中の外部ピッカー遷移を確認する。自動試験は無線・OEM省電力・実機相互運用の合格を意味しない。

## OS資料

- [Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle)：resumed/pauseによる利用中資源の所有権。
- [Android 8 background execution limits](https://developer.android.com/about/versions/oreo/background)：画面専用通信のためにバックグラウンドServiceを作らない。
- [FGS background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)：常駐ONの既存明示操作・boot経路を維持し、OFFではFGSを起動しない。
