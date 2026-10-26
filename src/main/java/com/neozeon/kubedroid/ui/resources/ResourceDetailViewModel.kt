package com.neozeon.kubedroid.ui.resources

import androidx.lifecycle.ViewModel
import com.neozeon.kubedroid.di.ApplicationScope
import com.neozeon.kubedroid.data.kubernetes.EditSanitizer
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
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Spec 04 "ResourceDetailScreen": Summary + YAML tabs over one live GET.
 * Secrets arrive redacted (repository rule); [reveal] re-fetches decoded
 * material for THIS object only — nothing is cached or logged.
 *
 * Spec 06 adds the edit mode: [startEdit] seeds a sanitizer-stripped YAML
 * draft, [save] validates locally (parse, required fields, identity guard)
 * BEFORE any network call, and PUTs the loaded-resourceVersion body.
 * Save errors allow Retry as-is (human decision 2026-10-26 Q6); a conflict
 * offers reload, never a silent overwrite.
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
        // ---- spec 06 edit state ----
        val editing: Boolean = false,
        val draft: String = "",
        val initialDraft: String = "",
        val validationError: String? = null,
        val saving: Boolean = false,
        val saveError: ResourceError? = null,
        val showConflict: Boolean = false,
        // ---- spec 06 delete state ----
        val confirmDelete: Boolean = false,
        val deleting: Boolean = false,
        val deleteError: ResourceError? = null,
        val deleteInfo: String? = null,
        val deletedEvent: Int = 0,
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

    // ---- spec 06: edit flow ----

    /** Seed the editor from the sanitizer's display form (no status/RV). */
    fun startEdit() {
        val st = _state.value
        val obj = st.obj ?: return
        if (st.editing) return
        // A masked Secret would put its redaction placeholders back on the
        // wire (PUT replaces data wholesale): reveal first, no exceptions.
        if (obj["kind"] == "Secret" && !st.revealed) return
        val draft = YamlRenderer.render(EditSanitizer.forDisplay(obj))
        _state.update {
            it.copy(
                editing = true, draft = draft, initialDraft = draft,
                validationError = null, saveError = null, showConflict = false,
                tab = TAB_YAML, yaml = null,
            )
        }
    }

    fun updateDraft(text: String) {
        _state.update { it.copy(draft = text, validationError = null) }
    }

    /** Leave edit mode discarding the draft (screen confirms dirty exits). */
    fun exitEdit() {
        _state.update {
            it.copy(
                editing = false, draft = "", initialDraft = "",
                validationError = null, saving = false, saveError = null, showConflict = false,
            )
        }
    }

    /**
     * Validate locally, then PUT. A validation failure NEVER reaches the
     * network (spec 06 Testing); a save error keeps the draft so Retry
     * re-issues the same body (Q6 human decision — the RV precondition
     * turns a duplicate into a 409, never a double-apply).
     */
    fun save() {
        val st = _state.value
        val target = ref ?: return
        val loaded = st.obj ?: return
        if (st.saving || !st.editing) return
        val outcome = validateDraft(st.draft, loaded)
        if (outcome is DraftOutcome.Bad) {
            _state.update { it.copy(validationError = outcome.message) }
            return
        }
        val parsed = (outcome as DraftOutcome.Good).map
        _state.update { it.copy(saving = true, saveError = null, validationError = null) }
        val gen = generation
        scope.launch {
            val body = EditSanitizer.prepareForReplace(parsed, loaded)
            val r = repository.replace(target, body)
            if (gen != generation) return@launch
            _state.update { cur ->
                if (!cur.editing) return@update cur.copy(saving = false)
                when {
                    r is ResourceResult.Ok ->
                        cur.copy(saving = false, editing = false, draft = "", initialDraft = "",
                            saveError = null, validationError = null)
                    r is ResourceResult.Err && r.error is ResourceError.Conflict ->
                        cur.copy(saving = false, showConflict = true)
                    r is ResourceResult.Err ->
                        cur.copy(saving = false, saveError = r.error)
                    else -> cur.copy(saving = false)
                }
            }
            if (r is ResourceResult.Ok) load(target, reveal = _state.value.revealed)
        }
    }

    /** Conflict dialog: Reload — discard the draft and re-GET (never merge). */
    fun conflictReload() {
        val target = ref ?: return
        _state.update { it.copy(showConflict = false) }
        exitEdit()
        load(target, reveal = _state.value.revealed)
    }

    /** Conflict dialog: keep the draft on screen, dismiss the dialog. */
    fun conflictKeepEditing() {
        _state.update { it.copy(showConflict = false) }
    }

    // ---- spec 06: delete flow ----

    fun requestDelete() {
        if (_state.value.obj == null || _state.value.editing) return
        _state.update { it.copy(confirmDelete = true, deleteError = null, deleteInfo = null) }
    }

    fun dismissDelete() {
        _state.update { it.copy(confirmDelete = false) }
    }

    fun dismissDeleteError() {
        _state.update { it.copy(deleteError = null) }
    }

    /**
     * DELETE with the shared confirmation already given. Never retried
     * (repository rule); 404 is success with an info line (spec 06 "Delete
     * flow"); success bumps [ResourceInvalidations] so every list refreshes.
     */
    fun performDelete() {
        val target = ref ?: return
        if (_state.value.deleting) return
        _state.update { it.copy(confirmDelete = false, deleting = true, deleteError = null) }
        scope.launch {
            val r = repository.delete(target)
            _state.update { cur ->
                when {
                    r is ResourceResult.Ok && r.value.existed ->
                        cur.copy(deleting = false, deletedEvent = cur.deletedEvent + 1)
                    r is ResourceResult.Ok ->
                        cur.copy(
                            deleting = false,
                            deleteInfo = "${target.name} was already deleted.",
                            deletedEvent = cur.deletedEvent + 1,
                        )
                    r is ResourceResult.Err -> cur.copy(deleting = false, deleteError = r.error)
                    else -> cur.copy(deleting = false)
                }
            }
            if (r is ResourceResult.Ok) ResourceInvalidations.bump()
        }
    }

    private sealed interface DraftOutcome {
        data class Good(val map: Map<String, Any?>) : DraftOutcome
        data class Bad(val message: String) : DraftOutcome
    }

    /** Local gate: parse, required fields, identity guard (spec 06 "Edit flow"). */
    private fun validateDraft(text: String, loaded: Map<String, Any?>): DraftOutcome {
        val parsed = try {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)
        } catch (e: Exception) {
            return DraftOutcome.Bad("The YAML could not be parsed: ${e.message ?: "syntax error"}")
        }
        @Suppress("UNCHECKED_CAST")
        val map = parsed as? Map<String, Any?>
            ?: return DraftOutcome.Bad("The document must be a YAML mapping.")
        if (map["apiVersion"] !is String) return DraftOutcome.Bad("Missing required field 'apiVersion'.")
        if (map["kind"] !is String) return DraftOutcome.Bad("Missing required field 'kind'.")
        @Suppress("UNCHECKED_CAST")
        val meta = map["metadata"] as? Map<String, Any?>
            ?: return DraftOutcome.Bad("Missing required field 'metadata'.")
        if (meta["name"] !is String) return DraftOutcome.Bad("Missing required field 'metadata.name'.")
        EditSanitizer.identityMismatch(map, loaded)?.let {
            return DraftOutcome.Bad("You cannot change $it — the editor saves back to the same object.")
        }
        return DraftOutcome.Good(map)
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
