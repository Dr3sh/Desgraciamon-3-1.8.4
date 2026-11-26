package com.desgraciamon.antidupe

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.slf4j.LoggerFactory

class AntiDupeMod : ModInitializer {
    private var tickCounter = 0

    override fun onInitialize() {
        logger.info("Iniciando Desgraciamon Anti-Dupe & Invsee Inspector")
        AntiDupeConfigStore.load()

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            AntiDupeCommands.register(dispatcher)
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            server.execute {
                AntiDupeEngine.scanSinglePlayer(handler.player)
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            AntiDupeEngine.clearPlayerBaseline(handler.player.uuid)
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            val intervalTicks = (AntiDupeConfigStore.config.scanIntervalSeconds * 20).coerceAtLeast(100)
            if (++tickCounter % intervalTicks == 0) {
                AntiDupeEngine.runGlobalScan(server)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("desgraciamon4-antidupe")
    }
}
