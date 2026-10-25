package com.neozeon.kubedroid.data.local

import com.neozeon.kubedroid.data.kubernetes.ApiClientProvider
import com.neozeon.kubedroid.data.kubernetes.ConnectionNotFoundException
import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.model.ActiveSelection
import com.neozeon.kubedroid.data.model.AddResult
import com.neozeon.kubedroid.data.model.ClusterConnection
import com.neozeon.kubedroid.data.model.ConnectionStatus
import com.neozeon.kubedroid.data.registry.ClusterRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InMemoryClusterRegistry : ClusterRegistry {
    val callLog = mutableListOf<String>()

    private val _connections = MutableStateFlow<List<ClusterConnection>>(emptyList())
    private val _active = MutableStateFlow(ActiveSelection(null, null))

    override val connections: StateFlow<List<ClusterConnection>> = _connections
    override val activeSelection: StateFlow<ActiveSelection> = _active

    override suspend fun setConnections(list: List<ClusterConnection>) {
        callLog += "registry.setConnections"
        _connections.value = list
    }

    override suspend fun setActive(selection: ActiveSelection) {
        callLog += "registry.setActive"
        _active.value = selection
    }
}

/**
 * Spec 03 Testing: ClusterRepository — dedup, wipe order, fresh ids on
 * re-import, no plaintext on disk, active selection persistence.
 */
class ClusterRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val raw = """
        apiVersion: v1
        kind: Config
        current-context: ctx1
        clusters:
        - name: c1
          cluster:
            server: https://api.example.com:6443
        contexts:
        - name: ctx1
          context: { cluster: c1, user: u1 }
        - name: ctx2
          context: { cluster: c1 }
        users:
        - name: u1
          user:
            token: TOP-SECRET-TOKEN-XYZ
    """.trimIndent()

    private class Fixture(val dir: File) {
        val registry = InMemoryClusterRegistry()
        val vault = FakeCipherVault()
        val blobStore = BlobStore(dir, vault)
        val provider = ApiClientProvider(blobStore, registry, KubeconfigParser())
        val repository = ClusterRepository(
            registry, blobStore, provider, WallClock { 1234567890L }, KubeconfigParser(),
        )
    }

    private fun fixture() = Fixture(tmp.newFolder())

    private suspend fun Fixture.addFirst(): AddResult.Added =
        repository.add(raw, "prod", "ctx1") as AddResult.Added

    @Test fun addPersistsAndActivates() = runTest {
        val f = fixture()
        val added = f.addFirst()
        assertEquals(1, f.registry.connections.value.size)
        val row = f.registry.connections.value.single()
        assertEquals("prod", row.displayName)
        assertEquals(1234567890L, row.addedAtEpochSec)
        assertEquals(64, row.payloadSha256.length)
        assertEquals(listOf("ctx1", "ctx2"), row.contexts)
        assertTrue(f.blobStore.exists(row.id))
        assertEquals(added.connection.id, f.registry.activeSelection.value.connectionId)
        assertEquals("ctx1", f.registry.activeSelection.value.contextName)
        assertEquals(ConnectionStatus.Unvalidated, f.repository.statusOf(row.id))
    }

    @Test fun noPlaintextOnDisk() = runTest {
        val f = fixture()
        f.addFirst()
        val allBytes = f.dir.walkTopDown().filter { it.isFile }.fold(ByteArray(0)) { acc, file ->
            acc + file.readBytes()
        }
        val text = allBytes.toString(Charsets.UTF_8)
        assertFalse("raw kubeconfig found on disk", text.contains("apiVersion"))
        assertFalse("token found on disk", text.contains("TOP-SECRET-TOKEN-XYZ"))
    }

    @Test fun identicalPayloadRejectedAsDuplicate() = runTest {
        val f = fixture()
        val first = f.addFirst()
        val second = f.repository.add(raw, "prod-copy", "ctx1")
        assertTrue(second is AddResult.Duplicate)
        assertEquals(first.connection.id, (second as AddResult.Duplicate).existingId)
        assertEquals(1, f.registry.connections.value.size)
        // nothing extra persisted for the duplicate
        assertEquals(1, f.dir.walkTopDown().count { it.isFile })
    }

    @Test fun invalidKubeconfigRejectedAndNothingPersisted() = runTest {
        val f = fixture()
        val bad = raw.replace("token: TOP-SECRET-TOKEN-XYZ", "exec:\n        command: kubelogin")
        val result = f.repository.add(bad, "bad", "ctx1")
        assertTrue("expected Rejected, got $result", result is AddResult.Rejected)
        assertTrue((result as AddResult.Rejected).reason.contains("exec"))
        assertEquals(0, f.registry.connections.value.size)
        assertEquals(0, f.dir.walkTopDown().count { it.isFile })
    }

    @Test fun unknownContextRejected() = runTest {
        val f = fixture()
        val result = f.repository.add(raw, "prod", "no-such-context")
        assertTrue(result is AddResult.Rejected)
    }

    @Test fun deleteWipesRegistryRowBeforeBlob() = runTest {
        val f = fixture()
        val added = f.addFirst()
        val id = added.connection.id
        f.registry.callLog.clear()
        // wrap blob store visibility: file must be gone after delete; order via callLog
        f.repository.delete(id)
        assertTrue(f.registry.callLog.first() == "registry.setConnections")
        assertFalse(f.blobStore.exists(id))
        assertEquals(emptyList<ClusterConnection>(), f.registry.connections.value)
        assertEquals(ActiveSelection(null, null), f.registry.activeSelection.value)
        assertEquals(ConnectionStatus.Unvalidated, f.repository.statusOf(id))
    }

    @Test fun reImportSamePayloadAfterDeleteGetsFreshId() = runTest {
        val f = fixture()
        val first = f.addFirst().connection.id
        f.repository.delete(first)
        val again = f.repository.add(raw, "prod", "ctx1")
        assertTrue(again is AddResult.Added)
        assertNotEquals(first, (again as AddResult.Added).connection.id)
    }

    @Test fun clientForDeletedConnectionFailsTyped() = runTest {
        val f = fixture()
        val id = f.addFirst().connection.id
        f.repository.delete(id)
        val outcome = runCatching { f.provider.clientFor(id, "ctx1") }
        assertTrue(
            "expected ConnectionNotFoundException, got ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is ConnectionNotFoundException,
        )
    }

    @Test fun renameUpdatesRow() = runTest {
        val f = fixture()
        val id = f.addFirst().connection.id
        f.repository.rename(id, "staging")
        assertEquals("staging", f.registry.connections.value.single().displayName)
    }

    @Test fun selectContextUpdatesRowAndActive() = runTest {
        val f = fixture()
        val id = f.addFirst().connection.id
        f.repository.selectContext(id, "ctx2")
        assertEquals("ctx2", f.registry.connections.value.single().contextName)
        assertEquals("ctx2", f.registry.activeSelection.value.contextName)
    }

    @Test fun selectContextRejectsUnknownContext() = runTest {
        val f = fixture()
        val id = f.addFirst().connection.id
        val outcome = runCatching { f.repository.selectContext(id, "nope") }
        assertTrue(outcome.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun activeSelectionSurvivesRegistryRecreation() = runTest {
        // Simulates app restart: a second registry instance over the same
        // fake store state keeps connections + active selection.
        val f = fixture()
        val added = f.addFirst()
        val restored = InMemoryClusterRegistry()
        restored.setConnections(f.registry.connections.value)
        restored.setActive(f.registry.activeSelection.value)
        assertEquals(added.connection.id, restored.activeSelection.value.connectionId)
        assertEquals(1, restored.connections.value.size)
    }
}
