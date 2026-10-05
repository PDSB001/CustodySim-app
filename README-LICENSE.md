# Android distribution licensing

The Android application includes adapted Episteme Reader code. The combined
Android application is distributed under **GNU AGPL version 3 only**; see
`LICENSE`. This applies to the application as a whole, not merely the reader
module. Separating Gradle modules does not remove these obligations.

Original CustodySim files retain their MIT grants and copyright notices; a copy
is in `LICENSES/CustodySim-MIT.txt`. Episteme's notices remain in its source files.
`episteme-core/UPSTREAM.json` records the exact upstream revision, imported paths,
original hashes, local hashes and modifications. No commercial license or
licensing exception has been obtained. The upstream contributor agreement is
not such an exception for this project.

The separately maintained CustodySim Web application and server remain under their MIT license.
They do not incorporate Episteme code. Third-party dependencies retain their
own licenses; none are relicensed by this document.

## Distributing APKs

For each distributed Android build, provide its complete Corresponding Source,
including local adaptations and the scripts needed to build it. An upstream
Episteme link alone, or an old CustodySim source revision, is insufficient.
Run `./gradlew androidCorrespondingSource` from this repository root and publish the ZIP
alongside the APK, with the same access and no additional charge. Verify that
the ZIP corresponds to that APK and contains the required dependencies' source
or instructions to obtain the exact versions under their applicable licenses.
Do not include signing keys, account data or private server configuration.

The task selects the Android source directories, build scripts, Gradle wrapper,
version catalog, licenses and import provenance explicitly; local Gradle homes
and inspection checkouts are not included. Dependencies are resolved from their
pinned coordinates in `gradle/libs.versions.toml`. The reader's new dependencies'
license originals and source URLs are included in `app/src/main/assets/licenses`.
The reviewed source is already vendored, so a normal Android build does not need
the upstream inspection checkout or the import script to run.

Do not describe the combined Android application as MIT-only. Preserve the
AGPL text, attribution and access to source when redistributing modified builds.
