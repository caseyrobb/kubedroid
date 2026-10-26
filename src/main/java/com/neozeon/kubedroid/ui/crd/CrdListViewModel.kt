package com.neozeon.kubedroid.ui.crd

import androidx.lifecycle.ViewModel
import com.neozeon.kubedroid.data.kubernetes.CrdDefinition
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourceResult
import com.neozeon.kubedroid.data.repository.CrdRepository
import com.neozeon.kubedroid.di.ApplicationScope
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
 * Spec 07 "CrdListScreen" state machine: discovery fetch with manual
 * refresh (Q1: cache + explicit refresh, no TTL), client-side
 * name/shortName filter (Q5), generation-guarded fetches.
 */
@HiltViewModel
class CrdListViewModel @Inject constructor(
    private val crdRepository: CrdRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val items: List<CrdDefinition> = emptyList(),
        val error: ResourceError? = null,
        val search: String = "",
        val loaded: Boolean = false,
    ) {
        /** Q5 human decision 2026-10-26: name + kind + plural + shortNames substring. */
        val visibleItems: List<CrdDefinition>
            get() = if (search.isBlank()) items else items.filter { crd ->
                crd.name.contains(search, ignoreCase = true) ||
                    crd.kind.contains(search, ignoreCase = true) ||
                    crd.plural.contains(search, ignoreCase = true) ||
                    crd.singular?.contains(search, ignoreCase = true) == true ||
                    crd.shortNames.any { it.contains(search, ignoreCase = true) }
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var connectionId: UUID? = null
    private var fetchJob: Job? = null
    private var generation = 0

    /** From the screen's LaunchedEffect; cache-backed (repository serves the
     *  first hit from its own cache too — a revisit costs one in-memory hop). */
    fun open(connectionId: UUID) {
        this.connectionId = connectionId
        if (_state.value.loaded) return
        load(connectionId, refresh = false)
    }

    fun refresh() {
        val id = connectionId ?: return
        load(id, refresh = true)
    }

    fun onSearch(query: String) {
        if (_state.value.search != query) _state.update { it.copy(search = query) }
    }

    private fun load(id: UUID, refresh: Boolean) {
        fetchJob?.cancel()
        val gen = ++generation
        _state.update { it.copy(loading = true, error = null) }
        fetchJob = scope.launch {
            val r = crdRepository.list(id, refresh = refresh)
            if (gen != generation) return@launch
            _state.update { st ->
                when (r) {
                    is ResourceResult.Ok -> st.copy(loading = false, items = r.value, error = null, loaded = true)
                    is ResourceResult.Err -> st.copy(loading = false, items = emptyList(), error = r.error, loaded = false)
                }
            }
        }
    }
}
