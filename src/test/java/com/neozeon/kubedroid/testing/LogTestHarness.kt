package com.neozeon.kubedroid.testing

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
import com.neozeon.kubedroid.ui.logs.LogViewModel
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.runBlocking

/**
 * Shared spec 05 harness: fake LogRepository whose streams are manually
 * drained channels, over a registered cluster fixture. VM and Compose tests
 * drive events by hand — no server, no timing.
 */
class LogTestHarness(private val dir: File) {
    private val fixture = TestClusterFixture(dir)
    val connectionId: UUID = runBlocking { fixture.add(kubeconfigFor("http://fake")) }

    data class StreamReq(
        val connectionId: UUID,
        val namespace: String,
        val podName: String,
        val container: String?,
        val previous: Boolean,
        val follow: Boolean,
    )

    val streamRequests = mutableListOf<StreamReq>()
    val channels = mutableListOf<Channel<LogLineEvent>>()
    var saveResult: ResourceError? = null
    var saveBytes: ByteArray? = null
    var saveCalls = 0

    val repository: LogRepository = object : LogRepository(
        LogGateway(
            ApiClientProvider(BlobStore(dir, FakeCipherVault()),
                InMemoryClusterRegistry(), KubeconfigParser()),
            RetryBackoff { },
        ),
        fixture.repository,
    ) {
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
    }

    fun emit(index: Int, event: LogLineEvent) {
        channels[index].trySend(event)
    }

    fun emitLast(event: LogLineEvent) = emit(channels.lastIndex, event)

    fun emitLineLast(text: String, ts: String? = null) =
        emitLast(LogLineEvent.Line(LogLine(ts, text)))

    fun emitCompletedLast() =
        emitLast(LogLineEvent.StreamEnd(StreamEndReason.Completed))

    fun emitErrorLast(error: ResourceError) =
        emitLast(LogLineEvent.StreamEnd(StreamEndReason.Error(error)))

    fun viewModel(): LogViewModel {
        val resourceRepo = ResourceRepository(
            ResourceGateway(
                ApiClientProvider(BlobStore(dir, FakeCipherVault()),
                    InMemoryClusterRegistry(), KubeconfigParser()),
                RetryBackoff { },
            ),
            TestClusterFixture(File(dir, "fallback-" + System.nanoTime())).repository,
        )
        return LogViewModel(repository, resourceRepo, CoroutineScope(Dispatchers.Unconfined))
    }

    fun params() = LogViewModel.Params(connectionId, "web", "web-1")
}
