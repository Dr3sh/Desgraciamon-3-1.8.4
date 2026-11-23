package com.desgraciamon.videomenu

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

class DesgraciamonVideoMenu : ClientModInitializer {
    override fun onInitializeClient() {
        println("[DesgraciamonVideoMenu] Client mod initialized.")
        VideoPlayerManager.initialize()

        // Prewarm when Minecraft client finishes startup
        ClientLifecycleEvents.CLIENT_STARTED.register {
            VideoPlayerManager.prewarm(force = true)
        }

        // Keep menu video warm whenever client is outside a world
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.level == null) {
                VideoPlayerManager.prewarm()
            }
        }

        // Clean up when client shuts down
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            VideoPlayerManager.stopVideo()
        }

        // Free video RAM/VRAM when entering a world or server
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            println("[DesgraciamonVideoMenu] Joined world/server - freeing video resources.")
            VideoPlayerManager.stopVideo()
        }

        // Prewarm video immediately when disconnecting back to menus (bypassing isInWorld race)
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            println("[DesgraciamonVideoMenu] Disconnected from world/server - prewarming menu video.")
            VideoPlayerManager.prewarm(force = true)
            client.execute {
                VideoPlayerManager.prewarm(force = true)
            }
        }
    }
}
