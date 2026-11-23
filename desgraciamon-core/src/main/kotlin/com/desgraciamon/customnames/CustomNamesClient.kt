package com.desgraciamon.customnames

import com.desgraciamon.badges.RichText
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.KeyMapping
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CustomNamesClient : ClientModInitializer {

    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(CustomNamesSyncPayload.TYPE) { payload, context ->
            context.client().execute {
                setNames(payload.names)
            }
        }

        tabPreviewKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.desgraciamon.tabpreview",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.value,
                "key.category.desgraciamon3"
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (tabPreviewKey.consumeClick()) {
                toggleTabListPreview()
                val statusMsg = if (isPreviewingTabList) "§aACTIVADA" else "§cDESACTIVADA"
                client.player?.displayClientMessage(
                    Component.literal("§6[TabList] §fPrevisualización de la lista de jugadores: $statusMsg"),
                    true
                )
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("tabpreview").executes { ctx ->
                    val active = toggleTabListPreview()
                    val statusMsg = if (active) "§aACTIVADA" else "§cDESACTIVADA"
                    ctx.source.sendFeedback(
                        Component.literal("§6[TabList] §fPrevisualización de la lista de jugadores: $statusMsg")
                    )
                    1
                }
            )
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            names.clear()
            componentCache.clear()
            plainCache.clear()
            isPreviewingTabList = false
        }

        LOGGER.info("Desgraciamon Custom Names (Cliente) inicializado correctamente.")
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("DesgraciamonCustomNamesClient")
        private val names = ConcurrentHashMap<UUID, String>()
        private val componentCache = ConcurrentHashMap<UUID, Component>()
        private val plainCache = ConcurrentHashMap<UUID, String>()

        @JvmStatic
        @Volatile
        var isPreviewingTabList: Boolean = false

        private lateinit var tabPreviewKey: KeyMapping

        @JvmStatic
        fun setNames(newNames: Map<UUID, String>) {
            names.clear()
            componentCache.clear()
            plainCache.clear()
            names.putAll(newNames)
            for ((uuid, raw) in newNames) {
                val comp = RichText.parse(raw)
                componentCache[uuid] = comp
                plainCache[uuid] = raw.replace(Regex("<[^>]*>"), "")
            }
        }

        @JvmStatic
        fun getComponent(uuid: UUID): Component? {
            val cached = componentCache[uuid]
            if (cached != null) return cached
            val raw = names[uuid] ?: runCatching { CustomNamesManager.getRaw(uuid) }.getOrNull() ?: return null
            val comp = RichText.parse(raw)
            componentCache[uuid] = comp
            return comp
        }

        @JvmStatic
        fun getPlain(uuid: UUID): String? {
            val cached = plainCache[uuid]
            if (cached != null) return cached
            val raw = names[uuid] ?: runCatching { CustomNamesManager.getRaw(uuid) }.getOrNull() ?: return null
            val plain = raw.replace(Regex("<[^>]*>"), "")
            plainCache[uuid] = plain
            return plain
        }

        @JvmStatic
        fun toggleTabListPreview(): Boolean {
            isPreviewingTabList = !isPreviewingTabList
            return isPreviewingTabList
        }
    }
}
