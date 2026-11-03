package com.neozeon.kubedroid.ui.logs

import androidx.lifecycle.ViewModel
import com.neozeon.kubedroid.di.ApplicationScope
import com.neozeon.kubedroid.data.kubernetes.LogLine
import com.neozeon.kubedroid.data.kubernetes.LogLineEvent
import com.neozeon.kubedroid.data.kubernetes.StreamEndReason
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourceRef
import com.neozeon.kubedroid.data.model.ResourceResult
import com.neozeon.kubedroid.data.repository.LogRepository
import com.neozeon.kubedroid.data.repository.ResourceRepository
import com.neozeon.kubedroid.data.kubernetes.ResourceCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.OutputStream
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
 * Ring buffer capped per spec 05 (10,000 lines): follow appends evict the
 * oldest; the header reports "showing last {cap} of {seen}".
 */
class LogBuffer(private val cap: Int = CAP) {
    private val lines = ArrayDeque<LogLine>()
    var seen: Int = 0
        private set
    var evicted: Int = 0
        private set

    fun add(line: LogLine) {
        lines.addLast(line)
        seen++
        if (lines.size > cap) {
            lines.removeFirst()
            evicted++
        }
    }

    fun clear() {
        lines.clear()
        seen = 0
        evicted = 0
    }

    fun snapshot(): List<LogLine> = lines.toList()

    companion object {
        const val CAP = 10_000
    }
}

/**
 * Spec 05 "LogScreen" state machine: stream lifecycle with generation-
 * guarded restarts (container/previous/follow changes supersede the live
 * stream; superseded events are dropped), display-only Timestamps/Wrap
 * toggles that never touch the network (tested explicitly), in-memory
 * filter over the buffer (text portion only — never the timestamp prefix),
 * and the save state machine (complete log via repository.save; the sink is
 * opened by the caller, line count reported on success).
 */
@HiltViewModel
class LogViewModel @Inject constructor(
    private val repository: LogRepository,
    private val resourceRepository: ResourceRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : ViewModel() {

    data class Params(
        val connectionId: UUID,
        val namespace: String,
        val podName: String,
    )

    data class UiState(
        val params: Params? = null,
        val loading: Boolean = false,
        val streaming: Boolean = false,
        val ended: Boolean = false,
        val error: ResourceError? = null,
        val lines: List<LogLine> = emptyList(),
        val seen: Int = 0,
        val evicted: Int = 0,
        val filter: String = "",
        val follow: Boolean = false,
        val showTimestamps: Boolean = false,
        val previous: Boolean = false,
        // spec 12 goal 7: wrap defaults off; both toggles persist per
        // session via LogDisplaySession (seeded in open()).
        val wrap: Boolean = false,
        val containers: List<String> = emptyList(),
        val selectedContainer: String? = null,
        val autoScroll: Boolean = true,
        val saving: Boolean = false,
        val saveMessage: String? = null,
        val saveError: ResourceError? = null,
    ) {
        /** Filter matches the text portion only, never the timestamp (spec 05). */
        val visibleLines: List<LogLine>
            get() = if (filter.isBlank()) lines
            else lines.filter { it.text.contains(filter, ignoreCase = true) }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val buffer = LogBuffer()
    private var streamJob: Job? = null
    private var generation = 0

    /**
     * Screen entry. [knownContainers] (regular, init) normally comes from the
     * already-loaded pod detail (spec: no second GET); it is fetched once as
     * a fallback only when the caller has none (e.g. restored process death).
     */
    fun open(
        params: Params,
        initialContainer: String?,
        knownContainers: Pair<List<String>, List<String>>? = null,
    ) {
        if (_state.value.params == params && _state.value.containers.isNotEmpty()) return
        // spec 12 goal 7: inherit last session's display toggles.
        _state.update { it.copy(wrap = LogDisplaySession.wrap, showTimestamps = LogDisplaySession.timestamps) }
        if (knownContainers != null) {
            startWith(params, initialContainer, knownContainers.first, knownContainers.second)
        } else {
            scope.launch {
                val r = resourceRepository.detail(
                    ResourceRef(params.connectionId, "v1", "pods", params.namespace, params.podName),
                )
                val names = when (r) {
                    is ResourceResult.Ok -> ResourceCatalog.containerNames(r.value.obj)
                    is ResourceResult.Err -> {
                        _state.update { it.copy(params = params, error = r.error) }
                        return@launch
                    }
                }
                startWith(params, initialContainer, names.first, names.second)
            }
        }
    }

    private fun startWith(
        params: Params,
        initialContainer: String?,
        regular: List<String>,
        init: List<String>,
    ) {
        val all = regular + init
        val selected = initialContainer?.takeIf { it in all }
            ?: if (all.size == 1) all.single() else null
        val validation = repository.validateContainer(initialContainer, regular, init)
        _state.update {
            it.copy(
                params = params,
                containers = all,
                selectedContainer = selected,
                error = validation,
            )
        }
        if (validation == null) startStream()
    }

    fun retry() = startStream()

    fun setFollow(follow: Boolean) {
        if (_state.value.follow == follow) return
        _state.update { it.copy(follow = follow) }
        startStream()
    }

    fun setPrevious(previous: Boolean) {
        if (_state.value.previous == previous) return
        _state.update { it.copy(previous = previous) }
        startStream()
    }

    fun selectContainer(container: String) {
        val st = _state.value
        if (st.selectedContainer == container) return
        val validation = repository.validateContainer(container, st.containers, emptyList())
        if (validation != null) {
            _state.update { it.copy(error = validation) }
            return
        }
        _state.update { it.copy(selectedContainer = container, error = null) }
        startStream()
    }

    /** Display-only: never touches the network (spec 05); session-persisted (spec 12). */
    fun toggleTimestamps() = _state.update {
        it.copy(showTimestamps = !it.showTimestamps).also { s ->
            LogDisplaySession.timestamps = s.showTimestamps
        }
    }

    /** Display-only: never touches the network (spec 05); session-persisted (spec 12). */
    fun toggleWrap() = _state.update {
        it.copy(wrap = !it.wrap).also { s -> LogDisplaySession.wrap = s.wrap }
    }

    fun onFilter(filter: String) = _state.update { it.copy(filter = filter) }

    fun onScrolledUp() = _state.update { it.copy(autoScroll = false) }

    fun jumpToLatest() = _state.update { it.copy(autoScroll = true) }

    /**
     * Save the complete log (spec 05). [openSink] is invoked with the user's
     * chosen document (SAF, stage 4); returning null means the picker was
     * cancelled — nothing happens, no file, no message.
     */
    fun save(openSink: () -> OutputStream?) {
        val st = _state.value.params ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, saveError = null, saveMessage = null) }
        scope.launch {
            val sink = openSink()
            if (sink == null) {
                _state.update { it.copy(saving = false) }
                return@launch
            }
            val counting = CountingOutputStream(sink)
            val error = repository.save(
                st.connectionId, st.namespace, st.podName,
                _state.value.selectedContainer, _state.value.previous, counting,
            )
            _state.update {
                it.copy(
                    saving = false,
                    saveError = error,
                    saveMessage = if (error == null) "Saved ${counting.newlines} lines" else null,
                )
            }
        }
    }

    private fun startStream() {
        val st = _state.value
        val params = st.params ?: return
        streamJob?.cancel()
        val gen = ++generation
        buffer.clear()
        _state.update {
            it.copy(
                loading = true, streaming = it.follow, ended = false, error = null,
                lines = emptyList(), seen = 0, evicted = 0,
            )
        }
        streamJob = scope.launch {
            repository.stream(
                params.connectionId, params.namespace, params.podName,
                st.selectedContainer, st.previous, st.follow,
            ).collect { event ->
                if (gen != generation) return@collect // superseded stream
                when (event) {
                    is LogLineEvent.Line -> {
                        buffer.add(event.line)
                        _state.update {
                            it.copy(loading = false, lines = buffer.snapshot(),
                                seen = buffer.seen, evicted = buffer.evicted)
                        }
                    }
                    is LogLineEvent.StreamEnd -> when (val reason = event.reason) {
                        StreamEndReason.Completed ->
                            _state.update { it.copy(loading = false, streaming = false, ended = true) }
                        is StreamEndReason.Error ->
                            _state.update { it.copy(loading = false, streaming = false, error = reason.error) }
                    }
                }
            }
        }
    }

    /** Counts newlines while copying to the SAF document (spec: "Saved n lines"). */
    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var newlines: Int = 0
            private set

        override fun write(b: Int) {
            if (b == '\n'.code) newlines++
            delegate.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) if (b[i] == '\n'.code.toByte()) newlines++
            delegate.write(b, off, len)
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
