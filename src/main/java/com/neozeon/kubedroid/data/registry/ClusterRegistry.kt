package com.neozeon.kubedroid.data.registry

import com.neozeon.kubedroid.data.model.ActiveSelection
import com.neozeon.kubedroid.data.model.ClusterConnection
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistence for connection metadata (no secrets). Production impl is
 * DataStore Preferences; tests use the in-memory twin.
 */
interface ClusterRegistry {
    val connections: StateFlow<List<ClusterConnection>>
    val activeSelection: StateFlow<ActiveSelection>
    suspend fun setConnections(list: List<ClusterConnection>)
    suspend fun setActive(selection: ActiveSelection)
}
