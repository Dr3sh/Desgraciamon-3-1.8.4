package com.desgraciamon.economy

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

object MarketSyncServer {
    fun createSyncPayload(): MarketSyncPayload {
        val map = LinkedHashMap<String, MarketItemSnapshot>()
        val trending = MarketStore.state.trendingItems.toList()
        for ((id, cfg) in MarketStore.config.items) {
            val supply = MarketStore.state.supply[id] ?: 0.0
            val name = cfg.displayName ?: id
            val isTrend = trending.contains(id)
            map[id] = MarketItemSnapshot(
                basePrice = cfg.basePrice,
                minPrice = cfg.minPrice,
                maxPrice = cfg.maxPrice,
                saturationVolume = cfg.saturationVolume,
                currentSupply = supply,
                displayName = name,
                isTrending = isTrend
            )
        }
        val nextReset = MarketEngine.getNextResetTimestampMs()
        return MarketSyncPayload(map, trending, nextReset)
    }

    fun syncToPlayer(player: ServerPlayer) {
        ServerPlayNetworking.send(player, createSyncPayload())
    }

    fun broadcastSync(server: MinecraftServer) {
        val payload = createSyncPayload()
        for (player in server.playerList.players) {
            ServerPlayNetworking.send(player, payload)
        }
    }
}
