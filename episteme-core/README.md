# Episteme native reader core

Adapted from [Episteme](https://github.com/Aryan-Raj3112/episteme) at the revision recorded
in [UPSTREAM.json](UPSTREAM.json), under AGPL-3.0-only. Upstream copyright headers are retained.

This module contains the semantic HTML/CSS parser, rich block model, content
styler, native Compose measurement and pagination policies. It does not include
the upstream account, AI, cloud, library UI or application configuration.

CustodySim adaptations are recorded in the import manifest. The Android adapter
and MIUIX reading controls live in the app module. No WebView is required by
this core. The app currently uses this engine for EPUB/DOCX in all four build types;
TXT and PDF use their own native engines. Compilation alone does not establish
real-device rendering or gesture quality. See the current
[reader and cache guide](../docs/reader-cache.md) and the historical
[integration record](../docs/android-episteme-integration.md).
