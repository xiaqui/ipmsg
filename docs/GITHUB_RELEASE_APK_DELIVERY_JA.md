# GitHub Releasesへの署名APK直接添付手順

更新: 2026-10-17 JST

GitHub Actions Artifactの容量制限に依存せず、同一ジョブでAndroidの検査、署名APK生成、GitHub Release添付、再取得照合まで完結させる共通手順。最初は必ず `prerelease` とし、Google Drive等の既存配布先は別工程で明示的に扱う。

## 適用条件

- 既存のunit test、Lint、assemble、署名設定を変更せず前段で成功させる。
- `permissions: contents: write` を明示し、Release作成時だけ `GITHUB_TOKEN` を `GH_TOKEN` として使う。
- Keystore、パスワード、tokenをRelease、ログ、provenanceへ書かない。
- pull requestではReleaseを作らない。mainの明示的な手動実行、または一度限りの識別済みpushだけで作る。
- APKのpackage、minSdk、署名証明書、SHA-256をRelease作成前に検査する。

## 共通フロー

1. 既存コマンドでunit test、Lint、assembleを実行する。
2. `apksigner verify --verbose --print-certs` を実行し、Keystoreから算出した証明書SHA-256と一致させる。
3. `aapt dump badging` でapplicationIdとminSdkを照合する。
4. 配布名へコピーし、`SHA256SUMS` とsource SHA/run IDを含む `provenance.txt` を作る。
5. `gh release create --prerelease` にAPKと検査記録を直接渡す。`actions/upload-artifact` は経由しない。
6. 同じジョブで `gh release download` し、再取得APKのサイズとSHA-256をローカル生成物に一致させる。
7. Release ID、tag、asset ID、source SHA、run ID、APKサイズ、SHA-256を進捗書へ記録する。

追跡鎖は `source SHA → Actions run ID → Release ID/tag → APK asset ID → 再取得APK → SHA-256/署名` とする。ReleaseのZIP/Source code archiveをAPKと誤認しない。

## リポジトリごとの置換箇所

- APK出力パスと配布ファイル名
- applicationId、minSdk、最低テスト件数
- Release tag/titleの接頭辞
- 署名Secrets名（値は共通化・記録しない）
- Releaseを許可するbranch/event条件

## テストReleaseと本番配送

- 初回は一意なtagのprereleaseを作り、APK assetを再取得して照合する。
- テストReleaseのURLを固定配布URLとして扱わない。
- Google Drive固定ID等の既存配布先は実証中に変更しない。本番配送する場合はRelease assetを取得し、同じSHA-256を確認してから別途更新する。
- 失敗したprereleaseやtagを削除する場合は、成功記録と参照先を確認してから `gh release delete <tag> --cleanup-tag` を使う。自動削除しない。

## 失敗時の判定

- build/test/Lint/署名失敗: Releaseを作らず修正する。
- Release権限失敗: `contents: write` とリポジトリのActions権限を確認する。tokenを追加Secretとして保存しない。
- 添付後のサイズ/SHA不一致: 配送禁止。Releaseをテスト失敗として扱い、原因修正後は新しい一意tagで再実行する。
- GitHub Releases自体の制限: Actions Artifactへ黙って戻さず、進捗書へ停止点を記録する。
