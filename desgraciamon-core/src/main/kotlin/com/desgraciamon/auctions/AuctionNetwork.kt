package com.desgraciamon.auctions

import com.google.gson.Gson
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class AuctionSyncPayload(
    val active: Boolean,
    val standX: Double,
    val standY: Double,
    val standZ: Double,
    val standDimension: String,
    val isPokemon: Boolean,
    val title: String,
    val currentBid: Long,
    val minNextBid: Long,
    val highestBidder: String,
    val timeRemainingSeconds: Int,
    val pokemonDetails: AuctionPokemonDetails?
) : CustomPacketPayload {

    override fun type() = TYPE

    companion object {
        private val gson = Gson()
        val TYPE = CustomPacketPayload.Type<AuctionSyncPayload>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon_core", "auction_sync")
        )

        val CODEC: StreamCodec<FriendlyByteBuf, AuctionSyncPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeBoolean(payload.active)
                buf.writeDouble(payload.standX)
                buf.writeDouble(payload.standY)
                buf.writeDouble(payload.standZ)
                buf.writeUtf(payload.standDimension)
                buf.writeBoolean(payload.isPokemon)
                buf.writeUtf(payload.title)
                buf.writeLong(payload.currentBid)
                buf.writeLong(payload.minNextBid)
                buf.writeUtf(payload.highestBidder)
                buf.writeInt(payload.timeRemainingSeconds)
                val hasDetails = payload.pokemonDetails != null
                buf.writeBoolean(hasDetails)
                if (hasDetails) {
                    buf.writeUtf(gson.toJson(payload.pokemonDetails))
                }
            },
            { buf ->
                val active = buf.readBoolean()
                val standX = buf.readDouble()
                val standY = buf.readDouble()
                val standZ = buf.readDouble()
                val standDimension = buf.readUtf()
                val isPokemon = buf.readBoolean()
                val title = buf.readUtf()
                val currentBid = buf.readLong()
                val minNextBid = buf.readLong()
                val highestBidder = buf.readUtf()
                val timeRemainingSeconds = buf.readInt()
                val hasDetails = buf.readBoolean()
                val details = if (hasDetails) {
                    runCatching { gson.fromJson(buf.readUtf(), AuctionPokemonDetails::class.java) }.getOrNull()
                } else null

                AuctionSyncPayload(
                    active, standX, standY, standZ, standDimension,
                    isPokemon, title, currentBid, minNextBid, highestBidder,
                    timeRemainingSeconds, details
                )
            }
        )
    }
}

object AuctionNetwork {
    private var registered = false

    fun registerCommon() {
        if (registered) return
        registered = true
        PayloadTypeRegistry.playS2C().register(AuctionSyncPayload.TYPE, AuctionSyncPayload.CODEC)
    }
}

