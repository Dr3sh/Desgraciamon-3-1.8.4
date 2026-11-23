package com.desgraciamon.discordbridge

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.fabricmc.loader.api.FabricLoader
import org.lwjgl.glfw.GLFW

class DiscordBridgeClient : ClientModInitializer {
    override fun onInitializeClient() {
        TextCatalog.load()
        val openSettingsKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping("key.desgraciamon4_discord_bridge.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.category.desgraciamon3")
        )
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openSettingsKey.consumeClick()) openSettings(client)
        }
        ClientPlayNetworking.registerGlobalReceiver(TeamPrivacyStatusPayload.TYPE) { payload, context ->
            context.client().execute {
                DiscordSettingsClientData.isTeamPrivate = payload.isPrivate
                DiscordSettingsClientData.isDiscordLinked = payload.isDiscordLinked
                DiscordSettingsClientData.cooldownInstalled = payload.cooldownInstalled
                DiscordSettingsClientData.cooldownBossBarEnabled = payload.cooldownBossBarEnabled
                DiscordSettingsClientData.cooldownJoinNoticeEnabled = payload.cooldownJoinNoticeEnabled
                DiscordSettingsClientData.catchAssistantInstalled = payload.catchAssistantInstalled
                DiscordSettingsClientData.captureNoticeEnabled = payload.catchNoticeEnabled
                (context.client().screen as? DiscordSettingsScreen)?.updateSettings(payload)
            }
        }

        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (!overlay) {
                val str = message.string
                if (!ClientAlertPreferences.data.biomeAtlasChat && str.contains("[Atlas de Biomas]")) {
                    return@register false
                }
                if (!ClientAlertPreferences.data.missionProgressChat && isMissionProgressChatMessage(str)) {
                    return@register false
                }
            }
            true
        }
    }

    private fun isMissionProgressChatMessage(text: String): Boolean {
        if (text.contains("[Misión]") || text.contains("[Nueva Misión]")) return true
        // Only suppress Bulletin guild mission reminders ("— X/Y" / "— Objetivo completado"),
        // never guild security warnings or guild chat prefixed with [Gremio].
        return text.contains("[Gremio]") &&
            text.contains(" — ") &&
            (text.contains("/") || text.contains("Objetivo completado"))
    }

    private fun openSettings(client: Minecraft) {
        if (client.player == null) return
        DiscordSettingsClientData.isTeamPrivate = null
        client.setScreen(DiscordSettingsScreen(client.screen))
    }
}
