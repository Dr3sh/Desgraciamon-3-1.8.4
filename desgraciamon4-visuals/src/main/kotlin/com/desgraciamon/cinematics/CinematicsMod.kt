package com.desgraciamon.cinematics

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

class CinematicsMod : ModInitializer {
    companion object {
        const val MOD_ID = "desgraciamon4_cinematics"
        val logger = LoggerFactory.getLogger(MOD_ID)
    }

    override fun onInitialize() {
        logger.info("Iniciando Desgraciamon 3 Cinemáticas Sincronizadas y Efectos de Pantalla...")
        CinematicPackets.registerCommon()
        com.desgraciamon.screeneffects.ScreenEffectPacket.registerCommon()
        CinematicRouteStorage.init()
        DirectorToolbox.register()

        ServerPlayNetworking.registerGlobalReceiver(CinematicClientReadyPayload.TYPE) { payload, context ->
            val player = context.player()
            context.server().execute {
                CinematicServerManager.onClientReady(context.server(), player, payload.routeName)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CinematicSkipPayload.TYPE) { _, context ->
            val player = context.player()
            context.server().execute {
                CinematicServerManager.onPlayerSkip(context.server(), player)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CinematicEditorSyncPayload.TYPE) { payload, context ->
            val player = context.player()
            context.server().execute {
                if (!player.hasPermissions(2)) return@execute
                val route = CinematicRouteStorage.fromJson(payload.routeJson) ?: return@execute
                CinematicRouteStorage.saveRoute(route)
                CinematicCommands.activeEditingRoutes[player.uuid] = route.name.lowercase()
                CinematicPackets.play(player, route.name, CinematicRouteStorage.toJson(route), -1L)
            }
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            CinematicCommands.register(dispatcher)
            com.desgraciamon.screeneffects.ScreenEffectsCommands.register(dispatcher)
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            CinematicServerManager.onServerTick(server)
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            CinematicServerManager.restorePendingOnStartupOrJoin(handler.player)
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            CinematicServerManager.onPlayerDisconnect(server, handler.player)
            CinematicCommands.activeEditingRoutes.remove(handler.player.uuid)
        }

        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register { player, _, _ ->
            CinematicServerManager.onPlayerDeathOrDimensionChange(player.server, player, "dimension_change")
        }

        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
            if (entity is ServerPlayer) {
                CinematicServerManager.onPlayerDeathOrDimensionChange(entity.server, entity, "death")
            }
        }

        logger.info("Desgraciamon 3 Cinemáticas inicializado correctamente.")
    }
}
