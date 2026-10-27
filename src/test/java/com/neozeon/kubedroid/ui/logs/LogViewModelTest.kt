package com.neozeon.kubedroid.ui.logs

import com.neozeon.kubedroid.data.kubernetes.ApiClientProvider
import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.kubernetes.LogGateway
import com.neozeon.kubedroid.data.kubernetes.LogLine
import com.neozeon.kubedroid.data.kubernetes.LogLineEvent
import com.neozeon.kubedroid.data.kubernetes.ResourceGateway
import com.neozeon.kubedroid.data.kubernetes.RetryBackoff
import com.neozeon.kubedroid.data.kubernetes.StreamEndReason
import com.neozeon.kubedroid.data.kubernetes.TestClusterFixture
import com.neozeon.kubedroid.data.kubernetes.kubeconfigFor
import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.local.FakeCipherVault
import com.neozeon.kubedroid.data.local.InMemoryClusterRegistry
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.repository.LogRepository
import com.neozeon.kubedroid.data.repository.ResourceRepository
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spec 05 stage 3 Testing: the LogViewModel state machine against a fake
 * LogRepository whose streams are manually-drained channels — stream
 * lifecycle, generation-guarded stale drops, display-only toggles making
 * zero gateway calls, text-only filtering, buffer cap, and the save
 * state machine.
 */
class LogViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private data class StreamReq(
        val connectionId: UUID,
        val namespace: String,
        val podName: String,
        val container: String?,
        val previous: Boolean,
        val follow: Boolean,
    )

    private inner class FakeLogRepo(clusters: ClusterRepository) :
        LogRepository(
            LogGateway(
                ApiClientProvider(BlobStore(tmp.newFolder(), FakeCipherVault()),
                    InMemoryClusterRegistry(), KubeconfigParser()),
                RetryBackoff { },
            ),
            clusters,
        ) {
        val channels = mutableListOf<Channel<LogLineEvent>>()
        val streamRequests = mutableListOf<StreamReq>()
        var saveResult: ResourceError? = null
        var saveBytes: ByteArray? = null
        var saveCalls = 0

        override fun stream(
            connectionId: UUID, namespace: String, podName: String,
            container: String?, previous: Boolean, follow: Boolean,
        ): Flow<LogLineEvent> {
            streamRequests += StreamReq(connectionId, namespace, podName, container, previous, follow)
            val channel = Channel<LogLineEvent>(Channel.UNLIMITED)
            channels += channel
            return channel.consumeAsFlow()
        }

        override suspend fun save(
            connectionId: UUID, namespace: String, podName: String,
            container: String?, previous: Boolean, sink: OutputStream,
        ): ResourceError? {
            saveCalls++
            saveBytes?.let { sink.write(it) }
            sink.flush()
            return saveResult
        }

        fun emit(index: Int, event: LogLineEvent) {
            channels[index].trySend(event)
        }

        fun emitLast(event: LogLineEvent) = emit(channels.lastIndex, event)
    }

    private fun fixture(): Pair<FakeLogRepo, UUID> {
        val f = TestClusterFixture(tmp.newFolder())
        val id = runBlocking { f.add(kubeconfigFor("http://fake")) }
        val repo = FakeLogRepo(f.repository)
        return repo to id
    }

    private fun vm(repo: LogRepository): LogViewModel {
        val resourceRepo = ResourceRepository(
            ResourceGateway(
                ApiClientProvider(BlobStore(tmp.newFolder(), FakeCipherVault()),
                    InMemoryClusterRegistry(), KubeconfigParser()),
                RetryBackoff { },
            ),
            TestClusterFixture(tmp.newFolder()).repository,
        )
        return LogViewModel(repo, resourceRepo, CoroutineScope(Dispatchers.Unconfined))
    }

    private fun params(id: UUID) = LogViewModel.Params(id, "web", "web-1")

    private fun line(text: String, ts: String? = null) = LogLine(ts, text)

    @Test fun firstLineFlipsLoadingAndLinesAppear() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = null, knownContainers = listOf("app") to emptyList())

        assertTrue(vm.state.value.loading)
        assertEquals(1, repo.channels.size)
        assertEquals(StreamReq(id, "web", "web-1", "app", false, false), repo.streamRequests.single())

        repo.emitLast(LogLineEvent.Line(line("hello")))
        assertFalse(vm.state.value.loading)
        assertEquals(listOf(line("hello")), vm.state.value.visibleLines)
        assertEquals(1, vm.state.value.seen)

        repo.emitLast(LogLineEvent.StreamEnd(StreamEndReason.Completed))
        assertTrue(vm.state.value.ended)
        assertFalse(vm.state.value.streaming)
    }

    @Test fun singleContainerIsAutoSelected() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = null, knownContainers = listOf("app") to emptyList())
        assertEquals("app", vm.state.value.selectedContainer)
        assertEquals(listOf("app"), vm.state.value.containers)
        assertEquals("app", repo.streamRequests.single().container)
    }

    @Test fun multipleContainersLeaveSelectionUnset() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = null, knownContainers = listOf("app") to listOf("init-db"))
        assertNull(vm.state.value.selectedContainer)
        assertEquals(listOf("app", "init-db"), vm.state.value.containers)
        assertNull(repo.streamRequests.single().container)
    }

    @Test fun unknownInitialContainerFailsValidationWithoutStream() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = "ghost", knownContainers = listOf("app") to emptyList())
        assertTrue(vm.state.value.error is ResourceError.ContainerNotFound)
        assertTrue(repo.streamRequests.isEmpty())
    }

    @Test fun containerSwitchSupersedesAndDropsStaleEvents() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = null,
            knownContainers = listOf("app", "sidecar") to emptyList())
        repo.emitLast(LogLineEvent.Line(line("from-app")))

        vm.selectContainer("sidecar")
        assertEquals("sidecar", repo.streamRequests.last().container)

        // The superseded stream delivers a late line; the new one its own.
        repo.emit(0, LogLineEvent.Line(line("late-from-app")))
        repo.emit(1, LogLineEvent.Line(line("from-sidecar")))

        val texts = vm.state.value.visibleLines.map { it.text }
        assertEquals(listOf("from-sidecar"), texts)
    }

    @Test fun followAndPreviousToggleRestartWithRightParams() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = null, knownContainers = listOf("app") to emptyList())

        vm.setFollow(true)
        assertTrue(repo.streamRequests.last().follow)
        assertTrue(vm.state.value.streaming)

        vm.setPrevious(true)
        assertTrue(repo.streamRequests.last().previous)
        assertTrue(repo.streamRequests.last().follow) // preserved across restart
    }

    @Test fun displayOnlyTogglesAndFilterMakeNoGatewayCalls() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = null, knownContainers = listOf("app") to emptyList())
        val before = repo.streamRequests.size
        repeat(4) { vm.toggleTimestamps() }
        repeat(2) { vm.toggleWrap() }
        vm.onFilter("abc")
        vm.onFilter("")
        vm.onScrolledUp()
        vm.jumpToLatest()
        assertEquals(before, repo.streamRequests.size)
    }

    @Test fun filterMatchesTextOnlyNeverTimestamp() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = null, knownContainers = listOf("app") to emptyList())
        repo.emitLast(LogLineEvent.Line(line("hello", ts = "2026-10-25T12:00:00Z needle")))

        vm.onFilter("needle") // only present in the timestamp portion
        assertTrue(vm.state.value.visibleLines.isEmpty())

        vm.onFilter("hel")
        assertEquals(1, vm.state.value.visibleLines.size)

        vm.onFilter("HELLO")
        assertEquals(1, vm.state.value.visibleLines.size) // case-insensitive
    }

    @Test fun bufferCapEvictsOldestAndCounts() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = null, knownContainers = listOf("app") to emptyList())
        repeat(LogBuffer.CAP + 5) { repo.emitLast(LogLineEvent.Line(line("l$it"))) }

        assertEquals(LogBuffer.CAP, vm.state.value.lines.size)
        assertEquals(LogBuffer.CAP + 5, vm.state.value.seen)
        assertEquals(5, vm.state.value.evicted)
        assertEquals("l5", vm.state.value.lines.first().text) // oldest five gone
    }

    @Test fun errorThenRetryRestartsStreamAndClearsError() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = null, knownContainers = listOf("app") to emptyList())

        repo.emitLast(LogLineEvent.StreamEnd(StreamEndReason.Error(ResourceError.ForbiddenLogs("web"))))
        assertEquals(ResourceError.ForbiddenLogs("web"), vm.state.value.error)
        assertFalse(vm.state.value.loading)

        vm.retry()
        assertEquals(2, repo.streamRequests.size)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.loading)
    }

    @Test fun saveReportsLineCountClearsMessagesAndHandlesCancel() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = null, knownContainers = listOf("app") to emptyList())

        repo.saveBytes = "a\nb\nc\n".toByteArray()
        vm.save { ByteArrayOutputStream() }
        assertEquals("Saved 3 lines", vm.state.value.saveMessage)
        assertNull(vm.state.value.saveError)
        assertFalse(vm.state.value.saving)

        // Picker cancelled: no file, no message, no error.
        vm.save { null }
        assertNull(vm.state.value.saveMessage)
        assertNull(vm.state.value.saveError)
        assertFalse(vm.state.value.saving)
        assertEquals(1, repo.saveCalls) // cancelled open did not reach the repo
    }

    @Test fun saveReportsMappedError() {
        val (repo, id) = fixture()
        val vm = vm(repo)
        vm.open(params(id), initialContainer = null, knownContainers = listOf("app") to emptyList())

        repo.saveResult = ResourceError.Network()
        vm.save { ByteArrayOutputStream() }
        assertEquals(ResourceError.Network(), vm.state.value.saveError)
        assertNull(vm.state.value.saveMessage)
        assertFalse(vm.state.value.saving)
    }
}
