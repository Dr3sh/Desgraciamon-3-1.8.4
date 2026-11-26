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

data class MirrorOriginsPayload(
    val origins: Map<Int, String>
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MirrorOriginsPayload> = TYPE

    companion object {
        val ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MirrorRules.MOD_ID, "memory_origins")
        val TYPE = CustomPacketPayload.Type<MirrorOriginsPayload>(ID)
        val CODEC: StreamCodec<FriendlyByteBuf, MirrorOriginsPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeVarInt(payload.origins.size)
                for ((entityId, origin) in payload.origins) {
                    buf.writeVarInt(entityId)
                    buf.writeUtf(origin, 64)
                }
            },
            { buf ->
                val count = buf.readVarInt().coerceIn(0, 512)
                val map = LinkedHashMap<Int, String>(count)
                repeat(count) {
                    val eid = buf.readVarInt()
                    val origin = buf.readUtf(64)
                    map[eid] = origin
                }
                MirrorOriginsPayload(map)
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
        PayloadTypeRegistry.playS2C().register(MirrorOriginsPayload.TYPE, MirrorOriginsPayload.CODEC)
    }

    fun send(player: ServerPlayer, fadeInMs: Int = 1800, holdMs: Int = 500, fadeOutMs: Int = 1800) {
        if (ServerPlayNetworking.canSend(player, MirrorFadePayload.TYPE)) {
            ServerPlayNetworking.send(player, MirrorFadePayload(fadeInMs, holdMs, fadeOutMs))
        }
    }

    fun sendOrigins(player: ServerPlayer, origins: Map<Int, String>) {
        if (origins.isEmpty()) return
        if (ServerPlayNetworking.canSend(player, MirrorOriginsPayload.TYPE)) {
            ServerPlayNetworking.send(player, MirrorOriginsPayload(origins))
        }
    }
}

