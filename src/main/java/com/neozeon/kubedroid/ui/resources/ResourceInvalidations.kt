package com.neozeon.kubedroid.ui.resources

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Spec 06 "Post-mutation consistency": one process-wide tick bumped after a
 * successful mutation (delete today; edit already reloads its own detail).
 * Every [ResourceListViewModel] refreshes on tick, so a delete from the
 * detail pane/screen invalidates the list without any navigation plumbing.
 */
object ResourceInvalidations {
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    fun bump() = _tick.update { it + 1 }
}
