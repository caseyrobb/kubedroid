package com.neozeon.kubedroid.ui.cluster

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neozeon.kubedroid.data.kubernetes.ApiClientProvider
import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.local.FakeCipherVault
import com.neozeon.kubedroid.data.local.InMemoryClusterRegistry
import com.neozeon.kubedroid.data.local.WallClock
import com.neozeon.kubedroid.data.model.ConnectionStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ClusterListViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var registry: InMemoryClusterRegistry
    private lateinit var repository: ClusterRepository
    private lateinit var viewModel: ClusterListViewModel

    // 127.0.0.1:1 refuses instantly; keeps probes fast + offline in tests.
    private val kubeconfig = """
        apiVersion: v1
        kind: Config
        current-context: prod-east
        clusters:
        - name: c1
          cluster:
            server: https://127.0.0.1:1
        contexts:
        - name: prod-east
          context: { cluster: c1, user: u1 }
        - name: prod-west
          context: { cluster: c1 }
        users:
        - name: u1
          user:
            token: VM-TEST-TOKEN
    """.trimIndent()

    @Before fun setUp() {
        registry = InMemoryClusterRegistry()
        val dir = tmp.newFolder()
        val blobStore = BlobStore(dir, FakeCipherVault())
        val parser = KubeconfigParser()
        val provider = ApiClientProvider(blobStore, registry, parser)
        repository = ClusterRepository(
            registry, blobStore, provider, WallClock { 1L }, parser,
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        viewModel = ClusterListViewModel(
            repository, parser, context, CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @Test fun initialEmptyState() = runTest {
        assertTrue(viewModel.connections.value.isEmpty())
        assertNull(viewModel.draft.value)
        assertTrue(viewModel.isEmpty)
    }

    @Test fun pasteFlowHappyPath() = runTest {
        viewModel.openAdd(kubeconfig)
        val draft = viewModel.draft.value
        assertNotNull("draft opened", draft)
        assertNotNull("parses cleanly", draft!!.parsed)
        assertEquals("prod-east", draft.displayName) // prefilled from current-context
        assertEquals("prod-east", draft.contextName)
        viewModel.updateName("prod")
        viewModel.confirmAdd()
        assertEquals(1, viewModel.connections.value.size)
        assertEquals("prod", viewModel.connections.value.single().displayName)
        assertEquals(
            listOf("prod-east", "prod-west"),
            viewModel.connections.value.single().contexts,
        )
    }

    @Test fun validationErrorDisplayed() = runTest {
        val execKubeconfig = kubeconfig.replace(
            "token: VM-TEST-TOKEN",
            "exec:\n        command: kubelogin",
        )
        viewModel.openAdd(execKubeconfig)
        val draft = viewModel.draft.value!!
        assertNull(draft.parsed)
        assertNotNull("typed error surfaced", draft.error)
        assertTrue(draft.error!!.contains("exec"))
    }

    @Test fun duplicateRejected() = runTest {
        viewModel.openAdd(kubeconfig)
        viewModel.confirmAdd()
        assertEquals(1, viewModel.connections.value.size)
        viewModel.openAdd(kubeconfig)
        viewModel.confirmAdd()
        assertEquals("duplicate must not add a second row", 1, viewModel.connections.value.size)
    }

    @Test fun deleteConfirmAndCancel() = runTest {
        viewModel.openAdd(kubeconfig)
        viewModel.confirmAdd()
        val id = viewModel.connections.value.single().id

        viewModel.requestDelete(id)
        assertEquals(id, viewModel.pendingDelete.value)
        viewModel.cancelDelete()
        assertNull(viewModel.pendingDelete.value)
        assertEquals(1, viewModel.connections.value.size)

        viewModel.requestDelete(id)
        viewModel.confirmDelete(id)
        assertTrue(viewModel.connections.value.isEmpty())
        assertNull(viewModel.pendingDelete.value)
    }

    @Test fun contextSwitchUpdatesRowAndActive() = runTest {
        viewModel.openAdd(kubeconfig)
        viewModel.confirmAdd()
        val conn = viewModel.connections.value.single()
        viewModel.selectContext(conn.id, "prod-west")
        assertEquals("prod-west", viewModel.connections.value.single().contextName)
        assertEquals("prod-west", viewModel.activeSelection.value.contextName)
    }

    @Test fun statusRenderingTransitionsAfterProbe() = runTest {
        viewModel.openAdd(kubeconfig)
        viewModel.confirmAdd()
        val id = viewModel.connections.value.single().id
        // probe runs on Dispatchers.IO inside appScope; poll for its result.
        // 127.0.0.1:1 refuses instantly ⇒ Unreachable expected well within budget.
        viewModel.probe(id)
        val deadline = System.currentTimeMillis() + 10_000
        var status = repository.statuses.value[id]
        while (status is ConnectionStatus.Unvalidated && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            status = repository.statuses.value[id]
        }
        assertTrue(
            "expected Unreachable after refused probe, got $status",
            status is ConnectionStatus.Unreachable,
        )
        assertEquals(ConnectionStatus.Unvalidated, repository.statusOf(java.util.UUID.randomUUID()))
    }

    @Test fun importFileReadsFileUri() {
        val f = java.io.File(tmp.root, "kc.yaml")
        f.writeText(kubeconfig)
        val uri = android.net.Uri.fromFile(f)
        // Robolectric's shadow ContentResolver does not open file:// URIs
        // unless registered — wire the stream explicitly.
        org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<Context>().contentResolver,
        ).registerInputStream(uri, java.io.FileInputStream(f))
        viewModel.importFile(uri)
        // No runTest here: viewModelScope runs on Robolectric's main looper
        // (paused mode); the IO read resumes onto it, so pump idle() while
        // waiting on the wall clock.
        val t0 = System.currentTimeMillis()
        while (viewModel.draft.value == null && System.currentTimeMillis() - t0 < 5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        val draft = viewModel.draft.value
        assertNotNull("draft opened from file", draft)
        assertNotNull("file content parsed", draft!!.parsed)
        assertEquals("prod-east", draft.displayName)
    }

    @Test fun renamePropagates() = runTest {
        viewModel.openAdd(kubeconfig)
        viewModel.confirmAdd()
        val id = viewModel.connections.value.single().id
        viewModel.rename(id, "renamed-cluster")
        assertEquals("renamed-cluster", viewModel.connections.value.single().displayName)
    }
}
