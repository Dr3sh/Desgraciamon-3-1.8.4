package com.desgraciamon.cinematics

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

data class CinematicPreparePayload(
    val routeName: String,
    val routeJson: String,
    val requiredAnimations: List<String>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CinematicPreparePayload> = TYPE

    companion object {
        val ID = ResourceLocation.fromNamespaceAndPath(CinematicsMod.MOD_ID, "prepare")
        val TYPE = CustomPacketPayload.Type<CinematicPreparePayload>(ID)
        val CODEC: StreamCodec<FriendlyByteBuf, CinematicPreparePayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeUtf(payload.routeName)
                buf.writeUtf(payload.routeJson)
                buf.writeVarInt(payload.requiredAnimations.size)
                payload.requiredAnimations.forEach { buf.writeUtf(it) }
            },
            { buf ->
                val routeName = buf.readUtf()
                val routeJson = buf.readUtf()
                val count = buf.readVarInt().coerceIn(0, 256)
                val anims = List(count) { buf.readUtf() }
                CinematicPreparePayload(routeName, routeJson, anims)
            }
        )
    }
}

data class CinematicClientReadyPayload(
    val routeName: String
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CinematicClientReadyPayload> = TYPE

    companion object {
        val ID = ResourceLocation.fromNamespaceAndPath(CinematicsMod.MOD_ID, "client_ready")
        val TYPE = CustomPacketPayload.Type<CinematicClientReadyPayload>(ID)
        val CODEC: StreamCodec<FriendlyByteBuf, CinematicClientReadyPayload> = StreamCodec.of(
            { buf, payload -> buf.writeUtf(payload.routeName) },
            { buf -> CinematicClientReadyPayload(buf.readUtf()) }
        )
    }
}

data class CinematicPlayPayload(
    val routeName: String,
    val routeJson: String,
    val startTimestampMs: Long,
    val serverNowMs: Long = startTimestampMs,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CinematicPlayPayload> = TYPE

    companion object {
        val ID = ResourceLocation.fromNamespaceAndPath(CinematicsMod.MOD_ID, "play")
        val TYPE = CustomPacketPayload.Type<CinematicPlayPayload>(ID)
        val CODEC: StreamCodec<FriendlyByteBuf, CinematicPlayPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeUtf(payload.routeName)
                buf.writeUtf(payload.routeJson)
                buf.writeLong(payload.startTimestampMs)
                buf.writeLong(payload.serverNowMs)
            },
            { buf ->
                CinematicPlayPayload(
                    routeName = buf.readUtf(),
                    routeJson = buf.readUtf(),
                    startTimestampMs = buf.readLong(),
                    serverNowMs = buf.readLong(),
                )
            }
        )
    }
}

data class CinematicHeartbeatPayload(
    val serverElapsedMs: Long,
    val isPaused: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CinematicHeartbeatPayload> = TYPE

    companion object {
        val ID = ResourceLocation.fromNamespaceAndPath(CinematicsMod.MOD_ID, "heartbeat")
        val TYPE = CustomPacketPayload.Type<CinematicHeartbeatPayload>(ID)
        val CODEC: StreamCodec<FriendlyByteBuf, CinematicHeartbeatPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeLong(payload.serverElapsedMs)
                buf.writeBoolean(payload.isPaused)
            },
            { buf ->
                CinematicHeartbeatPayload(
                    serverElapsedMs = buf.readLong(),
                    isPaused = buf.readBoolean()
                )
            }
        )
    }
}

data class CinematicStopPayload(
    val reason: String = "finished"
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CinematicStopPayload> = TYPE

    companion object {
        val ID = ResourceLocation.fromNamespaceAndPath(CinematicsMod.MOD_ID, "stop")
        val TYPE = CustomPacketPayload.Type<CinematicStopPayload>(ID)
        val CODEC: StreamCodec<FriendlyByteBuf, CinematicStopPayload> = StreamCodec.of(
            { buf, payload -> buf.writeUtf(payload.reason) },
            { buf -> CinematicStopPayload(buf.readUtf()) }
        )
    }
}

data class CinematicSkipPayload(
    val routeName: String = ""
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CinematicSkipPayload> = TYPE

    companion object {
        val ID = ResourceLocation.fromNamespaceAndPath(CinematicsMod.MOD_ID, "skip")
        val TYPE = CustomPacketPayload.Type<CinematicSkipPayload>(ID)
        val CODEC: StreamCodec<FriendlyByteBuf, CinematicSkipPayload> = StreamCodec.of(
            { buf, payload -> buf.writeUtf(payload.routeName) },
            { buf -> CinematicSkipPayload(buf.readUtf()) }
        )
    }
}

data class CinematicTriggerPayload(
    val actionType: String,
    val actionValue: String,
    val durationSeconds: Float,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CinematicTriggerPayload> = TYPE

    companion object {
        val ID = ResourceLocation.fromNamespaceAndPath(CinematicsMod.MOD_ID, "trigger")
        val TYPE = CustomPacketPayload.Type<CinematicTriggerPayload>(ID)
        val CODEC: StreamCodec<FriendlyByteBuf, CinematicTriggerPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeUtf(payload.actionType)
                buf.writeUtf(payload.actionValue)
                buf.writeFloat(payload.durationSeconds)
            },
            { buf ->
                CinematicTriggerPayload(
                    actionType = buf.readUtf(),
                    actionValue = buf.readUtf(),
                    durationSeconds = buf.readFloat()
                )
            }
        )
    }
}

data class CinematicEditorSyncPayload(
    val routeJson: String,
    val openGui: Boolean = false
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CinematicEditorSyncPayload> = TYPE

    companion object {
        val ID = ResourceLocation.fromNamespaceAndPath(CinematicsMod.MOD_ID, "editor_sync")
        val TYPE = CustomPacketPayload.Type<CinematicEditorSyncPayload>(ID)
        val CODEC: StreamCodec<FriendlyByteBuf, CinematicEditorSyncPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeUtf(payload.routeJson)
                buf.writeBoolean(payload.openGui)
            },
            { buf ->
                CinematicEditorSyncPayload(
                    routeJson = buf.readUtf(),
                    openGui = buf.readBoolean()
                )
            }
        )
    }
}

object CinematicPackets {
    private var registered = false

    fun registerCommon() {
        if (registered) return
        registered = true

        PayloadTypeRegistry.playS2C().register(CinematicPreparePayload.TYPE, CinematicPreparePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CinematicPlayPayload.TYPE, CinematicPlayPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CinematicHeartbeatPayload.TYPE, CinematicHeartbeatPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CinematicStopPayload.TYPE, CinematicStopPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CinematicTriggerPayload.TYPE, CinematicTriggerPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CinematicEditorSyncPayload.TYPE, CinematicEditorSyncPayload.CODEC)

        PayloadTypeRegistry.playC2S().register(CinematicClientReadyPayload.TYPE, CinematicClientReadyPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CinematicSkipPayload.TYPE, CinematicSkipPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CinematicEditorSyncPayload.TYPE, CinematicEditorSyncPayload.CODEC)
    }

    fun prepare(player: ServerPlayer, routeName: String, routeJson: String, requiredAnimations: List<String>) {
        if (ServerPlayNetworking.canSend(player, CinematicPreparePayload.TYPE)) {
            ServerPlayNetworking.send(player, CinematicPreparePayload(routeName, routeJson, requiredAnimations))
        }
    }

    fun play(player: ServerPlayer, routeName: String, routeJson: String, startTimestampMs: Long, serverNowMs: Long = System.currentTimeMillis()) {
        if (ServerPlayNetworking.canSend(player, CinematicPlayPayload.TYPE)) {
            ServerPlayNetworking.send(player, CinematicPlayPayload(routeName, routeJson, startTimestampMs, serverNowMs))
        }
    }

    fun heartbeat(player: ServerPlayer, serverElapsedMs: Long, isPaused: Boolean) {
        if (ServerPlayNetworking.canSend(player, CinematicHeartbeatPayload.TYPE)) {
            ServerPlayNetworking.send(player, CinematicHeartbeatPayload(serverElapsedMs, isPaused))
        }
    }

    fun stop(player: ServerPlayer, reason: String = "finished") {
        if (ServerPlayNetworking.canSend(player, CinematicStopPayload.TYPE)) {
            ServerPlayNetworking.send(player, CinematicStopPayload(reason))
        }
    }

    fun trigger(player: ServerPlayer, actionType: String, actionValue: String, durationSeconds: Float) {
        if (ServerPlayNetworking.canSend(player, CinematicTriggerPayload.TYPE)) {
            ServerPlayNetworking.send(player, CinematicTriggerPayload(actionType, actionValue, durationSeconds))
        }
    }

    fun openEditorGui(player: ServerPlayer, routeJson: String) {
        if (ServerPlayNetworking.canSend(player, CinematicEditorSyncPayload.TYPE)) {
            ServerPlayNetworking.send(player, CinematicEditorSyncPayload(routeJson, openGui = true))
        }
    }
}
