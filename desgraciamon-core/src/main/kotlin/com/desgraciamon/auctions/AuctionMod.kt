package com.desgraciamon.auctions

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.slf4j.LoggerFactory

class AuctionMod : ModInitializer {
    private val logger = LoggerFactory.getLogger("desgraciamon4-auctions")

    override fun onInitialize() {
        logger.info("Inicializando Desgraciamon In-World Auctions...")

        AuctionNetwork.registerCommon()

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            AuctionCommands.register(dispatcher)
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            AuctionManager.init(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            AuctionManager.save()
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            AuctionManager.tick(server)
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            AuctionManager.syncToPlayer(handler.player)
            AuctionManager.claimPendingRewards(handler.player)
        }

        logger.info("Desgraciamon In-World Auctions inicializado con éxito.")
    }
}

