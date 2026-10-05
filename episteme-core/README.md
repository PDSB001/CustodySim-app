# Episteme native reader core

Adapted from https://github.com/Aryan-Raj3112/episteme at the revision recorded
in `UPSTREAM.json`, under AGPL-3.0-only. Upstream copyright headers are retained.

This module contains the semantic HTML/CSS parser, rich block model, content
styler, native Compose measurement and pagination policies. It does not include
the upstream account, AI, cloud, library UI or application configuration.

CustodySim adaptations are recorded in the import manifest. The Android adapter
and MIUIX reading controls live in the app module. No WebView is required by
this core. This module's compilation is not proof that every publication format
or interaction is already migrated; see
[`docs/android-episteme-integration.md`](../../docs/android-episteme-integration.md).
