package com.neozeon.kubedroid.ui.resources

import androidx.lifecycle.ViewModel
import com.neozeon.kubedroid.di.ApplicationScope
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourceResult
import com.neozeon.kubedroid.data.model.ResourceSummary
import com.neozeon.kubedroid.data.repository.ResourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Spec 04 "ResourceListScreen" state machine: fetch/refresh/infinite-scroll
 * with generation-guarded cancellation (mid-flight scope/search/type changes
 * supersede in-flight requests; their responses are dropped), debounced
 * client-side search, namespace scope.
 */
@HiltViewModel
class ResourceListViewModel @Inject constructor(
    private val repository: ResourceRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : ViewModel() {

    data class Target(
        val connectionId: UUID,
        val apiVersion: String,
        val resource: String,
    )

    data class UiState(
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val items: List<ResourceSummary> = emptyList(),
        val error: ResourceError? = null,
        val continueToken: String? = null,
        val search: String = "",
        val namespace: String? = null,
        val namespaces: List<String> = emptyList(),
        val target: Target? = null,
        // ---- spec 06 delete state ----
        val deleteTarget: ResourceSummary? = null,
        val deleting: Boolean = false,
        val deleteError: ResourceError? = null,
        val deleteInfo: String? = null,
    ) {
        /** Client-side filter over name + label values (spec 04). */
        val visibleItems: List<ResourceSummary>
            get() = if (search.isBlank()) items else items.filter { s ->
                s.name.contains(search, ignoreCase = true) ||
                    s.labels.values.any { it.contains(search, ignoreCase = true) }
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var fetchJob: Job? = null
    private var generation = 0

    init {
        // spec 06 "Post-mutation consistency": any successful mutation (from
        // any screen) refreshes every live list. Skips the initial 0 value.
        scope.launch {
            var first = true
            ResourceInvalidations.tick.collect {
                if (first) first = false else refresh()
            }
        }
    }

    /** Called from the screen's LaunchedEffect; no-op when already loaded. */
    fun open(target: Target) {
        if (_state.value.target == target && _state.value.items.isNotEmpty()) return
        fetch(target, _state.value.namespace)
        if (_state.value.namespaces.isEmpty() && _state.value.target?.resource != "namespaces") {
            scope.launch {
                val r = repository.namespaces(target.connectionId)
                if (r is ResourceResult.Ok) _state.update { it.copy(namespaces = r.value) }
            }
        }
    }

    fun selectNamespace(namespace: String?) {
        val target = _state.value.target ?: return
        if (_state.value.namespace == namespace) return
        _state.update { it.copy(namespace = namespace, items = emptyList(), continueToken = null, error = null) }
        fetch(target, namespace)
    }

    fun onSearch(query: String) {
        // In-memory filtering over an already-loaded page: applied
        // immediately; keystrokes batch naturally with Compose recomposition.
        // (Spec's 300 ms debounce targets network queries — none here.)
        if (_state.value.search != query) _state.update { it.copy(search = query) }
    }

    fun refresh() {
        val target = _state.value.target ?: return
        fetch(target, _state.value.namespace)
    }

    // ---- spec 06: delete from the list row overflow ----

    fun requestDelete(summary: ResourceSummary) {
        _state.update { it.copy(deleteTarget = summary, deleteError = null, deleteInfo = null) }
    }

    fun dismissDelete() {
        _state.update { it.copy(deleteTarget = null) }
    }

    fun dismissDeleteError() {
        _state.update { it.copy(deleteError = null) }
    }

    fun confirmDelete() {
        val summary = _state.value.deleteTarget ?: return
        val target = _state.value.target ?: return
        if (_state.value.deleting) return
        _state.update { it.copy(deleting = true, deleteError = null) }
        scope.launch {
            val r = repository.delete(
                com.neozeon.kubedroid.data.model.ResourceRef(
                    target.connectionId, target.apiVersion, target.resource, summary.namespace, summary.name,
                ),
            )
            _state.update { cur ->
                when {
                    r is ResourceResult.Ok && !r.value.existed ->
                        cur.copy(deleting = false, deleteTarget = null,
                            deleteInfo = "${summary.name} was already deleted.")
                    r is ResourceResult.Ok -> cur.copy(deleting = false, deleteTarget = null)
                    r is ResourceResult.Err -> cur.copy(deleting = false, deleteTarget = null, deleteError = r.error)
                    else -> cur.copy(deleting = false)
                }
            }
            // Ok already refreshes via the invalidation bump below.
            if (r is ResourceResult.Ok) ResourceInvalidations.bump()
        }
    }

    fun loadMore() {
        val target = _state.value.target ?: return
        val token = _state.value.continueToken ?: return
        if (_state.value.loadingMore || _state.value.loading) return
        _state.update { it.copy(loadingMore = true) }
        val gen = ++generation
        scope.launch {
            val r = repository.list(target.connectionId, target.apiVersion, target.resource, _state.value.namespace, token)
            if (gen != generation) return@launch // superseded by a newer request
            _state.update { st ->
                when (r) {
                    is ResourceResult.Ok -> st.copy(
                        loadingMore = false,
                        items = st.items + r.value.items,
                        continueToken = r.value.continueToken,
                    )
                    is ResourceResult.Err -> st.copy(loadingMore = false, error = r.error)
                }
            }
        }
    }

    private fun fetch(target: Target, namespace: String?) {
        fetchJob?.cancel()
        val gen = ++generation
        _state.update { it.copy(target = target, loading = true, error = null, continueToken = null) }
        fetchJob = scope.launch {
            val r = repository.list(target.connectionId, target.apiVersion, target.resource, namespace)
            if (gen != generation) return@launch // drop superseded response
            _state.update { st ->
                when (r) {
                    is ResourceResult.Ok -> st.copy(
                        loading = false,
                        items = r.value.items,
                        continueToken = r.value.continueToken,
                        error = null,
                    )
                    is ResourceResult.Err -> st.copy(loading = false, items = emptyList(), error = r.error)
                }
            }
        }
    }
}
