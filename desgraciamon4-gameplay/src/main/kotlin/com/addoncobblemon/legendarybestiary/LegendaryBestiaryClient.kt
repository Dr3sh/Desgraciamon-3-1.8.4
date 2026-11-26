package com.addoncobblemon.legendarybestiary

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import com.cobblemon.mod.common.CobblemonItems
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.lwjgl.glfw.GLFW
import kotlin.math.exp

class LegendaryBestiaryClient : ClientModInitializer {
    private lateinit var openKey: KeyMapping

    private val pokedexPurpleTexture: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath("legendary_bestiary", "textures/gui/pokedex_purple.png")

    override fun onInitializeClient() {
        TextCatalog.load(mapOf(
            "tooltip.open" to "Abrir Guía de Legendarios", "screen.title" to "Guía de Legendarios", "filter.all" to "Todas", "filter.captured" to "Pactos", "filter.not_captured" to "Disponibles", "search.label" to "Buscar criatura", "search.hint" to "Buscar especie sagrada...", "header.title" to "GUÍA DE LEGENDARIOS", "header.brand" to "DESGRACIAMON 3", "summary" to "{captured} / {total} Registrados", "empty.waiting" to "Esperando datos del servidor...", "empty.results" to "No hay resultados para este filtro.", "entry.captured_by" to "Capturado por {owner}", "entry.not_captured" to "Sin capturar", "entry.captured_at" to "Capturado: {date}", "button.shiny" to "Variocolor", "button.roar" to "Rugido", "viewport.hint" to "Arrastra para rotar • Rueda para zoom • Clic para rugir", "card.captured.unknown" to "Desconocido", "card.captured.date_label" to "Fecha de captura: ", "card.captured.no_date" to "Sin fecha"
        ))
        ClientPlayNetworking.registerGlobalReceiver(LegendaryBestiaryPayload.TYPE) { payload, context ->
            context.client().execute { LegendaryBestiaryClientData.entries = payload.entries }
        }
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            LegendaryBestiaryClientData.entries = emptyList()
        }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is InventoryScreen) {
                val leftPos = (screen.width - 176) / 2
                val topPos = (screen.height - 166) / 2
                val buttonX = leftPos + 138
                val buttonY = topPos + 62
                val buttonSize = 18
                val bestiaryBtn = object : Button(
                    buttonX,
                    buttonY,
                    buttonSize,
                    buttonSize,
                    Component.empty(),
                    { openBestiary() },
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

                        val iconX = x + (width - 16) / 2
                        val iconY = y + (height - 16) / 2

                        val pose = graphics.pose()
                        pose.pushPose()
                        pose.translate(centerX, centerY, 0.0f)
                        pose.scale(scale, scale, 1.0f)
                        pose.translate(-centerX, -centerY, 0.0f)

                        RenderSystem.enableBlend()
                        RenderSystem.setShaderColor(brightness, brightness, brightness, 1.0f)
                        graphics.blit(pokedexPurpleTexture, iconX, iconY, 16, 16, 0f, 0f, 16, 16, 16, 16)
                        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)

                        pose.popPose()

                        if (isHovered) {
                            val tooltip = listOf(
                                RichText.parse("<prism=#E040FB,#9D00FF,#6200EA,#D500F9>Guía de Legendarios</prism>", emptyMap()),
                                Component.literal("§7Consulta los Pokémon legendarios y sus estados")
                            )
                            graphics.renderComponentTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY)
                        }
                    }
                }

                Screens.getButtons(screen).add(bestiaryBtn)
            }
        }
        openKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.legendary_bestiary.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_L,
                "key.category.desgraciamon3"
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openKey.consumeClick()) {
                if (client.player != null) {
                    openBestiary()
                }
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(ClientCommandManager.literal("bestiario").executes {
                openBestiary()
                1
            })
        }
    }

    private fun openBestiary() {
        val client = Minecraft.getInstance()
        if (client.player == null || client.connection == null) return
        ClientPlayNetworking.send(LegendaryBestiaryRequest)
        client.setScreen(LegendaryBestiaryScreen())
    }
}
