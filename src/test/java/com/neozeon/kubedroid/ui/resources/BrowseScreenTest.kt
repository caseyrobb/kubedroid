package com.neozeon.kubedroid.ui.resources

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec 04 stage 1 Testing (Robolectric Compose): the browse destination
 * renders the connection display name + active context from the repository
 * and exposes back navigation. Grid content arrives with later stages.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BrowseScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

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
        users:
        - name: u1
          user:
            token: UI-TEST-TOKEN
    """.trimIndent()

    private fun seededRepository(): Pair<ClusterRepository, java.util.UUID> {
        val registry = InMemoryClusterRegistry()
        val blobStore = BlobStore(tmp.newFolder(), FakeCipherVault())
        val parser = KubeconfigParser()
        val repository = ClusterRepository(
            registry, blobStore, ApiClientProvider(blobStore, registry, parser),
            WallClock { 1L }, parser,
        )
        runBlocking { repository.add(kubeconfig, "Chaos", "prod-east") }
        val id = registry.connections.value.single().id
        return repository to id
    }

    @Test fun rendersConnectionDisplayNameAndContext() {
        val (repository, id) = seededRepository()
        var backCount = 0
        composeRule.setContent {
            KubedroidTheme {
                BrowseScreen(
                    connectionId = id,
                    viewModel = BrowseViewModel(repository),
                    onBack = { backCount++ },
                )
            }
        }
        composeRule.onNodeWithText("Chaos · prod-east").assertIsDisplayed()
        composeRule.onNodeWithText("Resource browsing lands here (spec 04, stages 2-4)")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("browse-back").performClick()
        assertEquals(1, backCount)
    }

    @Test fun unknownConnectionIdDegradesToGenericTitle() {
        // spec 04 stage 1: deleted/unknown connection must never crash the
        // browse screen (row can vanish while this entry is on the stack).
        val (repository, _) = seededRepository()
        composeRule.setContent {
            KubedroidTheme {
                BrowseScreen(
                    connectionId = java.util.UUID.randomUUID(),
                    viewModel = BrowseViewModel(repository),
                )
            }
        }
        composeRule.onNodeWithText("Browse").assertIsDisplayed()
    }
}
