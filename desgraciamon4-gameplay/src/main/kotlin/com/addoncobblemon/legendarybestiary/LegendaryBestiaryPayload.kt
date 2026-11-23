package com.addoncobblemon.legendarybestiary

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class LegendaryBestiaryPayload(val entries: List<LegendaryBestiaryEntry>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<LegendaryBestiaryPayload>(
            ResourceLocation.fromNamespaceAndPath("legendary_bestiary", "entries")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, LegendaryBestiaryPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.entries.size)
                payload.entries.forEach { entry ->
                    buffer.writeUtf(entry.speciesId)
                    buffer.writeUtf(entry.name)
                    buffer.writeBoolean(entry.captured)
                    buffer.writeUtf(entry.originalOwner)
                    buffer.writeUtf(entry.currentOwner)
                    buffer.writeLong(entry.capturedAt)
                    buffer.writeLong(entry.nextCaptureAt)
                }
            },
            { buffer ->
                LegendaryBestiaryPayload(List(buffer.readVarInt().coerceIn(0, 4096)) {
                    LegendaryBestiaryEntry(
                        speciesId = buffer.readUtf(),
                        name = buffer.readUtf(),
                        captured = buffer.readBoolean(),
                        originalOwner = buffer.readUtf(),
                        currentOwner = buffer.readUtf(),
                        capturedAt = buffer.readLong(),
                        nextCaptureAt = buffer.readLong()
                    )
                })
            }
        )
    }
}

object LegendaryBestiaryRequest : CustomPacketPayload {
    override fun type() = TYPE

    val TYPE = CustomPacketPayload.Type<LegendaryBestiaryRequest>(
        ResourceLocation.fromNamespaceAndPath("legendary_bestiary", "request")
    )
    val CODEC: StreamCodec<RegistryFriendlyByteBuf, LegendaryBestiaryRequest> = StreamCodec.unit(this)
}

object LegendaryBestiaryClientData {
    var entries: List<LegendaryBestiaryEntry> = emptyList()
}
