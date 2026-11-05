# Windows build and Release delivery

The Windows client is implemented. Current verified SHAs, CI runs, releases, assets, and remaining device checks are recorded in `docs/WORK_PROGRESS.md`.

## Build

Use the repository's existing Windows workflow. Pin dependencies and run protocol/core tests, GUI or smoke checks, Ruff, PyInstaller packaging, and `--version` verification as configured. Keep Windows packaging separate from Android signing material. Code-signing and Defender behavior must be reported as verified or unverified rather than assumed.

## Release

The current distribution path publishes the packaged Windows ZIP/EXE directly to a GitHub Release from the approved source SHA; it does not depend on Actions artifact storage. Verify:

1. final source SHA and workflow run;
2. release/tag and exact asset name;
3. re-downloaded file size and SHA-256;
4. archive contents and executable version;
5. any available Windows device/smoke result.

Do not use an Android delivery destination for Windows files. Any external fixed destination requires separate repository configuration and authorization; its ID, URL, and credentials must not be committed.

A successful build, published Release, external delivery, and Windows/Android interoperability acceptance are separate completion states.

