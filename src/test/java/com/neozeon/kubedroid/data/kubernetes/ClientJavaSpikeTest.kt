package com.neozeon.kubedroid.data.kubernetes

import io.kubernetes.client.openapi.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 03 dev-plan stage 1 spike: confirm `io.kubernetes:client-java:27.0.0`
 * class-loads and constructs on the JVM/Android unit-test classpath without
 * desugaring. If this test or the build fails on java.time / javax.*
 * transitive needs, add core library desugaring and record it as a spec 03
 * amendment.
 */
class ClientJavaSpikeTest {

    @Test fun apiClientConstructsAndConfigures() {
        val client = ApiClient()
        client.setBasePath("https://api.example.com:6443")
        assertEquals("https://api.example.com:6443", client.basePath)
        client.setConnectTimeout(15_000)
        client.setReadTimeout(30_000)
        assertEquals(15_000, client.connectTimeout)
        assertEquals(30_000, client.readTimeout)
    }
}
