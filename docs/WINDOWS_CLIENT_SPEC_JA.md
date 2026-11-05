# Windows 11 client specification

The implemented Windows client interoperates with Android on the same LAN. Current verified SHA, CI, Release, and remaining device checks are recorded in `docs/WORK_PROGRESS.md`; the protocol authorities are `docs/SHARED_PROTOCOL_SPEC_JA.md` and `docs/FILE_TRANSFER_V1_CONTRACT_JA.md`.

## Required behavior

- Discover Android peers bidirectionally and use the same signed/encrypted protocol, peer fingerprint approval, ACK, retry, offline queue, and deduplication rules.
- Support individual, multiple-recipient, broadcast, and self destinations without treating multi-recipient delivery as a group chat.
- Store send/receive history in SQLite and preserve compatibility with the Android message and file-transfer contracts.
- Provide tray presence, notifications, optional startup, recovery after sleep/network changes, and explicit application exit.
- Keep GUI and communication core separated so shared fixtures and headless protocol tests can validate Android/Windows interoperability.

## Security and networking

- Restrict firewall guidance to private networks and require user approval for any rule changes.
- Protect private keys with an OS-appropriate secure mechanism; do not silently store plaintext secrets.
- Do not retry a write or transfer whose result is unknown until state is reconciled.
- Preserve authentication, encryption, path validation, file limits, and explicit receive consent.

## Acceptance

1. Windows and Android discover each other and complete fingerprint approval.
2. TEXT/ACK works in both directions, including offline/retry and duplicate handling.
3. File transfer works in both directions with consent, progress, integrity verification, cancellation, and recovery.
4. Tray, notification, startup setting, SQLite restore, and single-instance behavior pass relevant checks.
5. Automated core/fixture tests and Windows packaging checks pass; device interoperability remains separately reported.

## Build and delivery

Use the existing Windows workflow and direct GitHub Release path described in `docs/WINDOWS_BUILD_DELIVERY_JA.md`. Verify source SHA, run, release asset, re-downloaded size/SHA-256, archive contents, and executable version. Windows signing/Defender checks must be reported as verified or unverified. Do not use Android signing material or an Android delivery destination.

