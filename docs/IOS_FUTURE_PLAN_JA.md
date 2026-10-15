# iOS版将来対応（今回実装しない）
iPhone 13 miniを想定。会社のApple Developer Program加入はユーザー申告。配布権限・加入チーム・証明書は着手時確認する。
Windows版安定後、SwiftUI/Network.framework/Bonjour/ローカルネットワーク許可等を比較。Android現行v1を互換仕様の出発点とし、共通テストベクトルでAndroid・Windowsとの暗号/フレーム/ACK相互運用を保証する。
iOSは一般アプリのLAN常時待受/バックグラウンド任意ソケット通信が制限される。Foreground時の相互通信を最低目標とし、通知・バックグラウンド受信は利用可能なAPIと権限制約を検証して明記する。LAN限定・外部サーバー不要という条件を維持できない常時Push機能を既定仕様にしない。
TestFlightまたはAd Hocを検討。どちらも規約/資格/端末制限に従う。**今回はiOSコード/配布設定を変更しない**。