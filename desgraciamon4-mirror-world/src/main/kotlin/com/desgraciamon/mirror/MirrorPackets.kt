package com.desgraciamon.mirror

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

data class MirrorFadePayload(
    val fadeInMs: Int,
    val holdMs: Int,
    val fadeOutMs: Int,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MirrorFadePayload> = TYPE

    companion object {
        val ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MirrorRules.MOD_ID, "black_fade")
        val TYPE = CustomPacketPayload.Type<MirrorFadePayload>(ID)
        val CODEC: StreamCodec<FriendlyByteBuf, MirrorFadePayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeVarInt(payload.fadeInMs)
                buf.writeVarInt(payload.holdMs)
                buf.writeVarInt(payload.fadeOutMs)
            },
            { buf ->
                MirrorFadePayload(
                    fadeInMs = buf.readVarInt(),
                    holdMs = buf.readVarInt(),
                    fadeOutMs = buf.readVarInt(),
                )
            }
        )
    }
}

object MirrorPackets {
    private var registered = false

    fun registerCommon() {
        if (registered) return
        registered = true
        PayloadTypeRegistry.playS2C().register(MirrorFadePayload.TYPE, MirrorFadePayload.CODEC)
    }

    fun send(player: ServerPlayer, fadeInMs: Int = 1800, holdMs: Int = 500, fadeOutMs: Int = 1800) {
        if (ServerPlayNetworking.canSend(player, MirrorFadePayload.TYPE)) {
            ServerPlayNetworking.send(player, MirrorFadePayload(fadeInMs, holdMs, fadeOutMs))
        }
    }
}

