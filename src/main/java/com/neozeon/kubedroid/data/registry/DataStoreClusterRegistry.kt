package com.neozeon.kubedroid.data.registry

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.neozeon.kubedroid.data.model.ActiveSelection
import com.neozeon.kubedroid.data.model.ClusterConnection
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.clusterDataStore by preferencesDataStore(name = "cluster_registry")

/**
 * DataStore Preferences registry (spec 03 "Secure storage"): a single JSON
 * list of [ClusterConnection] metadata rows plus the active selection.
 * Rows are non-secret by construction; blobs live separately encrypted.
 */
class DataStoreClusterRegistry(private val context: Context) : ClusterRegistry {

    @Serializable
    private data class Row(
        val id: String,
        val displayName: String,
        val addedAtEpochSec: Long,
        val payloadSha256: String,
        val contextName: String,
        val contexts: List<String>,
    )

    private val store = context.clusterDataStore
    private val keyConnections = stringPreferencesKey("connections")
    private val keyActiveId = stringPreferencesKey("active_id")
    private val keyActiveContext = stringPreferencesKey("active_context")

    private val connectionsFlow: Flow<List<ClusterConnection>> =
        store.data
            .catch { e ->
                // corrupt/torn store: start clean rather than crash the app
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { prefs ->
                val raw = prefs[keyConnections] ?: return@map emptyList()
                runCatching { Json.decodeFromString<List<Row>>(raw) }
                    .getOrDefault(emptyList())
                    .map { it.toModel() }
            }

    override val connections: StateFlow<List<ClusterConnection>> =
        connectionsFlow.stateIn(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            SharingStarted.Eagerly,
            emptyList(),
        )

    override val activeSelection: StateFlow<ActiveSelection> =
        store.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                ActiveSelection(
                    connectionId = prefs[keyActiveId]?.let { idStr ->
                        runCatching { UUID.fromString(idStr) }.getOrNull()
                    },
                    contextName = prefs[keyActiveContext],
                )
            }
            .stateIn(
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
                SharingStarted.Eagerly,
                ActiveSelection(null, null),
            )

    override suspend fun setConnections(list: List<ClusterConnection>) {
        store.edit { prefs ->
            val rows = list.map {
                Row(
                    id = it.id.toString(),
                    displayName = it.displayName,
                    addedAtEpochSec = it.addedAtEpochSec,
                    payloadSha256 = it.payloadSha256,
                    contextName = it.contextName,
                    contexts = it.contexts,
                )
            }
            prefs[keyConnections] = Json.encodeToString(rows)
        }
    }

    override suspend fun setActive(selection: ActiveSelection) {
        store.edit { prefs ->
            prefs[keyActiveId] = selection.connectionId?.toString() ?: ""
            prefs[keyActiveContext] = selection.contextName ?: ""
        }
    }

    private fun Row.toModel() = ClusterConnection(
        id = UUID.fromString(id),
        displayName = displayName,
        addedAtEpochSec = addedAtEpochSec,
        payloadSha256 = payloadSha256,
        contextName = contextName,
        contexts = contexts,
    )
}
