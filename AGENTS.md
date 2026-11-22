# ipmsg development guidelines

These instructions apply to `xiaqui/ipmsg`. The first active section of `TASK.md` defines current work. Product and protocol contracts live in the relevant documents under `docs/`.

## Start and scope

- Confirm repository, `origin`, latest `main`, open PRs, CI, and current task before editing. Do not repeat completed M0–M6 work or apply superseded milestone restrictions to a newer task.
- Preserve the established Android and Windows implementations. Do not regenerate from a template or modify another repository.
- Use a branch and PR for behavioral, protocol, storage, security, build/workflow, or multi-file changes. Review corrections remain on the existing PR.
- Do not start deferred platforms or integrations unless the active task explicitly authorizes them.

## Product invariants

- Maintain Android 6 (API 23) compatibility unless an explicit, evidence-backed decision changes it.
- Keep LAN-only peer-to-peer operation, approval of unknown peers, and the distinction between broadcast, self, individual, and multi-recipient delivery.
- Treat protocol compatibility as a cross-platform contract. Changes require Android/Windows regression coverage as applicable.
- Separate automated/source verification from device interoperability testing. Unperformed device checks remain unverified.

## Security and reliability

- Never commit or expose credentials, signing material, private URLs, device fingerprints, personal messages, or identifying logs.
- Verify signing from an actual successful build and package inspection; YAML references or a statement that secrets exist are not proof.
- When a write or file transfer may have been sent but the result is unknown, do not retry blindly. Reconcile state first to avoid duplicate delivery.
- P0/P1 review findings block merge. Fix P2 or record a reasoned disposition.

## Build and delivery

Run relevant Android/Windows tests, lint/build checks, protocol compatibility tests, and `git diff --check`. Follow this repository's current Release-based distribution workflow. Trace source SHA to run/release/asset, downloaded file, hash/signature, and device test. Do not use another product's fixed destination or confuse an artifact with a release asset.

## 検収用Releaseの工程順（2026-11-22確定）

- Android実機検収には、端末にインストールできる署名APKが必要。現在のipmsgでは署名Releaseを完成mainから配布するため、**自動テスト・レビュー → 承認済みPRマージ → mainから署名Release → 実機検収**の順とする。
- **実機合格を、検収用APKのマージやRelease公開の必須前提にしない。** 逆に、CI成功を実機合格やRelease配送完了と混同しない。製品の正式完了判定は実機検収後。
- マージ・配送はそのタスクで承認された範囲内のみ実行。承認済みなら実機未検収を理由にDraftへ戻して工程を循環させない。P0/P1未解決・CI失敗・署名不一致があればマージ/配送を止めて対処する。
- Releaseの成否はActions run、公開Asset、source SHA、hash、署名の実体で判断し、mainへ特定の公開トリガー付きpushをしただけで成功と記録しない。文書更新のみでReleaseを再実行しない。

## Progress

Update `docs/WORK_PROGRESS.md` with verified SHA/PR, tests, review, CI/release status, device checks, and remaining work. Historical handoffs are evidence, not current instructions.

