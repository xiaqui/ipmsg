# IP Messenger Windows（M5）

Windows 11向けのLAN内手紙クライアントです。Android版のプロトコルv1と同じUDP/TCP 42124、RSA-2048鍵指紋、署名、暗号化、ACK、重複排除、有界再送を使用します。クラウドやログインは使いません。

## 初回起動

1. GitHub ReleasesのM5 prereleaseから `ipmsg-windows-*.zip` と `SHA256SUMS.txt` を取得し、SHA-256を照合する。
2. ZIPを展開して `ipmsg-windows.exe` を起動する。現時点の実証ビルドはWindowsコード署名証明書が未設定のためAuthenticode未署名。SmartScreen表示時は発行元とReleaseのSHAを確認する。
3. Windows Defender Firewallでは、プライベートネットワークに限ってUDP/TCP 42124を許可する。パブリックネットワークには許可しない。
4. 相手端末を1台選び、双方の画面に表示された64桁の鍵指紋全文を別経路で照合してから双方で承認する。

秘密鍵はWindowsのDPAPI（現在のユーザー単位）で暗号化し、`%LOCALAPPDATA%\Xiaqui\IPMessenger` に保存します。承認は片側だけでは成立しません。VPN、ゲストWi-Fiの端末分離、ファイアウォールにより探索できない場合があります。VPNや認証を迂回する機能はありません。

## 開発

```powershell
cd windows
py -3.12 -m venv .venv
.venv\Scripts\python -m pip install -e . pyinstaller==6.11.1 ruff==0.9.6
.venv\Scripts\python -m unittest discover -s tests -v
.venv\Scripts\ruff check ipmsg_windows tests
.venv\Scripts\pyinstaller --clean --noconfirm ipmsg-windows.spec
```

M5はテキスト送受信までです。ファイル転送、重要便、iOSは含みません。実機のVAIO↔Android通信は別途確認が必要です。

