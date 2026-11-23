package com.desgraciamon.vortex

import com.desgraciamon.vortex.command.VortexCommand
import com.desgraciamon.vortex.config.VortexConfig
import com.desgraciamon.vortex.network.RewardDecisionPacket
import com.desgraciamon.vortex.network.VortexNetwork
import com.desgraciamon.vortex.server.VortexManager
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class DesgraciamonMod : ModInitializer {
    companion object {
        const val MOD_ID = "desgraciamon4_special_effects"
        val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
    }

    override fun onInitialize() {
        LOGGER.info("Initializing Desgraciamon 3 Special Effects (Star Trails & Sky Shader)...")

        // Registrar sonidos personalizados
        com.desgraciamon.vortex.sound.DesgraciamonSounds.register()

        // Cargar o generar config/desgraciamon/config.json
        VortexConfig.load()
        com.desgraciamon.visuals.TextCatalog.load()

        // Registrar paquetes de red S2C y C2S
        VortexNetwork.registerPayloads()

        // Receptor C2S para la decisión de recompensa del ganador (Reclamar vs Vender)
        ServerPlayNetworking.registerGlobalReceiver(RewardDecisionPacket.ID) { payload, context ->
            val player = context.player()
            context.server().execute {
                VortexManager.handleRewardDecision(player, payload.claimPokemon)
            }
        }

        // Registrar comando /desgraciamon vortex start [nofly]
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            VortexCommand.register(dispatcher)
        }

        // Registrar listeners de Cobblemon una vez el servidor arranca
        ServerLifecycleEvents.SERVER_STARTED.register {
            VortexManager.registerEvents()
        }

        // Limpiar estado al detener el servidor
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            if (VortexManager.isVortexActive) {
                VortexManager.stopVortex(server, null)
            }
            VortexManager.clearForcedChunk()
            VortexManager.limbo.clear()
        }

        // Sincronizar estado del vórtice cuando un jugador entra al servidor
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val player = handler.player
            server.execute {
                if (VortexManager.isVortexActive) {
                    VortexManager.syncToPlayer(player)
                }
            }
        }

        // Tick del servidor para la cuenta atrás y el campo de gravedad No-Fly
        ServerTickEvents.END_SERVER_TICK.register { server ->
            VortexManager.onServerTick(server)
        }
    }
}

