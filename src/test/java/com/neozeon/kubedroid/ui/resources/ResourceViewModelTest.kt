package com.neozeon.kubedroid.ui.resources

import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourceRef
import com.neozeon.kubedroid.data.model.RedactedValue
import com.neozeon.kubedroid.testing.ResourceFixtures
import com.neozeon.kubedroid.testing.ResourceTestHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 04 stage 3 Testing: list state machine (load, search, namespace
 * switch, infinite scroll, stale-response drop) and detail reveal flow,
 * against the shared canned gateway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResourceViewModelTest {

    private fun listVm(h: ResourceTestHarness, dispatcher: kotlinx.coroutines.test.TestDispatcher) =
        ResourceListViewModel(h.repository, kotlinx.coroutines.CoroutineScope(dispatcher))

    @Test fun loadPopulatesItemsAndToken() = runTest {
        val h = ResourceTestHarness({ _, _ ->
            200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("a", app = "x"), ResourceFixtures.pod("b")), cont = "NEXT")
        })
        val vm = listVm(h, StandardTestDispatcher(testScheduler))
        vm.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        runCurrent()
        val s = vm.state.value
        assertEquals(listOf("a", "b"), s.items.map { it.name })
        assertEquals("NEXT", s.continueToken)
        assertEquals(false, s.loading)
    }

    @Test fun searchFiltersByNameAndLabels() = runTest {
        val h = ResourceTestHarness({ _, _ ->
            200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("alpha", app = "web"), ResourceFixtures.pod("beta", app = "db")))
        })
        val vm = listVm(h, StandardTestDispatcher(testScheduler))
        vm.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        runCurrent()
        vm.onSearch("web")
        assertEquals(listOf("alpha"), vm.state.value.visibleItems.map { it.name })
        vm.onSearch("bet")
        assertEquals(listOf("beta"), vm.state.value.visibleItems.map { it.name })
        vm.onSearch("")
        assertEquals(2, vm.state.value.visibleItems.size)
    }

    @Test fun namespaceSwitchRequeriesWithScope() = runTest {
        val h = ResourceTestHarness({ path, _ ->
            if (path.endsWith("namespaces")) 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("p", ns = "default")))
            else 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("scoped", ns = "kube-system")))
        })
        val vm = listVm(h, StandardTestDispatcher(testScheduler))
        vm.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        runCurrent()
        vm.selectNamespace("kube-system")
        runCurrent()
        assertEquals(listOf("scoped"), vm.state.value.items.map { it.name })
        assertEquals("kube-system", vm.state.value.namespace)
        assertTrue(h.lastSeen().contains("/namespaces/kube-system/pods"))
    }

    @Test fun forbiddenSurfacesWithScope() = runTest {
        val h = ResourceTestHarness({ path, _ -> if (path.endsWith("secrets")) 403 to "{}" else 200 to "{}" })
        val vm = listVm(h, StandardTestDispatcher(testScheduler))
        vm.open(ResourceListViewModel.Target(h.connectionId, "v1", "secrets"))
        runCurrent()
        val err = vm.state.value.error
        assertTrue("expected Forbidden, got $err", err is ResourceError.Forbidden)
    }

    @Test fun loadMoreAppendsUntilEnd() = runTest {
        var page = 0
        val h = ResourceTestHarness({ path, _ ->
            if (path.endsWith("namespaces")) 200 to ResourceFixtures.itemsJson(emptyList())
            else {
                page++
                if (page == 1) 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("p1")), cont = "CONT")
                else 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("p2")))
            }
        })
        val vm = listVm(h, StandardTestDispatcher(testScheduler))
        vm.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        runCurrent()
        vm.loadMore()
        runCurrent()
        assertEquals(listOf("p1", "p2"), vm.state.value.items.map { it.name })
        assertNull(vm.state.value.continueToken)
        vm.loadMore() // no token: no further request
        runCurrent()
        assertEquals(2, vm.state.value.items.size)
    }

    @Test fun staleOpenResponseIsDropped() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = ResourceTestHarness({ path, _ ->
            when {
                path.endsWith("namespaces") -> 200 to ResourceFixtures.itemsJson(emptyList())
                path.endsWith("pods") -> 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("first")))
                else -> 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("second"))) // deployments path
            }
        })
        val vm = ResourceListViewModel(h.repository, kotlinx.coroutines.CoroutineScope(dispatcher))
        vm.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        // supersede BEFORE the first fetch job runs: it must never apply
        vm.open(ResourceListViewModel.Target(h.connectionId, "apps/v1", "deployments"))
        runCurrent()
        assertEquals(listOf("second"), vm.state.value.items.map { it.name })
        assertEquals("deployments", vm.state.value.target?.resource)
    }

    @Test fun shouldFetchMoreOnlyNearEndWithToken() {
        assertEquals(true, shouldFetchMore(45, 50, hasToken = true, loading = false))
        assertEquals(false, shouldFetchMore(10, 50, hasToken = true, loading = false))
        assertEquals(false, shouldFetchMore(49, 50, hasToken = false, loading = false))
        assertEquals(false, shouldFetchMore(49, 50, hasToken = true, loading = true))
        assertEquals(false, shouldFetchMore(null, 50, hasToken = true, loading = false))
    }

    @Test fun detailRevealFlow() = runTest {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.secret() })
        val vm = ResourceDetailViewModel(h.repository, kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(testScheduler)))
        vm.open(ResourceRef(h.connectionId, "v1", "secrets", "web", "db"))
        runCurrent()
        val redacted = (vm.state.value.obj!!["data"] as Map<*, *>)["pw"]
        assertEquals(1, (redacted as RedactedValue).decodedByteLength)
        vm.reveal()
        runCurrent()
        assertEquals("eA==", (vm.state.value.obj!!["data"] as Map<*, *>)["pw"])
        assertEquals(true, vm.state.value.revealed)
        // YAML tab on revealed content contains the raw value
        vm.selectTab(1)
        assertEquals(true, vm.state.value.yaml!!.contains("eA=="))
    }
}
