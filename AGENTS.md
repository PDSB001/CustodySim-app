# Android repository instructions

- This repository contains the Android app only. Web/API implementation lives in CustodySim.
- Build from the repository root using the checked-in Gradle wrapper.
- Preserve application IDs, signing continuity, reading-position wire contracts, server permission checks and active-reading credit rules.
- Combined Android releases use AGPL-3.0-only; retain original MIT and third-party copyright/license notices.
- Update vendored Episteme through scripts/vendor-episteme-core.mjs and regenerate UPSTREAM.json; do not leave hashes stale.
- Never commit local.properties, credentials, signing keys, Gradle caches, APKs or private device artifacts.
- Build and publish matching corresponding-source archives with distributed APKs.
- Compile/unit validation does not establish real-device gesture or frame-time performance. Do not uninstall or clear app data for verification.

- Only debug/debugR8/production/release build types are supported. EPUB/DOCX always use Episteme; TXT/PDF retain their native engines. No WebView fallback or renderer flags.
- This worktree is the daily App development location inside the main project. Commit here; synchronize the separate CustodySim-app main checkout only before release using the main project's scripts/sync-app-release.ps1. Do not push without release authorization.
