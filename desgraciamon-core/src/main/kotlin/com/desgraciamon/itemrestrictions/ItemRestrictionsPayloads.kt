package com.desgraciamon.itemrestrictions

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class ItemRestrictionsSyncPayload(
    val restrictedItems: List<String>,
    val restrictedTags: List<String>,
    val activeForPlayer: Boolean
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ItemRestrictionsSyncPayload>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon4_item_restrictions", "sync")
        )

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ItemRestrictionsSyncPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.restrictedItems.size)
                payload.restrictedItems.forEach { buffer.writeUtf(it) }
                buffer.writeVarInt(payload.restrictedTags.size)
                payload.restrictedTags.forEach { buffer.writeUtf(it) }
                buffer.writeBoolean(payload.activeForPlayer)
            },
            { buffer ->
                val itemCount = buffer.readVarInt().coerceIn(0, 4096)
                val items = List(itemCount) { buffer.readUtf() }
                val tagCount = buffer.readVarInt().coerceIn(0, 4096)
                val tags = List(tagCount) { buffer.readUtf() }
                val active = buffer.readBoolean()
                ItemRestrictionsSyncPayload(items, tags, active)
            }
        )
    }
}

