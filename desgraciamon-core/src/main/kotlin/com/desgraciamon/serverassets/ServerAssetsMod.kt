package com.desgraciamon.serverassets

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

class ServerAssetsMod : ModInitializer {
    override fun onInitialize() {
        PayloadTypeRegistry.playS2C().register(ServerAssetsManifestPayload.TYPE, ServerAssetsManifestPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(ServerAssetRequestPayload.TYPE, ServerAssetRequestPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(ServerAssetChunkPayload.TYPE, ServerAssetChunkPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PlayServerSoundPayload.TYPE, PlayServerSoundPayload.CODEC)

        ServerLifecycleEvents.SERVER_STARTED.register {
            ServerAssetsManager.load()
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            ServerAssetsManager.tick(server)
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            ServerAssetsManager.sendManifest(handler.player)
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            ServerAssetsManager.onDisconnect(handler.player.uuid)
        }

        ServerPlayNetworking.registerGlobalReceiver(ServerAssetRequestPayload.TYPE) { payload, context ->
            context.server().execute {
                ServerAssetsManager.handleAssetRequest(context.player(), payload.keys)
            }
        }
    }
}
