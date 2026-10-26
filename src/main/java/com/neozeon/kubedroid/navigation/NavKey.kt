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
