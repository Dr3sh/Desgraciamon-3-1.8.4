package com.desgraciamon.announcements

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class BulletinSyncPayload(val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BulletinSyncPayload>(ResourceLocation.fromNamespaceAndPath("desgraciamon4_announcements", "sync"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BulletinSyncPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUtf(payload.json, 1048576) },
            { buffer -> BulletinSyncPayload(buffer.readUtf(1048576)) }
        )
    }
}

data class BulletinActionPayload(val action: String, val id: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BulletinActionPayload>(ResourceLocation.fromNamespaceAndPath("desgraciamon4_announcements", "action"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BulletinActionPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUtf(payload.action, 32); buffer.writeUtf(payload.id, 128) },
            { buffer -> BulletinActionPayload(buffer.readUtf(32), buffer.readUtf(128)) }
        )
    }
}

data class BulletinAdminActionPayload(val action: String, val payloadJson: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BulletinAdminActionPayload>(ResourceLocation.fromNamespaceAndPath("desgraciamon4_announcements", "admin_action"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BulletinAdminActionPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUtf(payload.action, 64); buffer.writeUtf(payload.payloadJson, 262144) },
            { buffer -> BulletinAdminActionPayload(buffer.readUtf(64), buffer.readUtf(262144)) }
        )
    }
}

data class OpenAdminScreenPayload(val initialTab: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OpenAdminScreenPayload>(ResourceLocation.fromNamespaceAndPath("desgraciamon4_announcements", "open_admin"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenAdminScreenPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUtf(payload.initialTab, 32) },
            { buffer -> OpenAdminScreenPayload(buffer.readUtf(32)) }
        )
    }
}

object BulletinClientData {
    var snapshot = ClientBulletinSnapshot(BulletinDocument(), PlayerBulletinState())
}
