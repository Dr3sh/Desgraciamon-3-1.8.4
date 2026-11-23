package com.desgraciamon.vortex.client

import com.desgraciamon.vortex.network.RewardDecisionPacket
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.text.NumberFormat
import java.util.Locale

/**
 * Módulo 5.2: Pantalla de Decisión (Frontend)
 * Permite al jugador ganador elegir entre "Reclamar Anomalía" o "Vender al Mercado Negro ($150,000)".
 */
class VortexRewardScreen(
    private val pokemonName: String,
    private val speciesIdentifier: String,
    private val level: Int,
    private val rewardAmount: Int
) : Screen(Component.literal("Anomalía Dimensional Capturada")) {

    companion object {
        private const val BASE_W = 310f
        private const val BASE_H = 165f
    }
    private var uiScale = 1.0f
    private var renderX = 0f
    private var renderY = 0f

    private var decisionSent = false

    override fun init() {
        super.init()

        val margin = 8f
        uiScale = minOf(1.0f, (width - margin * 2) / BASE_W, (height - margin * 2) / BASE_H)
        renderX = (width - BASE_W * uiScale) / 2f
        renderY = (height - BASE_H * uiScale) / 2f

        val panelWidth = BASE_W.toInt()
        val formattedReward = NumberFormat.getIntegerInstance(Locale.US).format(rewardAmount)

        // Botón 1: Reclamar Anomalía (Inyectar al PC)
        val claimButton = Button.builder(
            Component.literal("Reclamar Anomalía (Enviar al PC)").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
        ) {
            sendDecision(claimPokemon = true)
        }
            .bounds(15, 94, panelWidth - 30, 24)
            .build()

        // Botón 2: Vender al Mercado Negro ($150,000 CobbleDollars)
        val sellButton = Button.builder(
            Component.literal("Vender al Mercado Negro ($$formattedReward)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
        ) {
            sendDecision(claimPokemon = false)
        }
            .bounds(15, 126, panelWidth - 30, 24)
            .build()

        this.addRenderableWidget(claimButton)
        this.addRenderableWidget(sellButton)
    }

    private fun sendDecision(claimPokemon: Boolean) {
        if (decisionSent) return
        decisionSent = true
        ClientPlayNetworking.send(RewardDecisionPacket(claimPokemon = claimPokemon))
        this.onClose()
    }

    override fun shouldCloseOnEsc(): Boolean = false

    override fun isPauseScreen(): Boolean = false

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val localMouseX = ((mouseX - renderX) / uiScale).toInt()
        val localMouseY = ((mouseY - renderY) / uiScale).toInt()

        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick)

        val matrices = guiGraphics.pose()
        matrices.pushPose()
        matrices.translate(renderX, renderY, 0.0f)
        matrices.scale(uiScale, uiScale, 1.0f)

        val panelWidth = BASE_W.toInt()
        val panelHeight = BASE_H.toInt()
        val left = 0
        val top = 0
        val centerX = panelWidth / 2

        // Panel principal con estética del Vórtice Dimensional
        guiGraphics.fill(left, top, left + panelWidth, top + panelHeight, 0xEA120324.toInt())
        guiGraphics.renderOutline(left, top, panelWidth, panelHeight, 0xFF9B30FF.toInt())
        guiGraphics.renderOutline(left + 2, top + 2, panelWidth - 4, panelHeight - 4, 0xFF52088C.toInt())

        // Título y detalles genéticos del Pokémon en el Limbo
        guiGraphics.drawCenteredString(
            this.font,
            Component.literal("ANOMALÍA DIMENSIONAL EN EL LIMBO").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
            centerX,
            top + 14,
            0xFFE088FF.toInt()
        )

        guiGraphics.drawCenteredString(
            this.font,
            Component.literal("Espécimen: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("$pokemonName Shiny (Nv. $level)").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)),
            centerX,
            top + 34,
            0xFFFFFFFF.toInt()
        )

        guiGraphics.drawCenteredString(
            this.font,
            Component.literal("Genética: IVs Perfectos (31 / 31 / 31 / 31 / 31 / 31)").withStyle(ChatFormatting.AQUA),
            centerX,
            top + 48,
            0xFF55FFFF.toInt()
        )

        guiGraphics.drawCenteredString(
            this.font,
            Component.literal("Elige el destino final de esta anomalía:").withStyle(ChatFormatting.WHITE),
            centerX,
            top + 68,
            0xFFCCCCCC.toInt()
        )

        children().filterIsInstance<net.minecraft.client.gui.components.Renderable>().forEach {
            it.render(guiGraphics, localMouseX, localMouseY, partialTick)
        }

        matrices.popPose()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val localX = (mouseX - renderX) / uiScale
        val localY = (mouseY - renderY) / uiScale
        return super.mouseClicked(localX, localY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val localX = (mouseX - renderX) / uiScale
        val localY = (mouseY - renderY) / uiScale
        return super.mouseReleased(localX, localY, button)
    }
}
