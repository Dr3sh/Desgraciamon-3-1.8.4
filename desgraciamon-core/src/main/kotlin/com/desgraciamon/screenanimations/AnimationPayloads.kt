package com.desgraciamon.screenanimations

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import java.util.UUID

data class AnimationTransferStartPayload(
    val transferId: UUID,
    val animationName: String,
    val fps: Int,
    val frameCount: Int,
    val chunkCount: Int,
    val totalBytes: Int,
    val scale: Float = 0.6f,
    val fitMode: String = "contain",
    val soundsJson: String = "",
    val prewarmOnly: Boolean = false
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<AnimationTransferStartPayload>(
            ResourceLocation.fromNamespaceAndPath(ScreenAnimationsMod.MOD_ID, "animation_start")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, AnimationTransferStartPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.transferId)
                buffer.writeUtf(payload.animationName, 64)
                buffer.writeVarInt(payload.fps)
                buffer.writeVarInt(payload.frameCount)
                buffer.writeVarInt(payload.chunkCount)
                buffer.writeVarInt(payload.totalBytes)
                buffer.writeFloat(payload.scale)
                buffer.writeUtf(payload.fitMode, 32)
                buffer.writeUtf(payload.soundsJson, 32767)
                buffer.writeBoolean(payload.prewarmOnly)
            },
            { buffer ->
                AnimationTransferStartPayload(
                    buffer.readUUID(),
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readFloat(),
                    buffer.readUtf(32),
                    buffer.readUtf(32767),
                    buffer.readBoolean()
                )
            }
        )
    }
}

data class AnimationTransferChunkPayload(
    val transferId: UUID,
    val index: Int,
    val bytes: ByteArray
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val MAX_CHUNK_BYTES = 256 * 1024
        val TYPE = CustomPacketPayload.Type<AnimationTransferChunkPayload>(
            ResourceLocation.fromNamespaceAndPath(ScreenAnimationsMod.MOD_ID, "animation_chunk")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, AnimationTransferChunkPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.transferId)
                buffer.writeVarInt(payload.index)
                buffer.writeByteArray(payload.bytes)
            },
            { buffer ->
                AnimationTransferChunkPayload(buffer.readUUID(), buffer.readVarInt(), buffer.readByteArray(MAX_CHUNK_BYTES))
            }
        )
    }
}

data object AnimationStopPayload : CustomPacketPayload {
    override fun type() = TYPE

    val TYPE = CustomPacketPayload.Type<AnimationStopPayload>(
        ResourceLocation.fromNamespaceAndPath(ScreenAnimationsMod.MOD_ID, "animation_stop")
    )
    val CODEC: StreamCodec<RegistryFriendlyByteBuf, AnimationStopPayload> = StreamCodec.unit(AnimationStopPayload)
}

data class AnimationReadyPayload(
    val transferId: UUID,
    val animationName: String = "",
    val prewarmed: Boolean = false
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<AnimationReadyPayload>(
            ResourceLocation.fromNamespaceAndPath(ScreenAnimationsMod.MOD_ID, "animation_ready")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, AnimationReadyPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.transferId)
                buffer.writeUtf(payload.animationName, 64)
                buffer.writeBoolean(payload.prewarmed)
            },
            { buffer ->
                AnimationReadyPayload(
                    buffer.readUUID(),
                    buffer.readUtf(64),
                    buffer.readBoolean()
                )
            }
        )
    }
}

data class AnimationPlayInstantPayload(
    val animationName: String,
    val fitMode: String = "contain",
    val scale: Float = 1.0f,
    val streamUrl: String = ""
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<AnimationPlayInstantPayload>(
            ResourceLocation.fromNamespaceAndPath(ScreenAnimationsMod.MOD_ID, "animation_instant")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, AnimationPlayInstantPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUtf(payload.animationName, 64)
                buffer.writeUtf(payload.fitMode, 32)
                buffer.writeFloat(payload.scale)
                buffer.writeUtf(payload.streamUrl, 2048)
            },
            { buffer ->
                AnimationPlayInstantPayload(
                    buffer.readUtf(64),
                    buffer.readUtf(32),
                    buffer.readFloat(),
                    buffer.readUtf(2048)
                )
            }
        )
    }
}

data class AnimationClearCachePayload(
    val animationName: String = ""
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<AnimationClearCachePayload>(
            ResourceLocation.fromNamespaceAndPath(ScreenAnimationsMod.MOD_ID, "animation_clear_cache")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, AnimationClearCachePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUtf(payload.animationName, 64)
            },
            { buffer ->
                AnimationClearCachePayload(buffer.readUtf(64))
            }
        )
    }
}

data class AnimationPlayTriggerPayload(
    val transferId: UUID
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<AnimationPlayTriggerPayload>(
            ResourceLocation.fromNamespaceAndPath(ScreenAnimationsMod.MOD_ID, "animation_trigger")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, AnimationPlayTriggerPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.transferId)
            },
            { buffer ->
                AnimationPlayTriggerPayload(buffer.readUUID())
            }
        )
    }
}
