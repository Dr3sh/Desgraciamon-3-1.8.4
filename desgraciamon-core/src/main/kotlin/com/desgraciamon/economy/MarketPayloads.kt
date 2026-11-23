package com.desgraciamon.economy

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class MarketItemSnapshot(
    val basePrice: Double,
    val minPrice: Double,
    val maxPrice: Double,
    val saturationVolume: Double,
    val currentSupply: Double,
    val displayName: String,
    val isTrending: Boolean = false
)

data class MarketSyncPayload(
    val items: Map<String, MarketItemSnapshot>,
    val trendingItems: List<String> = emptyList(),
    val nextResetTimestampMs: Long = 0L
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<MarketSyncPayload>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon4_economy", "market_sync")
        )

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, MarketSyncPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeVarInt(payload.items.size)
                for ((id, item) in payload.items) {
                    buf.writeUtf(id)
                    buf.writeDouble(item.basePrice)
                    buf.writeDouble(item.minPrice)
                    buf.writeDouble(item.maxPrice)
                    buf.writeDouble(item.saturationVolume)
                    buf.writeDouble(item.currentSupply)
                    buf.writeUtf(item.displayName)
                    buf.writeBoolean(item.isTrending)
                }
                buf.writeVarInt(payload.trendingItems.size)
                for (trend in payload.trendingItems) {
                    buf.writeUtf(trend)
                }
                buf.writeLong(payload.nextResetTimestampMs)
            },
            { buf ->
                val size = buf.readVarInt()
                val map = LinkedHashMap<String, MarketItemSnapshot>(size)
                for (i in 0 until size) {
                    val id = buf.readUtf()
                    val base = buf.readDouble()
                    val min = buf.readDouble()
                    val max = buf.readDouble()
                    val sat = buf.readDouble()
                    val sup = buf.readDouble()
                    val name = buf.readUtf()
                    val isTrending = buf.readBoolean()
                    map[id] = MarketItemSnapshot(base, min, max, sat, sup, name, isTrending)
                }
                val trendCount = buf.readVarInt()
                val trendList = ArrayList<String>(trendCount)
                for (i in 0 until trendCount) {
                    trendList.add(buf.readUtf())
                }
                val nextReset = buf.readLong()
                MarketSyncPayload(map, trendList, nextReset)
            }
        )
    }
}

data class MarketAlertTogglePayload(
    val enabled: Boolean
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<MarketAlertTogglePayload>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon4_economy", "market_alert_toggle")
        )

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, MarketAlertTogglePayload> = StreamCodec.of(
            { buf, payload -> buf.writeBoolean(payload.enabled) },
            { buf -> MarketAlertTogglePayload(buf.readBoolean()) }
        )
    }
}

data object EconomySellPayload : CustomPacketPayload {
    override fun type() = TYPE

    val TYPE = CustomPacketPayload.Type<EconomySellPayload>(
        ResourceLocation.fromNamespaceAndPath("desgraciamon4_economy", "economy_sell")
    )

    val CODEC: StreamCodec<RegistryFriendlyByteBuf, EconomySellPayload> = StreamCodec.unit(EconomySellPayload)
}
