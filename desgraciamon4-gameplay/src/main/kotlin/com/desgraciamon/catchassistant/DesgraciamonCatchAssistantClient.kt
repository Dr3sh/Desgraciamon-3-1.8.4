package com.desgraciamon.catchassistant

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

class DesgraciamonCatchAssistantClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(LegendaryCaptureSyncPayload.TYPE) { payload, context ->
            context.client().execute { LegendaryCaptureClientData.entries = payload.entries }
        }
    }
}
