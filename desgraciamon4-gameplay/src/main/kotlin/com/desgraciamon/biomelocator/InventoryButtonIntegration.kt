package com.desgraciamon.biomelocator

import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import kotlin.math.exp

object InventoryButtonIntegration {
    private val ICON_INVENTORY = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "textures/gui/inventory_icon.png")

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is InventoryScreen) {
                val leftPos = (screen.width - 176) / 2
                val topPos = (screen.height - 166) / 2

                // Placed right to the right side of the offhand slot (offhand is at leftPos + 77, topPos + 62)
                val buttonX = leftPos + 98
                val buttonY = topPos + 62
                val buttonSize = 18

                val btn = object : Button(
                    buttonX,
                    buttonY,
                    buttonSize,
                    buttonSize,
                    Component.empty(),
                    {
                        BiomeLocatorClientState.requestOpen()
                        Minecraft.getInstance().setScreen(BiomeLocatorScreen(screen))
                    },
                    { narration -> narration.get() }
                ) {
                    private var hoverProgress: Float = 0.0f
                    private var lastTimeMs: Long = 0L

                    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
                        val isHovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height

                        val now = System.currentTimeMillis()
                        val dt = if (lastTimeMs == 0L) 0.016f else ((now - lastTimeMs).coerceIn(1L, 100L) / 1000.0f)
                        lastTimeMs = now

                        val target = if (isHovered) 1.0f else 0.0f
                        val speed = 14.0f
                        hoverProgress += (target - hoverProgress) * (1.0f - exp((-speed * dt).toDouble())).toFloat()
                        hoverProgress = hoverProgress.coerceIn(0.0f, 1.0f)

                        val centerX = x + width / 2.0f
                        val centerY = y + height / 2.0f
                        val scale = 1.0f + 0.16f * hoverProgress
                        val brightness = 1.0f - 0.25f * hoverProgress

                        val iconSize = 16
                        val iconX = x + (width - iconSize) / 2
                        val iconY = y + (height - iconSize) / 2

                        val pose = graphics.pose()
                        pose.pushPose()
                        pose.translate(centerX, centerY, 0.0f)
                        pose.scale(scale, scale, 1.0f)
                        pose.translate(-centerX, -centerY, 0.0f)

                        RenderSystem.setShaderColor(brightness, brightness, brightness, 1.0f)
                        graphics.blit(ICON_INVENTORY, iconX, iconY, 0f, 0f, iconSize, iconSize, iconSize, iconSize)
                        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)

                        pose.popPose()

                        if (isHovered) {
                            val tooltip = listOf(
                                com.addoncobblemon.legendarybestiary.RichText.parse("<prism=#FF1744,#FF6E6E,#B71C1C>Atlas y Localizador de Biomas</prism>", emptyMap()),
                                Component.literal("§7Descubre y rastrea hábitats de Cobblemon")
                            )
                            graphics.renderComponentTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY)
                        }
                    }
                }

                Screens.getButtons(screen).add(btn)
            }
        }
    }
}
