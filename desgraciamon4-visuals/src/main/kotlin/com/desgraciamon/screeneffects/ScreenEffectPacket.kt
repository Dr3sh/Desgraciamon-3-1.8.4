package com.desgraciamon.screeneffects

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

data class ScreenEffectPacket(
    val effectType: String,
    val color: Int,
    val fadeInMs: Int,
    val durationMs: Int,
    val fadeOutMs: Int,
    val intensity: Float,
    val startEpochMs: Long,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ScreenEffectPacket> = TYPE

    companion object {
        val ID = ResourceLocation.fromNamespaceAndPath("desgraciamon4_visuals", "screen_effect")
        val TYPE = CustomPacketPayload.Type<ScreenEffectPacket>(ID)
        val CODEC: StreamCodec<FriendlyByteBuf, ScreenEffectPacket> = StreamCodec.of(
            { buf, payload ->
                buf.writeUtf(payload.effectType)
                buf.writeInt(payload.color)
                buf.writeInt(payload.fadeInMs)
                buf.writeInt(payload.durationMs)
                buf.writeInt(payload.fadeOutMs)
                buf.writeFloat(payload.intensity)
                buf.writeLong(payload.startEpochMs)
            },
            { buf ->
                ScreenEffectPacket(
                    effectType = buf.readUtf(),
                    color = buf.readInt(),
                    fadeInMs = buf.readInt(),
                    durationMs = buf.readInt(),
                    fadeOutMs = buf.readInt(),
                    intensity = buf.readFloat(),
                    startEpochMs = buf.readLong(),
                )
            }
        )

        private var registered = false
        fun registerCommon() {
            if (registered) return
            registered = true
            PayloadTypeRegistry.playS2C().register(TYPE, CODEC)
        }

        fun send(
            players: Collection<ServerPlayer>,
            effectType: String,
            color: Int = 0x000000,
            fadeInMs: Int = 1000,
            durationMs: Int = 2000,
            fadeOutMs: Int = 1000,
            intensity: Float = 1.0f,
            startEpochMs: Long = System.currentTimeMillis() + 60L
        ) {
            val packet = ScreenEffectPacket(
                effectType = effectType.uppercase(),
                color = color,
                fadeInMs = fadeInMs,
                durationMs = durationMs,
                fadeOutMs = fadeOutMs,
                intensity = intensity,
                startEpochMs = startEpochMs
            )
            for (player in players) {
                ServerPlayNetworking.send(player, packet)
            }
        }
    }
}

