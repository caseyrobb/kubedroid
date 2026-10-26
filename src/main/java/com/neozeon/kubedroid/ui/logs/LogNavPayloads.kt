package com.neozeon.kubedroid.ui.logs

import java.util.UUID

/**
 * Spec 05: one-shot hand-off of the pod's container names from the detail
 * screen (which already holds the parsed pod object) to the log screen, so
 * the viewer never re-GETs the pod. Process death falls back to the VM's
 * one-shot detail fetch — the payload is a cache, not a requirement.
 */
object LogNavPayloads {
    private data class Key(
        val connectionId: UUID,
        val namespace: String,
        val podName: String,
    )

    private val payloads = mutableMapOf<Key, Pair<List<String>, List<String>>>()

    fun put(
        connectionId: UUID,
        namespace: String,
        podName: String,
        containers: Pair<List<String>, List<String>>,
    ) {
        payloads[Key(connectionId, namespace, podName)] = containers
    }

    fun take(
        connectionId: UUID,
        namespace: String,
        podName: String,
    ): Pair<List<String>, List<String>>? = payloads.remove(Key(connectionId, namespace, podName))

    fun clear() = payloads.clear()
}
