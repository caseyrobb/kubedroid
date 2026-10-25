package com.neozeon.kubedroid.ui.cluster

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neozeon.kubedroid.di.ApplicationScope
import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.kubernetes.ParseResult
import com.neozeon.kubedroid.data.kubernetes.ParsedKubeconfig
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.model.AddResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the cluster list, add flows (File/Paste/QR), context switching,
 * delete confirmation and probes (spec 03 "UI"). One-shot user messages
 * go through [events]; all state is StateFlow. Logs ids/names only —
 * never kubeconfig material (spec 03 "Secure storage").
 */
@HiltViewModel
class ClusterListViewModel @Inject constructor(
    private val repository: ClusterRepository,
    private val parser: KubeconfigParser,
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val connections: StateFlow<List<com.neozeon.kubedroid.data.model.ClusterConnection>> =
        repository.connections
    val statuses = repository.statuses
    val activeSelection = repository.activeSelection

    data class AddDraft(
        val raw: String = "",
        val parsed: ParsedKubeconfig? = null,
        val error: String? = null,
        val displayName: String = "",
        val contextName: String = "",
        val userEditedName: Boolean = false,
    )

    private val _draft = MutableStateFlow<AddDraft?>(null)
    val draft: StateFlow<AddDraft?> = _draft.asStateFlow()

    private val _pendingDelete = MutableStateFlow<UUID?>(null)
    val pendingDelete: StateFlow<UUID?> = _pendingDelete.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val isEmpty: Boolean get() = repository.connections.value.isEmpty()

    // ---- add flow ------------------------------------------------------

    fun openAdd(initialRaw: String = "") {
        _draft.value = AddDraft().copy(raw = initialRaw).let { validate(it, initialRaw) }
    }

    fun updateRaw(raw: String) {
        _draft.value?.let { _draft.value = validate(it, raw) }
    }

    private fun validate(draft: AddDraft, raw: String): AddDraft =
        when (val r = parser.parse(raw)) {
            is ParseResult.Invalid -> draft.copy(raw = raw, parsed = null, error = r.error.userMessage())
            is ParseResult.Valid -> {
                val current = r.config.currentContextName ?: r.config.contexts.firstOrNull()?.name ?: ""
                draft.copy(
                    raw = raw,
                    parsed = r.config,
                    error = null,
                    displayName = if (draft.userEditedName) draft.displayName
                    else current.ifEmpty { "cluster" },
                    contextName = if (draft.contextName in r.config.contexts.map { it.name })
                        draft.contextName else current,
                )
            }
        }

    fun updateName(name: String) {
        _draft.value = _draft.value?.copy(displayName = name, userEditedName = true)
    }

    fun updateContext(context: String) {
        _draft.value = _draft.value?.copy(contextName = context)
    }

    fun closeAdd() {
        _draft.value = null
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text == null) {
                _events.send("Could not read the selected file")
            } else {
                openAdd(text)
            }
        }
    }

    fun confirmAdd() {
        val draft = _draft.value ?: return
        val parsed = draft.parsed ?: return
        val name = draft.displayName.ifBlank { "cluster" }
        val context = draft.contextName
        viewModelScope.launch {
            when (val result = repository.add(draft.raw, name, context)) {
                is AddResult.Added -> {
                    _draft.value = null
                    _events.send("Cluster added: ${result.connection.displayName}")
                    probe(result.connection.id)
                }
                is AddResult.Duplicate ->
                    _events.send("Already imported")
                is AddResult.Rejected ->
                    _draft.value = draft.copy(error = result.reason)
            }
        }
    }

    // ---- row actions ---------------------------------------------------

    fun setActive(id: UUID?) = viewModelScope.launch { repository.setActive(id) }

    fun selectContext(id: UUID, context: String) =
        viewModelScope.launch { repository.selectContext(id, context) }

    fun rename(id: UUID, name: String) =
        viewModelScope.launch { repository.rename(id, name) }

    fun requestDelete(id: UUID) {
        _pendingDelete.value = id
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete(id: UUID) {
        _pendingDelete.value = null
        viewModelScope.launch {
            repository.delete(id)
            _events.send("Connection deleted")
        }
    }

    fun probe(id: UUID) {
        appScope.launch(Dispatchers.IO) { repository.probe(id) }
    }

    fun refreshActive() {
        activeSelection.value.connectionId?.let { probe(it) }
    }
}
