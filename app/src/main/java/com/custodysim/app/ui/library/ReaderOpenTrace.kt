package com.custodysim.app.ui.library

import android.os.Build
import android.os.Trace

/** A book click to first committed readable page, independent of the app startup metric. */
internal object ReaderOpenTrace {
    private var book: String? = null
    fun start(id: String) {
        book?.let(::finish)
        if (Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) {
            book = id
            Trace.beginAsyncSection("ReaderBusinessOpen", 1)
        }
    }
    fun finish(id: String) {
        if (book != id) return
        book = null
        if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection("ReaderBusinessOpen", 1)
    }
}
