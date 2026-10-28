package com.neozeon.kubedroid.ui.cluster

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neozeon.kubedroid.data.kubernetes.ApiClientProvider
import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.local.FakeCipherVault
import com.neozeon.kubedroid.data.local.InMemoryClusterRegistry
import com.neozeon.kubedroid.data.local.WallClock
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec 03 Testing (Robolectric Compose): empty state buttons visible,
 * paste flow end-to-end with fake-backed repository, delete confirmation
 * dialog, context dropdown.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ClusterListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var registry: InMemoryClusterRegistry
    private lateinit var viewModel: ClusterListViewModel

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
            token: UI-TEST-TOKEN
    """.trimIndent()

    @Before fun setUp() {
        registry = InMemoryClusterRegistry()
        val blobStore = BlobStore(tmp.newFolder(), FakeCipherVault())
        val parser = KubeconfigParser()
        val provider = ApiClientProvider(blobStore, registry, parser)
        val repository = ClusterRepository(
            registry, blobStore, provider, WallClock { 1L }, parser,
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        viewModel = ClusterListViewModel(
            repository, parser, context, CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun showScreen(onBrowse: (java.util.UUID) -> Unit = {}) = composeRule.setContent {
        KubedroidTheme { ClusterListScreen(viewModel = viewModel, onBrowse = onBrowse) }
    }

    @Test fun rowTapActivatesAndNavigates() {
        // spec 04 amendment (dev plan stage 1): row tap sets active AND browses.
        var browsed: java.util.UUID? = null
        showScreen { browsed = it }
        viewModel.openAdd(kubeconfig)
        viewModel.confirmAdd()
        composeRule.waitForIdle()
        val id = registry.connections.value.single().id
        composeRule.onNodeWithTag("connection-row").performClick()
        composeRule.waitForIdle()
        assertEquals(id, browsed)
        assertEquals(id, viewModel.activeSelection.value.connectionId)
    }

    @Test fun emptyStateShowsImportButtons() {
        showScreen()
        composeRule.onNodeWithText("kubedroid").assertIsDisplayed()
        composeRule.onNodeWithText("No cluster connected").assertIsDisplayed()
        composeRule.onNodeWithText("Paste kubeconfig").assertIsDisplayed()
        composeRule.onNodeWithText("Open kubeconfig file").assertIsDisplayed()
        composeRule.onNodeWithText("Scan QR code").assertDoesNotExist() // spec 03 stage 10: QR removed, no camera use
        composeRule.onNodeWithText("Add cluster") // FAB
    }

    @Test fun pasteFlowEndToEnd() {
        showScreen()
        composeRule.onNodeWithText("Paste kubeconfig").performClick()
        composeRule.onNodeWithTag("kubeconfig-input").performTextInput(kubeconfig)
        // name prefilled from current-context (may be below the dialog fold)
        composeRule.onNodeWithTag("name-input").fetchSemanticsNode()
        composeRule.onNodeWithTag("confirm-add").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.connections.value.size)
        composeRule.onNodeWithTag("connection-row").assertIsDisplayed() // row shows
        assertTrue(viewModel.connections.value.single().contexts.contains("prod-west"))
    }

    @Test fun execKubeconfigShowsInlineError() {
        showScreen()
        composeRule.onNodeWithText("Paste kubeconfig").performClick()
        composeRule.onNodeWithTag("kubeconfig-input")
            .performTextInput(kubeconfig.replace("token: UI-TEST-TOKEN", "exec:\n        command: kubelogin"))
        // error Text may sit below the dialog fold; assert presence, not display
        composeRule
            .onAllNodesWithText("uses an exec credential plugin", substring = true)
            .assertCountEquals(1)
    }

    @Test fun deleteConfirmationDialog() {
        showScreen()
        // seed a row via the VM directly (paste flow covered in its own test)
        viewModel.openAdd(kubeconfig)
        viewModel.confirmAdd()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("connection-row").performClick() // activates; no-op for dialog
        composeRule.onNodeWithTag("connection-row").performTouchInput { longClick() }

        composeRule.onNodeWithText("Delete connection?").assertIsDisplayed()
        composeRule.onNodeWithTag("cancel-delete").performClick()
        composeRule.onAllNodesWithText("Delete connection?").assertCountEquals(0)

        composeRule.onNodeWithTag("connection-row").performTouchInput { longClick() }
        composeRule.onNodeWithTag("confirm-delete").performClick()
        composeRule.waitForIdle()
        assertTrue(registry.connections.value.isEmpty())
        composeRule.onNodeWithText("No cluster connected").assertIsDisplayed()
    }

    @Test fun contextDropdownSwitchesContext() {
        showScreen()
        viewModel.openAdd(kubeconfig)
        viewModel.confirmAdd()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("context-dropdown").performClick()
        composeRule.onNodeWithTag("context-item-prod-west").performClick()
        composeRule.waitForIdle()
        assertEquals("prod-west", registry.connections.value.single().contextName)
    }

    @Test fun spec08AboutDialogShowsAttribution() {
        showScreen()
        composeRule.onNodeWithTag("about").performClick()
        composeRule.onNodeWithTag("about-attribution").assertIsDisplayed()
        composeRule.onNodeWithText("Kubernetes Icons Set", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("about-dismiss").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("about-attribution").fetchSemanticsNodes().isEmpty())
    }
}
