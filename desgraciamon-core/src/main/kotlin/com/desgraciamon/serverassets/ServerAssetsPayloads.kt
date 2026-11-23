package com.desgraciamon.serverassets

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class ServerAssetManifestEntry(
    val kind: String,
    val id: String,
    val ext: String,
    val sha256: String,
    val size: Int
) {
    fun key(): String = "$kind:$id"
}

data class ServerAssetsManifestPayload(
    val entries: List<ServerAssetManifestEntry>
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        const val MAX_ENTRIES = 1024

        val TYPE = CustomPacketPayload.Type<ServerAssetsManifestPayload>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon_core", "server_assets_manifest")
        )

        val CODEC: StreamCodec<FriendlyByteBuf, ServerAssetsManifestPayload> = StreamCodec.of(
            { buf, payload ->
                val list = payload.entries.take(MAX_ENTRIES)
                buf.writeVarInt(list.size)
                for (entry in list) {
                    buf.writeUtf(entry.kind, 16)
                    buf.writeUtf(entry.id, 64)
                    buf.writeUtf(entry.ext, 16)
                    buf.writeUtf(entry.sha256, 64)
                    buf.writeVarInt(entry.size)
                }
            },
            { buf ->
                val count = buf.readVarInt().coerceIn(0, MAX_ENTRIES)
                val entries = ArrayList<ServerAssetManifestEntry>(count)
                for (i in 0 until count) {
                    val kind = buf.readUtf(16)
                    val id = buf.readUtf(64)
                    val ext = buf.readUtf(16)
                    val sha256 = buf.readUtf(64)
                    val size = buf.readVarInt().coerceAtLeast(0)
                    entries.add(ServerAssetManifestEntry(kind, id, ext, sha256, size))
                }
                ServerAssetsManifestPayload(entries)
            }
        )
    }
}

data class ServerAssetRequestPayload(
    val keys: List<String>
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        const val MAX_KEYS = 256

        val TYPE = CustomPacketPayload.Type<ServerAssetRequestPayload>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon_core", "server_asset_request")
        )

        val CODEC: StreamCodec<FriendlyByteBuf, ServerAssetRequestPayload> = StreamCodec.of(
            { buf, payload ->
                val list = payload.keys.take(MAX_KEYS)
                buf.writeVarInt(list.size)
                for (key in list) {
                    buf.writeUtf(key, 96)
                }
            },
            { buf ->
                val count = buf.readVarInt().coerceIn(0, MAX_KEYS)
                val keys = ArrayList<String>(count)
                for (i in 0 until count) {
                    keys.add(buf.readUtf(96))
                }
                ServerAssetRequestPayload(keys)
            }
        )
    }
}

data class ServerAssetChunkPayload(
    val kind: String,
    val id: String,
    val ext: String,
    val sha256: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val data: ByteArray
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        const val CHUNK_SIZE = 24 * 1024
        const val MAX_CHUNK_BYTES = 32 * 1024

        val TYPE = CustomPacketPayload.Type<ServerAssetChunkPayload>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon_core", "server_asset_chunk")
        )

        val CODEC: StreamCodec<FriendlyByteBuf, ServerAssetChunkPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeUtf(payload.kind, 16)
                buf.writeUtf(payload.id, 64)
                buf.writeUtf(payload.ext, 16)
                buf.writeUtf(payload.sha256, 64)
                buf.writeVarInt(payload.chunkIndex)
                buf.writeVarInt(payload.totalChunks)
                buf.writeByteArray(payload.data)
            },
            { buf ->
                val kind = buf.readUtf(16)
                val id = buf.readUtf(64)
                val ext = buf.readUtf(16)
                val sha256 = buf.readUtf(64)
                val chunkIndex = buf.readVarInt().coerceAtLeast(0)
                val totalChunks = buf.readVarInt().coerceAtLeast(1)
                val data = buf.readByteArray(MAX_CHUNK_BYTES)
                ServerAssetChunkPayload(kind, id, ext, sha256, chunkIndex, totalChunks, data)
            }
        )
    }
}

data class PlayServerSoundPayload(
    val id: String,
    val volume: Float = 1.0f
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PlayServerSoundPayload>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon_core", "play_server_sound")
        )

        val CODEC: StreamCodec<FriendlyByteBuf, PlayServerSoundPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeUtf(payload.id, 64)
                buf.writeFloat(payload.volume)
            },
            { buf ->
                val id = buf.readUtf(64)
                val volume = buf.readFloat().coerceIn(0.0f, 2.0f)
                PlayServerSoundPayload(id, volume)
            }
        )
    }
}
