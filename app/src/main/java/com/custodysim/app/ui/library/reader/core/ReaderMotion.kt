// SPDX-License-Identifier: AGPL-3.0-only
package com.custodysim.app.ui.library

/** An unmounted Compose scroll state can wait forever for its first layout. */
internal suspend fun stopActiveReaderMotion(mode: String,
    stopPaged: suspend () -> Unit, stopContinuous: suspend () -> Unit) {
    if (mode == "paged") stopPaged() else stopContinuous()
}
