package com.desgraciamon.biomelocator

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import org.lwjgl.glfw.GLFW
import kotlin.math.atan2

class BiomeLocatorClient : ClientModInitializer {
    private val ICON_INVENTORY = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "textures/gui/inventory_icon.png")

    companion object {
        val KEY_OPEN = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.desgraciamon4_biome_locator.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "key.category.desgraciamon3"
            )
        )
    }

    override fun onInitializeClient() {
        // Networking S2C
        ClientPlayNetworking.registerGlobalReceiver(BiomeLocatorPackets.S2CSyncPayload.TYPE) { payload, context ->
            context.client().execute {
                BiomeLocatorClientState.handleSync(payload)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(BiomeLocatorPackets.S2CNavPayload.TYPE) { payload, context ->
            context.client().execute {
                BiomeLocatorClientState.handleNav(payload)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(BiomeLocatorPackets.S2CBiomeSpawnsPayload.TYPE) { payload, context ->
            context.client().execute {
                BiomeLocatorClientState.handleSpawns(payload)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(BiomeLocatorPackets.S2CPokemonBiomesPayload.TYPE) { payload, context ->
            context.client().execute {
                BiomeLocatorClientState.handlePokemonBiomes(payload)
            }
        }

        // Inventory button
        InventoryButtonIntegration.register()

        // Hotkey B to open
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (KEY_OPEN.consumeClick()) {
                if (client.screen == null) {
                    BiomeLocatorClientState.requestOpen()
                    client.setScreen(BiomeLocatorScreen())
                }
            }
        }

        // HUD Navigation Overlay
        HudRenderCallback.EVENT.register { graphics, deltaTracker ->
            renderTrackingHud(graphics)
        }
    }

    private fun renderTrackingHud(graphics: GuiGraphics) {
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        if (client.screen != null || client.options.hideGui) return

        val state = BiomeLocatorClientState
        if (state.activeTrackedBiome == null) return
        if (state.navStatus != "FOUND" && state.navStatus != "SEARCHING") return

        val screenW = client.window.guiScaledWidth
        val widgetW = 164
        val widgetH = 22
        val x = (screenW - widgetW) / 2
        val y = 10

        // Dark slate sleek background
        graphics.fill(x, y, x + widgetW, y + widgetH, 0xD012171A.toInt())
        graphics.fill(x, y, x + 3, y + widgetH, 0xFF33D17A.toInt()) // Emerald left accent
        graphics.fill(x + 3, y, x + widgetW, y + 1, 0x5533D17A)
        graphics.fill(x + 3, y + widgetH - 1, x + widgetW, y + widgetH, 0x5533D17A)
        graphics.fill(x + widgetW - 1, y, x + widgetW, y + widgetH, 0x5533D17A)

        // Compass icon
        graphics.blit(ICON_INVENTORY, x + 6, y + 3, 0f, 0f, 16, 16, 16, 16)

        if (state.navStatus == "SEARCHING") {
            graphics.drawString(client.font, "Sintonizando señal...", x + 26, y + 7, 0xFFE2D6A5.toInt(), false)
            return
        }

        // Calculate direction and distance
        val dx = state.navTargetX - player.x
        val dz = state.navTargetZ - player.z
        val dist = Math.sqrt(dx * dx + dz * dz).toInt()

        // Angle relative to player view
        val angleRad = atan2(dz, dx)
        val angleDeg = Math.toDegrees(angleRad) - 90.0
        var diff = (angleDeg - player.yRot) % 360.0
        if (diff < 0) diff += 360.0

        val arrow = when {
            diff >= 337.5 || diff < 22.5 -> "↑"
            diff in 22.5..67.5 -> "↗"
            diff in 67.5..112.5 -> "→"
            diff in 112.5..157.5 -> "↘"
            diff in 157.5..202.5 -> "↓"
            diff in 202.5..247.5 -> "↙"
            diff in 247.5..292.5 -> "←"
            else -> "↖"
        }

        var bName = state.navBiomeName
        if (client.font.width(bName) > 75) {
            bName = client.font.plainSubstrByWidth(bName, 70) + ".."
        }

        graphics.drawString(client.font, bName, x + 26, y + 3, 0xFFFFFFFF.toInt(), false)

        val distText = if (dist > 50) "~${dist}m" else "¡Aquí!"
        graphics.drawString(client.font, "§7Distancia: §e$distText", x + 26, y + 12, 0xFF8A9A96.toInt(), false)

        // Direction Arrow in gold/emerald
        graphics.drawString(client.font, "§a§l$arrow", x + widgetW - 16, y + 6, 0xFF33D17A.toInt(), false)
    }
}

