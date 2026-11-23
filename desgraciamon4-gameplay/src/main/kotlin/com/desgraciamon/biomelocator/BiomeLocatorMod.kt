package com.desgraciamon.biomelocator

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

class BiomeLocatorMod : ModInitializer {
    override fun onInitialize() {
        TextCatalog.load()
        // Register packets
        BiomeLocatorPackets.registerCommon()

        // Register Server C2S Packet Receiver
        ServerPlayNetworking.registerGlobalReceiver(BiomeLocatorPackets.C2SActionPayload.TYPE) { payload, context ->
            val player = context.player()
            context.server().execute {
                BiomeLocatorServer.handleAction(player, payload.action, payload.biomeId)
            }
        }

        // Register Commands
        CommandRegistrationCallback.EVENT.register { dispatcher, registryAccess, environment ->
            BiomeLocatorCommands.register(dispatcher)
        }

        // Register Player Join
        ServerPlayConnectionEvents.JOIN.register { handler, sender, server ->
            BiomeLocatorServer.onPlayerJoin(handler.player)
        }

        // Register Server Tick
        ServerTickEvents.END_SERVER_TICK.register { server ->
            BiomeLocatorServer.tick(server)
        }

        // Clear world data when server stops
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            BiomeLocatorServer.onServerStopping()
        }

        // Invalidate spawn index on datapack reload
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { server, _, _ ->
            CobblemonSpawnIntegration.invalidateCache()
        }

        // Initialize server storage & Cobblemon event hooks
        BiomeLocatorServer.initialize()
        CobblemonDiscoveryHook.register()

        println("[DesgraciamonBiomeLocator] Mod initialized successfully.")
    }
}

