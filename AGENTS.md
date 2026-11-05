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

## Progress

Update `docs/WORK_PROGRESS.md` with verified SHA/PR, tests, review, CI/release status, device checks, and remaining work. Historical handoffs are evidence, not current instructions.

