package com.desgraciamon.catchassistant

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class LegendaryCaptureView(
    val speciesId: String,
    val speciesName: String,
    val capturedBy: String,
    val capturedAt: Long,
    val nextCaptureAt: Long,
    val shiny: Boolean = false
)

data class LegendaryCaptureSyncPayload(val entries: List<LegendaryCaptureView>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<LegendaryCaptureSyncPayload>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon_catch_assistant", "capture_sync")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, LegendaryCaptureSyncPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.entries.size)
                payload.entries.forEach { entry ->
                    buffer.writeUtf(entry.speciesId)
                    buffer.writeUtf(entry.speciesName)
                    buffer.writeUtf(entry.capturedBy)
                    buffer.writeLong(entry.capturedAt)
                    buffer.writeLong(entry.nextCaptureAt)
                    buffer.writeBoolean(entry.shiny)
                }
            },
            { buffer ->
                LegendaryCaptureSyncPayload(List(buffer.readVarInt().coerceIn(0, 4096)) {
                    LegendaryCaptureView(
                        buffer.readUtf(), buffer.readUtf(), buffer.readUtf(), buffer.readLong(), buffer.readLong(), buffer.readBoolean()
                    )
                })
            }
        )
    }
}

object LegendaryCaptureSyncRequest : CustomPacketPayload {
    override fun type() = TYPE

    val TYPE = CustomPacketPayload.Type<LegendaryCaptureSyncRequest>(
        ResourceLocation.fromNamespaceAndPath("desgraciamon_catch_assistant", "capture_sync_request")
    )
    val CODEC: StreamCodec<RegistryFriendlyByteBuf, LegendaryCaptureSyncRequest> = StreamCodec.unit(LegendaryCaptureSyncRequest)
}

object LegendaryCaptureClientData {
    var entries: List<LegendaryCaptureView> = emptyList()
}