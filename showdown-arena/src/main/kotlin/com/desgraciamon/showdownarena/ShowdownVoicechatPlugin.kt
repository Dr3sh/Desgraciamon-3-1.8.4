package com.desgraciamon.showdownarena

import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VolumeCategory
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent
import net.fabricmc.api.EnvType
import net.fabricmc.loader.api.FabricLoader

class ShowdownVoicechatPlugin : VoicechatPlugin {
    companion object {
        const val CATEGORY_ID = "arena_music"

        private fun buildCategory(api: VoicechatApi): VolumeCategory {
            return api.volumeCategoryBuilder()
                .setId(CATEGORY_ID)
                .setName("Música de Arena")
                .setDescription("Música de fondo de la cúpula de combate")
                .build()
        }
    }

    override fun getPluginId(): String = "showdown_arena"

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(VoicechatServerStartedEvent::class.java) { event ->
            val serverApi = event.voicechat
            runCatching { serverApi.registerVolumeCategory(buildCategory(serverApi)) }
                .onFailure { println("[ShowdownVoicechat] Server category error: ${it.message}") }
        }

        if (FabricLoader.getInstance().environmentType == EnvType.CLIENT) {
            registration.registerEvent(ClientVoicechatInitializationEvent::class.java) { event ->
                val clientApi = event.voicechat
                runCatching { clientApi.registerClientVolumeCategory(buildCategory(clientApi)) }
                ArenaMusicClient.onVoicechatInit(clientApi)
            }

            registration.registerEvent(ClientVoicechatConnectionEvent::class.java) { event ->
                val clientApi = event.voicechat
                ArenaMusicClient.onVoicechatInit(clientApi)
                if (event.isConnected) {
                    runCatching { clientApi.registerClientVolumeCategory(buildCategory(clientApi)) }
                }
                ArenaMusicClient.onVoicechatConnection(event.isConnected)
            }
        }
    }
}
