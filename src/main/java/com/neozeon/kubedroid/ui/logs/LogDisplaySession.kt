package com.neozeon.kubedroid.ui.logs

/**
 * Spec 12 goal 7: log display toggles persist for the app session only —
 * process-lifetime statics (same pattern as [LogNavPayloads]), never
 * written to disk, reset by [reset] for tests.
 */
object LogDisplaySession {
    var wrap: Boolean = false
    var timestamps: Boolean = false

    fun reset() {
        wrap = false
        timestamps = false
    }
}
