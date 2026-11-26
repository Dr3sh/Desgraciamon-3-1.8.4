package com.desgraciamon.economy

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

class EconomyClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(MarketSyncPayload.TYPE) { payload, context ->
            context.client().execute {
                MarketStore.config.items.clear()
                MarketStore.state.supply.clear()
                for ((id, item) in payload.items) {
                    MarketStore.config.items[id] = ItemMarketConfig(
                        basePrice = item.basePrice,
                        minPrice = item.minPrice,
                        maxPrice = item.maxPrice,
                        saturationVolume = item.saturationVolume,
                        displayName = item.displayName
                    )
                    MarketStore.state.supply[id] = item.currentSupply
                }
                MarketStore.state.trendingItems.clear()
                MarketStore.state.trendingItems.addAll(payload.trendingItems)
                MarketEngine.clientSyncedNextResetMs = payload.nextResetTimestampMs
                MarketStore.state.lastResetTimestampMs = payload.nextResetTimestampMs - (MarketEngine.getResetIntervalDays() * 86400000L)
            }
        }

        ClientPlayConnectionEvents.JOIN.register { _, sender, _ ->
            MarketClientConfig.load()
            if (!MarketClientConfig.isAlertsEnabled()) {
                sender.sendPacket(MarketAlertTogglePayload(false))
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            MarketEngine.clientSyncedNextResetMs = 0L
            MarketStore.config.items.clear()
            MarketStore.resetState()
        }
    }
}
