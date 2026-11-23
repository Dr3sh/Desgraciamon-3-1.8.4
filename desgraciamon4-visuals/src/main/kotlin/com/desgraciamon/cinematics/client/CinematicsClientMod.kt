package com.desgraciamon.cinematics.client

import com.desgraciamon.cinematics.*
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

class CinematicsClientMod : ClientModInitializer {

    private lateinit var keyAddPoint: KeyMapping
    private lateinit var keyMovePoint: KeyMapping
    private lateinit var keyPrevPoint: KeyMapping
    private lateinit var keyNextPoint: KeyMapping
    private lateinit var keyOpenGui: KeyMapping

    override fun onInitializeClient() {
        CinematicPackets.registerCommon()
        com.desgraciamon.screeneffects.ScreenEffectPacket.registerCommon()
        CinematicEditorRenderer.init()

        keyAddPoint = KeyBindingHelper.registerKeyBinding(KeyMapping("key.desgraciamon.director_add", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.desgraciamon.director"))
        keyMovePoint = KeyBindingHelper.registerKeyBinding(KeyMapping("key.desgraciamon.director_move", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.desgraciamon.director"))
        keyPrevPoint = KeyBindingHelper.registerKeyBinding(KeyMapping("key.desgraciamon.director_prev", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, "category.desgraciamon.director"))
        keyNextPoint = KeyBindingHelper.registerKeyBinding(KeyMapping("key.desgraciamon.director_next", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, "category.desgraciamon.director"))
        keyOpenGui = KeyBindingHelper.registerKeyBinding(KeyMapping("key.desgraciamon.director_gui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "category.desgraciamon.director"))

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            CinematicClientEngine.stopPlayback("disconnect")
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (CinematicClientEngine.isCinematicActive()) {
                val route = CinematicClientEngine.activeRoute
                if (route != null && route.skippable && client.screen == null) {
                    if (client.options.keyJump.consumeClick()) {
                        CinematicClientEngine.sendSkipRequest()
                    }
                }
            } else if (CinematicClientEngine.isEditorMode && client.screen == null && client.player != null) {
                val route = CinematicClientEngine.editorRoute
                while (keyAddPoint.consumeClick()) {
                    client.player?.connection?.sendCommand("cinematicas director agregar")
                }
                while (keyMovePoint.consumeClick()) {
                    val idx = CinematicClientEngine.selectedKeyframeIndex + 1
                    client.player?.connection?.sendCommand("cinematicas director mover $idx")
                }
                while (keyPrevPoint.consumeClick()) {
                    if (route != null && route.keyframes.isNotEmpty()) {
                        CinematicClientEngine.selectedKeyframeIndex =
                            (CinematicClientEngine.selectedKeyframeIndex - 1 + route.keyframes.size) % route.keyframes.size
                    }
                }
                while (keyNextPoint.consumeClick()) {
                    if (route != null && route.keyframes.isNotEmpty()) {
                        CinematicClientEngine.selectedKeyframeIndex =
                            (CinematicClientEngine.selectedKeyframeIndex + 1) % route.keyframes.size
                    }
                }
                while (keyOpenGui.consumeClick()) {
                    if (route != null) {
                        client.setScreen(CinematicDirectorScreen(route))
                    }
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(com.desgraciamon.screeneffects.ScreenEffectPacket.TYPE) { payload, context ->
            context.client().execute {
                com.desgraciamon.screeneffects.ScreenEffectsClientEngine.triggerEffect(payload)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(CinematicPreparePayload.TYPE) { payload, context ->
            val route = CinematicRouteStorage.fromJson(payload.routeJson) ?: return@registerGlobalReceiver
            context.client().execute {
                CinematicClientEngine.preparePlayback(route, payload.requiredAnimations)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(CinematicPlayPayload.TYPE) { payload, context ->
            val route = CinematicRouteStorage.fromJson(payload.routeJson) ?: return@registerGlobalReceiver
            context.client().execute {
                CinematicClientEngine.startPlayback(route, payload.startTimestampMs, payload.serverNowMs)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(CinematicHeartbeatPayload.TYPE) { payload, context ->
            context.client().execute {
                CinematicClientEngine.onHeartbeat(payload.serverElapsedMs, payload.isPaused)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(CinematicStopPayload.TYPE) { payload, context ->
            context.client().execute {
                CinematicClientEngine.stopPlayback(payload.reason)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(CinematicEditorSyncPayload.TYPE) { payload, context ->
            val route = CinematicRouteStorage.fromJson(payload.routeJson) ?: return@registerGlobalReceiver
            context.client().execute {
                CinematicClientEngine.startPlayback(route, -1L)
                if (payload.openGui) {
                    context.client().setScreen(CinematicDirectorScreen(route))
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(CinematicTriggerPayload.TYPE) { payload, context ->
            context.client().execute {
                when (payload.actionType.lowercase()) {
                    "shake" -> CinematicClientEngine.triggerShake(payload.durationSeconds, 2.0f)
                    "fade" -> CinematicClientEngine.triggerFade(CinematicClientEngine.parseColor(payload.actionValue), payload.durationSeconds)
                }
            }
        }

        HudRenderCallback.EVENT.register { guiGraphics, _ ->
            if (Minecraft.getInstance().screen == null) {
                CinematicClientEngine.renderCinematicHud(guiGraphics, 0f)
            }
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (com.desgraciamon.screeneffects.ScreenEffectsClientEngine.shouldSuperimposeScreen(screen)) {
                ScreenEvents.beforeRender(screen).register { renderedScreen, _, _, _, _ ->
                    if (Minecraft.getInstance().screen === renderedScreen) {
                        com.desgraciamon.screeneffects.ScreenEffectsClientEngine.renderTopLevel(0f, isDirectScreenCall = true)
                    }
                }
            } else {
                ScreenEvents.afterRender(screen).register { renderedScreen, graphics, _, _, _ ->
                    if (Minecraft.getInstance().screen === renderedScreen) {
                        if (CinematicClientEngine.isCinematicActive() || CinematicClientEngine.isEditorMode || CinematicClientEngine.isPreparing) {
                            CinematicClientEngine.renderCinematicHud(graphics, 0f)
                        }
                        com.desgraciamon.screeneffects.ScreenEffectsClientEngine.renderTopLevel(0f, isDirectScreenCall = true)
                    }
                }
            }
        }
    }
}
