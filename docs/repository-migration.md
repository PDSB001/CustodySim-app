# Migration record

- Remote: https://github.com/PDSB001/CustodySim-app.git
- Original repository: https://github.com/PDSB001/CustodySim.git
- Original commit: 44ac161 (Android subtree split: cc0b84d02e756faae4a09e18b31f7cad6a44af24).
- Android history retains original authors and messages. Necessary helper scripts and Android documents were imported with the current migration snapshot.
- Uncommitted Android changes from the source working tree were included, including the native reader, caches, license UI and loading regression correction.
- Gradle now builds at this repository root. Source packaging, fixture/profile scripts and About source URL were updated for this repository.
- Independent development APK, 30 library unit tests and corresponding-source ZIP built successfully on 2026-10-05.
- Existing app-wide lint findings and real-device stabilization checklist remain follow-up work; no new release-performance claims are made by this migration.
- Private local.properties was retained only as an ignored local build configuration; it and signing materials are excluded from Git and source packages.
- Application IDs and signing configuration were retained. Original checkout has not been deleted or its history rewritten.
