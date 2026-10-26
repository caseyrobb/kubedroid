package com.neozeon.kubedroid.ui.resources

import androidx.lifecycle.ViewModel
import com.neozeon.kubedroid.di.ApplicationScope
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourceRef
import com.neozeon.kubedroid.data.model.ResourceResult
import com.neozeon.kubedroid.data.repository.ResourceRepository
import com.neozeon.kubedroid.util.YamlRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Spec 04 "ResourceDetailScreen": Summary + YAML tabs over one live GET.
 * Secrets arrive redacted (repository rule); [reveal] re-fetches decoded
 * material for THIS object only — nothing is cached or logged.
 */
@HiltViewModel
class ResourceDetailViewModel @Inject constructor(
    private val repository: ResourceRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val obj: Map<String, Any?>? = null,
        val error: ResourceError? = null,
        val tab: Int = TAB_SUMMARY,
        val revealed: Boolean = false,
        val yaml: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var ref: ResourceRef? = null
    private var job: Job? = null
    private var generation = 0

    fun open(ref: ResourceRef) {
        if (this.ref == ref && _state.value.obj != null) return
        this.ref = ref
        _state.value = UiState()
        load(ref, reveal = false)
    }

    fun selectTab(tab: Int) {
        _state.update { st ->
            val yaml = if (tab == TAB_YAML && st.yaml == null && st.obj != null) {
                YamlRenderer.render(st.obj)
            } else {
                st.yaml
            }
            st.copy(tab = tab, yaml = yaml)
        }
    }

    /** Secret plaintext on demand (spec 04 human decision 2026-10-25). */
    fun reveal() {
        val target = ref ?: return
        if (_state.value.revealed || _state.value.loading) return
        load(target, reveal = true)
    }

    private fun load(target: ResourceRef, reveal: Boolean) {
        job?.cancel()
        val gen = ++generation
        val wasRevealed = _state.value.revealed || reveal
        _state.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            val r = repository.detail(target, reveal = reveal)
            if (gen != generation) return@launch
            _state.update { st ->
                when (r) {
                    is ResourceResult.Ok -> st.copy(
                        loading = false,
                        obj = r.value.obj,
                        error = null,
                        revealed = wasRevealed,
                        yaml = if (st.tab == TAB_YAML) YamlRenderer.render(r.value.obj) else null,
                    )
                    is ResourceResult.Err -> st.copy(loading = false, error = r.error)
                }
            }
        }
    }

    companion object {
        const val TAB_SUMMARY = 0
        const val TAB_YAML = 1
    }
}
