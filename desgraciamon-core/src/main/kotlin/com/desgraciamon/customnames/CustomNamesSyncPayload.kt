package com.desgraciamon.customnames

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import java.util.UUID

data class CustomNamesSyncPayload(
    val names: Map<UUID, String>
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<CustomNamesSyncPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<CustomNamesSyncPayload>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon_core", "custom_names_sync")
        )

        val CODEC: StreamCodec<FriendlyByteBuf, CustomNamesSyncPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeVarInt(payload.names.size)
                for ((uuid, name) in payload.names) {
                    buf.writeUUID(uuid)
                    buf.writeUtf(name)
                }
            },
            { buf ->
                val size = buf.readVarInt()
                val map = HashMap<UUID, String>(size)
                for (i in 0 until size) {
                    val uuid = buf.readUUID()
                    val name = buf.readUtf()
                    map[uuid] = name
                }
                CustomNamesSyncPayload(map)
            }
        )
    }
}

