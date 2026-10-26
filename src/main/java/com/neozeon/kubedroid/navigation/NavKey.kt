package com.neozeon.kubedroid.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

// All navigation keys live here and must be @Serializable so Nav3's
// saveable-state decorator can persist them across process death.

/** kotlinx-serialization has no built-in UUID codec; store as its string form. */
private object UuidAsStringSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

@Serializable
object HomeKey : NavKey

/** Resource type grid for one connection (spec 04 "Navigation"). */
@Serializable
data class BrowseKey(
    @Serializable(with = UuidAsStringSerializer::class)
    val connectionId: UUID,
) : NavKey

/** Resource list for one type (spec 04 "ResourceListScreen"). */
@Serializable
data class ResourceListKey(
    @Serializable(with = UuidAsStringSerializer::class)
    val connectionId: UUID,
    val apiVersion: String,
    val resource: String,
) : NavKey

/** Discovered CRD list for one connection (spec 07 "CrdListScreen"). */
@Serializable
data class CrdListKey(
    @Serializable(with = UuidAsStringSerializer::class)
    val connectionId: UUID,
) : NavKey

/** Object detail (spec 04 "ResourceDetailScreen"); cluster-scope objects have no namespace. */
@Serializable
data class ResourceDetailKey(
    @Serializable(with = UuidAsStringSerializer::class)
    val connectionId: UUID,
    val apiVersion: String,
    val resource: String,
    val namespace: String?,
    val name: String,
) : NavKey

/** Pod log viewer (spec 05 "Navigation"); container nullable = auto-select. */
@Serializable
data class LogKey(
    @Serializable(with = UuidAsStringSerializer::class)
    val connectionId: UUID,
    val namespace: String,
    val podName: String,
    val container: String?,
) : NavKey
