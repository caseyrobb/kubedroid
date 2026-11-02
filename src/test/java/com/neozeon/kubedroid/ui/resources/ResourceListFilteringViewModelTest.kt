package com.neozeon.kubedroid.ui.resources

import com.neozeon.kubedroid.testing.ResourceFixtures
import com.neozeon.kubedroid.testing.ResourceTestHarness
import com.neozeon.kubedroid.ui.status.PodStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 12 stage 3 ViewModel tests: sort/status chip state is pure client
 * side; label chips re-query the server with an AND-joined labelSelector
 * (Q3 decision).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResourceListFilteringViewModelTest {

    private fun vm(h: ResourceTestHarness, dispatcher: kotlinx.coroutines.test.TestDispatcher) =
        ResourceListViewModel(h.repository, kotlinx.coroutines.CoroutineScope(dispatcher))

    private val pods = listOf(
        ResourceFixtures.pod("good", app = "web"),
        ResourceFixtures.pod("bad", app = "web", phase = "Failed"),
        ResourceFixtures.pod("meh", app = "db", phase = "Pending"),
    )

    @Test fun rowsCarryResolvedStatusTokens() = runTest {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.itemsJson(pods) })
        val v = vm(h, StandardTestDispatcher(testScheduler))
        v.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        runCurrent()
        val items = v.state.value.items.associateBy { it.name }
        assertEquals(PodStatus.RUNNING, items.getValue("good").statusToken)
        assertEquals(PodStatus.FAILED, items.getValue("bad").statusToken)
        assertEquals(PodStatus.PENDING, items.getValue("meh").statusToken)
    }

    @Test fun statusFilterChipsFilterClientSide() = runTest {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.itemsJson(pods) })
        val v = vm(h, StandardTestDispatcher(testScheduler))
        v.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        runCurrent()
        v.selectStatusFilter(StatusFilter.FAILED)
        assertEquals(listOf("bad"), v.state.value.visibleItems.map { it.name })
        v.selectStatusFilter(StatusFilter.PENDING)
        assertEquals(listOf("meh"), v.state.value.visibleItems.map { it.name })
        v.selectStatusFilter(StatusFilter.ALL)
        assertEquals(3, v.state.value.visibleItems.size)
        assertEquals(0, h.seen.count { it.contains("labelSelector") }) // chips made NO new request
    }

    @Test fun severitySortSurfacesProblemsFirst() = runTest {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.itemsJson(pods) })
        val v = vm(h, StandardTestDispatcher(testScheduler))
        v.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        runCurrent()
        v.selectSort(ListSort.SEVERITY)
        assertEquals(listOf("bad", "meh", "good"), v.state.value.visibleItems.map { it.name })
    }

    @Test fun labelChipRequeriesServerWithSelector() = runTest {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.itemsJson(pods) })
        val v = vm(h, StandardTestDispatcher(testScheduler))
        v.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        runCurrent()
        v.toggleLabel("app=web")
        runCurrent()
        assertTrue(
            "expected labelSelector in ${h.seen}",
            h.seen.any { it.contains("labelSelector=app%3Dweb") },
        )
        // toggle off re-queries WITHOUT the selector
        v.toggleLabel("app=web")
        runCurrent()
        assertTrue(h.seen.last().let { !it.contains("labelSelector") })
        assertEquals(emptySet<String>(), v.state.value.labelFilters)
    }

    @Test fun twoLabelChipsAndJoin() = runTest {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.itemsJson(pods) })
        val v = vm(h, StandardTestDispatcher(testScheduler))
        v.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        runCurrent()
        v.toggleLabel("app=web")
        runCurrent()
        v.toggleLabel("tier=front")
        runCurrent()
        assertTrue(
            "expected AND join in ${h.seen.last()}",
            h.seen.last().contains("labelSelector=app%3Dweb%2Ctier%3Dfront"),
        )
    }
}
