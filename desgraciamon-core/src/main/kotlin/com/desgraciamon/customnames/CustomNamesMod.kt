package com.desgraciamon.customnames

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.scores.PlayerTeam
import org.slf4j.LoggerFactory
import java.util.UUID

class CustomNamesMod : ModInitializer {

    override fun onInitialize() {
        PayloadTypeRegistry.playS2C().register(CustomNamesSyncPayload.TYPE, CustomNamesSyncPayload.CODEC)

        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            CustomNamesManager.initForServer(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            CustomNamesManager.save()
            CustomNamesManager.resetServer()
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            CustomNamesManager.onPlayerJoin(handler.player)
        }

        S2CPlayChannelEvents.REGISTER.register { handler, _, _, types ->
            if (types.contains(CustomNamesSyncPayload.TYPE.id)) {
                CustomNamesManager.syncToPlayer(handler.player)
            }
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            CustomNamesCommands.register(dispatcher)
        }

        LOGGER.info("Desgraciamon Custom Names inicializado correctamente.")
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("DesgraciamonCustomNames")

        @JvmStatic
        fun getNameComponent(uuid: UUID, isClient: Boolean): Component? {
            return if (isClient) {
                CustomNamesClient.getComponent(uuid)
            } else {
                CustomNamesManager.getComponent(uuid)
            }
        }

        @JvmStatic
        fun getTabListDisplayName(player: ServerPlayer): Component {
            val baseComp = CustomNamesManager.getComponent(player.uuid)
                ?: Component.literal(player.gameProfile.name)
            val hexColor = CustomNamesManager.getTabColor(player.uuid)
            val coloredComp = if (hexColor != null) {
                val parsedColor = net.minecraft.network.chat.TextColor.parseColor(hexColor).result().orElse(null)
                if (parsedColor != null) {
                    baseComp.copy().withStyle { it.withColor(parsedColor) }
                } else {
                    baseComp
                }
            } else {
                baseComp
            }

            val teamComp = if (player.team != null) {
                PlayerTeam.formatNameForTeam(player.team, coloredComp)
            } else {
                coloredComp
            }

            val afkSuffix = com.desgraciamon.afk.AfkManager.getTabListSuffix(player.uuid)
            return if (afkSuffix != null) {
                teamComp.copy().append(afkSuffix)
            } else {
                teamComp
            }
        }
    }
}
