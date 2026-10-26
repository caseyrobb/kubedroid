package com.neozeon.kubedroid.data.repository

import com.neozeon.kubedroid.data.kubernetes.ApiClientProvider
import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.kubernetes.ResourceGateway
import com.neozeon.kubedroid.data.kubernetes.RetryBackoff
import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.local.FakeCipherVault
import com.neozeon.kubedroid.data.local.InMemoryClusterRegistry
import com.neozeon.kubedroid.data.local.WallClock
import com.neozeon.kubedroid.data.model.DeleteResult
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourceRef
import com.neozeon.kubedroid.data.model.ResourceResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 06 stage 1 Testing: repository mutation error mapping via a canned
 * gateway (same pattern as ResourceRepositoryTest, but method-aware so PUT
 * and DELETE can answer differently).
 */
class ResourceRepositoryMutationTest {

    private class MutationGateway(dir: File, private val handler: (Request) -> Pair<Int, String>) :
        ResourceGateway(
            ApiClientProvider(BlobStore(dir, FakeCipherVault()), InMemoryClusterRegistry(), KubeconfigParser()),
            backoff = RetryBackoff { },
        ) {
        val seen = mutableListOf<Request>()
        private val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                seen += request
                val (code, body) = handler(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("canned")
                    .body(body.toResponseBody(null))
                    .build()
            })
            .build()

        override suspend fun resolve(connectionId: UUID, context: String): Resolved =
            Resolved("http://fake", http)
    }

    private var connectionId = UUID.randomUUID()

    private fun repoWith(handler: (Request) -> Pair<Int, String>): Pair<ResourceRepository, MutationGateway> {
        val dir = kotlin.io.path.createTempDirectory("resrepmut").toFile()
        val registry = InMemoryClusterRegistry()
        val gateway = MutationGateway(dir, handler)
        val clusterRepo = ClusterRepository(
            registry, BlobStore(dir, FakeCipherVault()),
            ApiClientProvider(BlobStore(dir, FakeCipherVault()), registry, KubeconfigParser()),
            WallClock { 1L },
            KubeconfigParser(),
        )
        runBlocking {
            val r = clusterRepo.add(
                """
                apiVersion: v1
                kind: Config
                current-context: ctx1
                clusters: [{name: c1, cluster: {server: "https://x:6443"}}]
                users: [{name: u1, user: {token: t}}]
                contexts: [{name: ctx1, context: {cluster: c1, user: u1}}]
                """.trimIndent(),
                "T", "ctx1",
            )
            connectionId = (r as com.neozeon.kubedroid.data.model.AddResult.Added).connection.id
        }
        return ResourceRepository(gateway, clusterRepo) to gateway
    }

    private val podRef
        get() = ResourceRef(connectionId, "v1", "pods", "web", "web-1")

    @Test fun replaceSuccessReturnsOkAndSendsJsonBody() = runBlocking {
        val (repo, gw) = repoWith { 200 to """{"metadata":{"name":"web-1"}}""" }
        val r = repo.replace(podRef, mapOf("apiVersion" to "v1", "kind" to "Pod"))
        assertTrue("expected Ok, got $r", r is ResourceResult.Ok)
        val req = gw.seen.single()
        assertEquals("PUT", req.method)
        assertEquals("/api/v1/namespaces/web/pods/web-1", req.url.encodedPath)
        // application interceptors run before BridgeInterceptor adds the
        // header, so assert the media type the body itself carries
        assertEquals("application/json; charset=utf-8", req.body?.contentType()?.toString())
    }

    @Test fun replaceConflictMapsToConflictError() = runBlocking {
        val (repo, _) = repoWith { 409 to """{"kind":"Status","message":"the object has been modified","code":409}""" }
        val r = repo.replace(podRef, emptyMap())
        val err = (r as ResourceResult.Err).error
        assertTrue("expected Conflict, got $err", err is ResourceError.Conflict)
        err as ResourceError.Conflict
        assertEquals("Pod", err.kind)
        assertEquals("web-1", err.name)
        assertTrue(err.userMessage().contains("nothing was overwritten"))
    }

    @Test fun replaceUnprocessableSurfacesApiserverMessageVerbatim() = runBlocking {
        val (repo, _) = repoWith {
            422 to """{"kind":"Status","message":"spec.containers[0].image: Required value","reason":"Invalid","code":422}"""
        }
        val r = repo.replace(podRef, emptyMap())
        val err = (r as ResourceResult.Err).error
        assertTrue("expected Invalid, got $err", err is ResourceError.Invalid)
        assertTrue(
            "apiserver message must appear verbatim",
            err.userMessage().contains("spec.containers[0].image: Required value"),
        )
    }

    @Test fun replaceForbiddenNamesWriteVerb() = runBlocking {
        val (repo, _) = repoWith { 403 to """{"kind":"Status","message":"nope","code":403}""" }
        val r = repo.replace(podRef, emptyMap())
        val err = (r as ResourceResult.Err).error
        assertTrue("expected ForbiddenVerb, got $err", err is ResourceError.ForbiddenVerb)
        err as ResourceError.ForbiddenVerb
        assertEquals("update", err.verb)
        assertEquals("Pod", err.kind)
        assertEquals("web", err.namespace)
        assertTrue(err.userMessage().contains("update Pod"))
    }

    @Test fun deleteSuccessAndAlreadyGoneBothSucceed() = runBlocking {
        val (repoExist, _) = repoWith { 200 to """{"kind":"Status","status":"Success"}""" }
        val ok = repoExist.delete(podRef) as ResourceResult.Ok
        assertTrue("expected existed=true", ok.value.existed)

        val (repoGone, _) = repoWith { 404 to """{"kind":"Status","reason":"NotFound","code":404}""" }
        val gone = repoGone.delete(podRef) as ResourceResult.Ok
        assertEquals(DeleteResult(existed = false), gone.value)
    }

    @Test fun deleteForbiddenNamesDeleteVerb() = runBlocking {
        val (repo, _) = repoWith { 403 to """{"kind":"Status","message":"nope","code":403}""" }
        val r = repo.delete(podRef)
        val err = (r as ResourceResult.Err).error
        assertTrue("expected ForbiddenVerb(delete), got $err", err is ResourceError.ForbiddenVerb)
        assertEquals("delete", (err as ResourceError.ForbiddenVerb).verb)
    }

    @Test fun serverErrorMapsToServerCode() = runBlocking {
        val (repo, _) = repoWith { 500 to "boom" }
        val r = repo.delete(podRef)
        val err = (r as ResourceResult.Err).error
        assertTrue("expected Server(500), got $err", err is ResourceError.Server && err.code == 500)
    }
}
