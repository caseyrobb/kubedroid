package com.neozeon.kubedroid.ui.resources

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
        val vm = BrowseViewModel(repository)
        composeRule.setContent {
            KubedroidTheme {
                BrowseScreen(
                    connectionId = id,
                    viewModel = vm,
                    onBack = { backCount++ },
                )
            }
        }
        composeRule.onNodeWithText("Chaos · prod-east").assertIsDisplayed()
        // spec 04 stage 4: real catalog grid replaced the stage-1 placeholder
        composeRule.onNodeWithTag("resource-grid").assertIsDisplayed()
        composeRule.onNodeWithText("Pods").assertIsDisplayed()
        composeRule.onNodeWithTag("group-WORKLOADS").assertIsDisplayed()
        composeRule.onNodeWithTag("browse-back").performClick()
        assertEquals(1, backCount)
    }

    @Test fun typeCardOpensResourceList() {
        val (repository, id) = seededRepository()
        var opened: com.neozeon.kubedroid.navigation.ResourceListKey? = null
        val vm = BrowseViewModel(repository)
        composeRule.setContent {
            KubedroidTheme {
                BrowseScreen(
                    connectionId = id,
                    viewModel = vm,
                    onOpenType = { opened = it },
                )
            }
        }
        composeRule.onNodeWithTag("type-v1/pods").performClick()
        assertEquals("pods", opened?.resource)
        assertEquals("v1", opened?.apiVersion)
        assertEquals(id, opened?.connectionId)
    }

    @Test fun customResourcesCardOpensCrdList() {
        // spec 07 stage 2: the Browse grid gains exactly one CUSTOM card.
        val (repository, id) = seededRepository()
        var crdsOpened = 0
        val vm = BrowseViewModel(repository)
        composeRule.setContent {
            KubedroidTheme {
                BrowseScreen(
                    connectionId = id,
                    viewModel = vm,
                    onOpenCrds = { crdsOpened++ },
                )
            }
        }
        // CUSTOM sits after ~25 static cards in the LazyVerticalGrid: it is
        // not composed until scrolled to (off-screen = absent, not clipped).
        val staticItems = com.neozeon.kubedroid.data.kubernetes.ResourceCatalog
            .byGroup.values.sumOf { it.size } +
            com.neozeon.kubedroid.data.kubernetes.ResourceCatalog.byGroup.count { it.value.isNotEmpty() }
        composeRule.onNode(androidx.compose.ui.test.hasScrollToIndexAction())
            .performScrollToIndex(staticItems + 1) // custom header + card: card is last
        composeRule.onNodeWithTag("group-CUSTOM").assertIsDisplayed()
        composeRule.onNodeWithTag("custom-resources-card").performClick()
        assertEquals(1, crdsOpened)
    }

    @Test fun unknownConnectionIdDegradesToGenericTitle() {
        // spec 04 stage 1: deleted/unknown connection must never crash the
        // browse screen (row can vanish while this entry is on the stack).
        val (repository, _) = seededRepository()
        val vm = BrowseViewModel(repository)
        composeRule.setContent {
            KubedroidTheme {
                BrowseScreen(
                    connectionId = java.util.UUID.randomUUID(),
                    viewModel = vm,
                )
            }
        }
        composeRule.onNodeWithText("Browse").assertIsDisplayed()
    }

    // ---- spec 08 stage 2: official kind icons on the browse grid ----

    @Test fun browseTilesShowOfficialIcons() {
        val (repository, id) = seededRepository()
        val vm = BrowseViewModel(repository)
        composeRule.setContent {
            KubedroidTheme {
                BrowseScreen(
                    connectionId = id,
                    viewModel = vm,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
        // Q6A: every catalog tile carries its official icon; the Custom
        // Resources card carries crd.svg. (Assertions target contentDescription:
        // the clickable Card merges descendant semantics, hiding child testTags.)
        composeRule.onNodeWithContentDescription("Pod").assertIsDisplayed()
        // LazyVerticalGrid only composes on-screen items — scroll until the
        // bottom card materialises.
        val grid = composeRule.onNodeWithTag("resource-grid")
        for (i in 0 until 30) {
            runCatching { grid.performScrollToIndex(i) }
            if (composeRule.onAllNodesWithContentDescription("CustomResourceDefinition")
                    .fetchSemanticsNodes().isNotEmpty()
            ) break
        }
        composeRule.onNodeWithContentDescription("CustomResourceDefinition").assertIsDisplayed()
    }
}
