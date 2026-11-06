# Current task — Android UI polish (2026-11-07 JST)

## Scope and authorization

Next implementation task: improve the existing **Android** IP Messenger UI without changing LAN protocol, identity, encryption, message/file-transfer semantics, persisted user data, or Windows interoperability. This file records the task only; implementation, build, release, and device acceptance have not yet been performed.

M0–M6-2 and Release-label correction are already merged. Do **not** rerun those milestones. Inspect current main, AGENTS.md, open PRs/issues, source, protocol contracts, docs/WORK_PROGRESS.md, and delivery workflow before implementing. Use a feature branch and PR; review and test the final head. Do not claim merge, APK delivery, or device acceptance without evidence.

## Required changes (in order)

### 1. Receiver notification: pause/resume without opening the app

- Add an action to the Android ongoing foreground-service notification to stop/pause LAN reception, and a one-tap way to resume from a notification while stopped. Do not conflate transient service failure with user-requested pause.
- Make the receiver status and action text short enough for compact notifications; no reliance on the truncated current text `LAN受信を有効にしています。停止はアプリの設定から`.
- Keep the existing Settings receiver-enable switch in sync with notification actions. Specify clearly whether pause survives app restarts/device reboot: **manual pause must not silently auto-resume**. Restore reception only after explicit user action; preserve existing normal enabled-at-boot behavior for users who have not paused.
- Android may remove an ordinary stopped-service notification; design the resumed entry point accordingly, e.g. a safe persistent paused-state notification if feasible or another unambiguous one-tap entry. Do not promise a notification action that OS restrictions make impossible.
- Use explicit/immutable or appropriately mutable PendingIntent flags and non-exported components. Avoid background foreground-service start violations across supported Android API levels. If the notification action cannot safely restart directly on some API levels, open a clearly labeled resume UI instead.
- Do not interrupt in-flight message/file transfers silently; establish and test a deterministic cancellation/pause behavior with no duplicate sends or invalid delivered status.

### 2. Rename the file history entry

- On the Android main screen change the existing `ファイル転送` button label to exactly **`送受信ファイル`**.
- Keep its existing `fileTransfers()` destination, showing both sent and received files, pending transfers and completed-file actions. **Do not** replace it with a receive-only view.
- Retain `ファイルを選んで送信` as the actual sending entry point, unless a separately reviewed UX simplification explicitly changes only its label. Preserve its multi-file selection and destination validation.
- Update relevant dialog/accessible labels and UI tests for clarity where appropriate.

### 3. Live status in sent-message history

- While the sent-message history is visible, refresh each message's current status (pending/retry count vs authenticated delivered ACK) without requiring the dialog to be closed and reopened.
- Make status and message body visually distinguishable. Stop periodic updates when the UI is dismissed/paused; prevent leaks, selection loss, excessive DB reads, and stale callbacks. Do not claim a read receipt; existing ACK is *delivery*, not *opened*.
- Preserve per-recipient statuses and history copy/delete/reply workflows.

### 4. App icon

- Replace the generic Android application/launcher icon with an envelope **✉️-inspired** app icon aligned with the existing notification imagery, remaining recognizable on Android 6–10 and newer supported versions.
- Provide proper density resources or compatible adaptive/legacy resources as needed; do not use a text emoji glyph dependent on device font. Preserve notification small-icon readability and Android 6 compatibility.
- No new branding changes outside ipmsg.

## Validation and acceptance

- Run applicable Android unit/UI tests, lint, assemble, `git diff --check`, and CI for final PR head. Include regression checks for settings/notification pause-resume synchronization, boot behavior, action PendingIntents and API boundaries (especially 23/26/29/33+), history updates/dismissal, launcher icon packaging, and unchanged message/file-transfer behaviors.
- Confirm no Android–Windows protocol or storage incompatibility. No Windows implementation changes are authorized.
- Separate automated checks from actual physical device acceptance. Focus device checks on Z3TC Android 6, Z4T Android 7, XZ1 Compact Android 8, Rakuten Mini Android 9, Hand/other Android 10, as available; prioritize notification action behavior, restart, pause, background/screen-off, history refresh, and icon.
- Independent review: P0/P1 must be fixed before merge; fix or explicitly dispose of P2. Review must cover any security/performance/background-execution implications.
- Follow the current repository Release-based APK distribution process if a build is requested. Do not overwrite a fixed Google Drive APK or use another app's destination without explicit authorization. Verify final source SHA, tests, release asset hash/signature, and clearly report outstanding device tests.
- Update `docs/WORK_PROGRESS.md` with verified results and remaining work.

## Out of scope / backlog (do not implement in this task)

- M7: important sealed letters, opening acknowledgement, quick reply.
- M8: iOS native client.
- M9: household broadcast and timed sealed letters.
- Nearby Games invitation integration and Android/browser LAN bridge.
- Intermittent file transfer IO_ERROR, one-off Japanese-filename document picker exit, VPN/firewall interaction, and extended transfer/device combinations remain reproduction or device-verification items. Do not change the functioning transfer protocol without a reproducible issue and separately scoped task.
- Android home-screen quick-post widget is a future idea, not part of the present four-item UI task.
