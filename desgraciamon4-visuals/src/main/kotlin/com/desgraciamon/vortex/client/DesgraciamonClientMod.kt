package com.desgraciamon.vortex.client

import com.desgraciamon.vortex.network.OpenRewardScreenPacket
import com.desgraciamon.vortex.network.VortexSyncPacket
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.resources.ResourceLocation

class DesgraciamonClientMod : ClientModInitializer {

    override fun onInitializeClient() {
        // Registrar los Core Shaders GLSL para renderizar los 6 efectos de Skybox
        CoreShaderRegistrationCallback.EVENT.register { context ->
            context.register(
                ResourceLocation.withDefaultNamespace("desgraciamon_vortex_sky"),
                DefaultVertexFormat.POSITION
            ) { shaderInstance ->
                VortexSkyRenderer.vortexSkyShader = shaderInstance
            }
            context.register(
                ResourceLocation.withDefaultNamespace("desgraciamon_startrails_sky"),
                DefaultVertexFormat.POSITION
            ) { shaderInstance ->
                VortexSkyRenderer.starTrailsSkyShader = shaderInstance
            }
            context.register(
                ResourceLocation.withDefaultNamespace("desgraciamon_goosebumps_sky"),
                DefaultVertexFormat.POSITION
            ) { shaderInstance ->
                VortexSkyRenderer.goosebumpsSkyShader = shaderInstance
            }
        }

        // Receptor S2C para sincronizar el estado del Vórtice Dimensional
        ClientPlayNetworking.registerGlobalReceiver(VortexSyncPacket.ID) { payload, context ->
            context.client().execute {
                VortexShaderController.onSyncPacket(payload)
            }
        }

        // Receptor S2C para abrir la interfaz de recompensa al capturar la anomalía
        ClientPlayNetworking.registerGlobalReceiver(OpenRewardScreenPacket.ID) { payload, context ->
            val client = context.client()
            client.execute {
                client.setScreen(
                    VortexRewardScreen(
                        pokemonName = payload.pokemonName,
                        speciesIdentifier = payload.speciesIdentifier,
                        level = payload.level,
                        rewardAmount = payload.rewardAmount
                    )
                )
            }
        }

        // Tick de cliente para animar Time/Intensity del shader GLSL y el contador mm:ss
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            VortexShaderController.onClientTick(client)
        }

        // Limpiar estado del shader al desconectarse de un servidor
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            client.execute {
                VortexShaderController.resetClientState(client)
            }
        }

        // Renderizar la cuenta atrás mm:ss en el Top-Center de la pantalla
        HudRenderCallback.EVENT.register(VortexHudOverlay)
    }
}
