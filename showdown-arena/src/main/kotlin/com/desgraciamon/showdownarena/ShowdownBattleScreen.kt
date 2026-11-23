package com.desgraciamon.showdownarena

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import kotlin.math.pow

class ShowdownBattleScreen(
    private val parent: Screen? = null,
    private val targetPlayerName: String? = null
) : Screen(Component.literal("Desgraciamon 3 Combats")) {

    private val isPracticeMode: Boolean = targetPlayerName.isNullOrBlank()

    data class GenOption(val gen: Int, val shortName: String, val region: String)

    private val genOptions = listOf(
        GenOption(0, "Global", "Todas"),
        GenOption(1, "Gen 1", "Kanto"),
        GenOption(2, "Gen 2", "Johto"),
        GenOption(3, "Gen 3", "Hoenn"),
        GenOption(4, "Gen 4", "Sinnoh"),
        GenOption(5, "Gen 5", "Teselia"),
        GenOption(6, "Gen 6", "Kalos"),
        GenOption(7, "Gen 7", "Alola"),
        GenOption(8, "Gen 8", "Galar"),
        GenOption(9, "Gen 9", "Paldea")
    )
    private var selectedGenIndex = 0

    private val catOptions = FormatCategory.entries
    private var selectedCatIndex = 0

    private var selectedLevel = 50 // 50, 100, 0 (Balanceado)
    private var selectedTeamSize = 6 // 6, 4, 3, 2, 1

    private val selectedGen: GenOption get() = genOptions[selectedGenIndex]
    private val selectedCat: FormatCategory get() = catOptions[selectedCatIndex]

    companion object {
        private const val BASE_W = 404f
        private const val BASE_H = 292f
    }

    private var uiScale = 1.0f
    private var renderX = 0f
    private var renderY = 0f
    private val openedAtNanos = System.nanoTime()

    private fun openingProgress(): Float =
        ((System.nanoTime() - openedAtNanos) / 2.8e8f).coerceIn(0.0f, 1.0f)

    override fun init() {
        clearWidgets()
        val margin = 10f
        uiScale = minOf(1.0f, (width - margin * 2) / BASE_W, (height - margin * 2) / BASE_H).coerceAtLeast(0.45f)
        renderX = (width - BASE_W * uiScale) / 2f
        renderY = (height - BASE_H * uiScale) / 2f
    }

    private fun playClick() {
        minecraft?.soundManager?.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    private fun triggerAction() {
        val genCode = if (selectedGen.gen in 1..9) "gen${selectedGen.gen}" else "global"
        val effSize = if (selectedCat == FormatCategory.VGC_DOUBLES && selectedTeamSize < 2) 4 else selectedTeamSize
        if (isPracticeMode) {
            Minecraft.getInstance().connection?.sendCommand("combates practica $genCode ${selectedCat.idSlug} $selectedLevel $effSize")
        } else {
            Minecraft.getInstance().connection?.sendCommand("combates retar $targetPlayerName $genCode ${selectedCat.idSlug} $selectedLevel $effSize")
        }
        Minecraft.getInstance().setScreen(null)
    }

    override fun renderBlurredBackground(delta: Float) {}
    override fun renderMenuBackground(context: GuiGraphics) {}
    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val localX = ((mouseX - renderX) / uiScale).toInt()
        val localY = ((mouseY - renderY) / uiScale).toInt()

        graphics.fill(0, 0, width, height, 0xA8060A10.toInt())

        val matrices = graphics.pose()
        matrices.pushPose()
        matrices.translate(renderX, renderY, 0f)
        matrices.scale(uiScale, uiScale, 1.0f)

        val progress = openingProgress()
        if (progress < 1.0f) {
            val eased = 1.0f - (1.0f - progress).toDouble().pow(3.0).toFloat()
            val animScale = 0.08f + eased * 0.92f
            val cx = BASE_W / 2.0f
            val cy = BASE_H / 2.0f
            matrices.translate(cx, cy, 0f)
            matrices.scale(animScale, animScale, 1.0f)
            matrices.translate(-cx, -cy, 0f)
        }

        val panelW = BASE_W.toInt()
        val panelH = BASE_H.toInt()
        val accent = if (isPracticeMode) 0xFF38BDF8.toInt() else 0xFFF59E0B.toInt()

        // Main card
        graphics.fill(0, 0, panelW, panelH, 0xF5111824.toInt())
        graphics.fill(0, 0, panelW, 34, 0xFF172233.toInt())
        graphics.fill(0, 0, panelW, 2, accent)
        graphics.fill(0, 34, panelW, 35, 0xFF27394F.toInt())
        graphics.renderOutline(0, 0, panelW, panelH, 0xFF243447.toInt())

        // Header
        graphics.drawString(font, "DESGRACIAMON 3 COMBATS", 16, 9, accent, false)
        val subtitle = if (isPracticeMode) "Modo Practica vs IA (sin afectar tu equipo real)" else "Desafio PvP contra: §f$targetPlayerName"
        graphics.drawString(font, subtitle, 16, 20, 0xFF94A3B8.toInt(), false)

        // 1. Generacion (2 rows x 5 cols)
        graphics.drawString(font, "GENERACION", 16, 42, 0xFF7DD3FC.toInt(), false)
        val genStartY = 53
        val genCellW = 71
        val genCellH = 20
        val genGapX = 4
        val genGapY = 4
        genOptions.forEachIndexed { idx, opt ->
            val col = idx % 5
            val row = idx / 5
            val gx = 16 + col * (genCellW + genGapX)
            val gy = genStartY + row * (genCellH + genGapY)
            val isSel = idx == selectedGenIndex
            val isHover = localX in gx..(gx + genCellW) && localY in gy..(gy + genCellH)
            val bg = when {
                isSel -> 0xFF1D4D63.toInt()
                isHover -> 0xFF1C2C3F.toInt()
                else -> 0xFF15202E.toInt()
            }
            val border = if (isSel) accent else if (isHover) 0xFF476985.toInt() else 0xFF233447.toInt()
            graphics.fill(gx, gy, gx + genCellW, gy + genCellH, bg)
            graphics.renderOutline(gx, gy, genCellW, genCellH, border)
            val textCol = if (isSel) 0xFFFFFFFF.toInt() else 0xFFCBD5E1.toInt()
            graphics.drawCenteredString(font, opt.shortName, gx + genCellW / 2, gy + 6, textCol)
        }

        // 2. Modalidad (2 rows x 3 cols)
        graphics.drawString(font, "MODALIDAD DE COMBATE", 16, 105, 0xFF7DD3FC.toInt(), false)
        val catStartY = 116
        val catCellW = 121
        val catCellH = 22
        val catGapX = 4
        val catGapY = 4
        catOptions.forEachIndexed { idx, cat ->
            val col = idx % 3
            val row = idx / 3
            val cx = 16 + col * (catCellW + catGapX)
            val cy = catStartY + row * (catCellH + catGapY)
            val isSel = idx == selectedCatIndex
            val isHover = localX in cx..(cx + catCellW) && localY in cy..(cy + catCellH)
            val bg = when {
                isSel -> 0xFF1E4D45.toInt()
                isHover -> 0xFF1C2C3F.toInt()
                else -> 0xFF15202E.toInt()
            }
            val border = if (isSel) 0xFF34D399.toInt() else if (isHover) 0xFF476985.toInt() else 0xFF233447.toInt()
            graphics.fill(cx, cy, cx + catCellW, cy + catCellH, bg)
            graphics.renderOutline(cx, cy, catCellW, catCellH, border)
            val label = shortCategoryLabel(cat)
            graphics.drawCenteredString(font, label, cx + catCellW / 2, cy + 7, if (isSel) 0xFFFFFFFF.toInt() else 0xFFCBD5E1.toInt())
        }

        // 3. Nivel + Tamano side by side
        val optY = 172
        graphics.drawString(font, "NIVEL", 16, optY, 0xFF7DD3FC.toInt(), false)
        val levels = listOf(Triple("Nv. 50", 50, 16), Triple("Nv. 100", 100, 74), Triple("Auto", 0, 134))
        levels.forEach { (label, lvl, lx) ->
            val lw = 54
            val isSel = selectedLevel == lvl
            val isHover = localX in lx..(lx + lw) && localY in (optY + 11)..(optY + 29)
            val bg = if (isSel) 0xFF1D4D63.toInt() else if (isHover) 0xFF1C2C3F.toInt() else 0xFF15202E.toInt()
            val border = if (isSel) accent else 0xFF233447.toInt()
            graphics.fill(lx, optY + 11, lx + lw, optY + 29, bg)
            graphics.renderOutline(lx, optY + 11, lw, 18, border)
            graphics.drawCenteredString(font, label, lx + lw / 2, optY + 16, if (isSel) 0xFFFFFFFF.toInt() else 0xFFCBD5E1.toInt())
        }

        graphics.drawString(font, "TAMANO DE EQUIPO", 204, optY, 0xFF7DD3FC.toInt(), false)
        val sizes = listOf(6 to 204, 4 to 242, 3 to 280, 2 to 318, 1 to 356)
        sizes.forEach { (sz, sx) ->
            val sw = 34
            val disabled = selectedCat == FormatCategory.VGC_DOUBLES && sz < 2
            val isSel = selectedTeamSize == sz && !disabled
            val isHover = !disabled && localX in sx..(sx + sw) && localY in (optY + 11)..(optY + 29)
            val bg = when {
                disabled -> 0xFF0E141D.toInt()
                isSel -> 0xFF1D4D63.toInt()
                isHover -> 0xFF1C2C3F.toInt()
                else -> 0xFF15202E.toInt()
            }
            val border = if (isSel) accent else 0xFF233447.toInt()
            graphics.fill(sx, optY + 11, sx + sw, optY + 29, bg)
            graphics.renderOutline(sx, optY + 11, sw, 18, border)
            val col = when {
                disabled -> 0xFF475569.toInt()
                isSel -> 0xFFFFFFFF.toInt()
                else -> 0xFFCBD5E1.toInt()
            }
            graphics.drawCenteredString(font, "${sz}v${sz}", sx + sw / 2, optY + 16, col)
        }

        // 4. Summary & Description box
        val infoY = 208
        val infoH = 44
        graphics.fill(16, infoY, panelW - 16, infoY + infoH, 0xFF0C121B.toInt())
        graphics.renderOutline(16, infoY, panelW - 32, infoH, 0xFF1E2D3D.toInt())

        val modeBadge = if (selectedCat == FormatCategory.VGC_DOUBLES) "Dobles 2v2" else "Individual 1v1"
        val lvlBadge = if (selectedLevel == 0) "Balanceado" else "Nv. $selectedLevel"
        graphics.drawString(
            font,
            "${selectedGen.shortName} (${selectedGen.region}) • ${shortCategoryLabel(selectedCat)} • $modeBadge • $lvlBadge",
            24,
            infoY + 6,
            0xFFFBBF24.toInt(),
            false
        )
        val desc = font.plainSubstrByWidth(selectedCat.description, panelW - 48)
        graphics.drawString(font, desc, 24, infoY + 18, 0xFF94A3B8.toInt(), false)

        val zoneHint = if (ArenaZoneClient.isEnabled && ArenaZoneClient.currentZone != null) {
            "§bArena activa: §f${ArenaZoneClient.currentZone?.name}"
        } else {
            "§aCombate libre en cualquier ubicacion (equipos siempre distintos)"
        }
        graphics.drawString(font, zoneHint, 24, infoY + 31, 0xFF34D399.toInt(), false)

        // Custom flat Close button (top-right)
        val closeX = panelW - 30
        val closeY = 8
        val closeW = 20
        val closeH = 18
        val closeHover = localX in closeX..(closeX + closeW) && localY in closeY..(closeY + closeH)
        graphics.fill(closeX, closeY, closeX + closeW, closeY + closeH, if (closeHover) 0xFF7F1D1D.toInt() else 0xFF2A161B.toInt())
        graphics.renderOutline(closeX, closeY, closeW, closeH, if (closeHover) 0xFFEF4444.toInt() else 0xFF5C232B.toInt())
        graphics.drawCenteredString(font, "X", closeX + closeW / 2, closeY + 5, 0xFFFCA5A5.toInt())

        // Custom flat Action button (bottom)
        val actionText = if (isPracticeMode) "INICIAR PRACTICA" else "ENVIAR DESAFIO A $targetPlayerName"
        val actX = 16
        val actY = panelH - 34
        val actW = panelW - 32
        val actH = 22
        val actHover = localX in actX..(actX + actW) && localY in actY..(actY + actH)
        val actBg = if (isPracticeMode) {
            if (actHover) 0xFF0284C7.toInt() else 0xFF0369A1.toInt()
        } else {
            if (actHover) 0xFFD97706.toInt() else 0xFFB45309.toInt()
        }
        graphics.fill(actX, actY, actX + actW, actY + actH, actBg)
        graphics.renderOutline(actX, actY, actW, actH, if (actHover) 0xFFFFFFFF.toInt() else accent)
        graphics.drawCenteredString(font, actionText, actX + actW / 2, actY + 7, 0xFFFFFFFF.toInt())

        matrices.popPose()
    }

    private fun shortCategoryLabel(cat: FormatCategory): String = when (cat) {
        FormatCategory.RANDOM_COMPETITIVE -> "Random Competitivo"
        FormatCategory.RANDOM_CHAOS -> "Random Caos"
        FormatCategory.OU -> "OU (Smogon)"
        FormatCategory.UBERS -> "Ubers (Leyendas)"
        FormatCategory.VGC_DOUBLES -> "VGC Dobles"
        FormatCategory.MONOTYPE -> "Monotype"
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (openingProgress() < 1.0f) return true
        val localX = ((mouseX - renderX) / uiScale).toInt()
        val localY = ((mouseY - renderY) / uiScale).toInt()
        val panelW = BASE_W.toInt()
        val panelH = BASE_H.toInt()

        if (button == 0) {
            // Close button
            if (localX in (panelW - 30)..(panelW - 10) && localY in 8..26) {
                playClick()
                Minecraft.getInstance().setScreen(parent)
                return true
            }
            // Action button
            if (localX in 16..(panelW - 16) && localY in (panelH - 34)..(panelH - 12)) {
                playClick()
                triggerAction()
                return true
            }
        }

        // 1. Gen grid
        val genStartY = 53
        val genCellW = 71
        val genCellH = 20
        val genGapX = 4
        val genGapY = 4
        genOptions.forEachIndexed { idx, _ ->
            val col = idx % 5
            val row = idx / 5
            val gx = 16 + col * (genCellW + genGapX)
            val gy = genStartY + row * (genCellH + genGapY)
            if (localX in gx..(gx + genCellW) && localY in gy..(gy + genCellH)) {
                selectedGenIndex = idx
                return true
            }
        }

        // 2. Category grid
        val catStartY = 116
        val catCellW = 121
        val catCellH = 22
        val catGapX = 4
        val catGapY = 4
        catOptions.forEachIndexed { idx, cat ->
            val col = idx % 3
            val row = idx / 3
            val cx = 16 + col * (catCellW + catGapX)
            val cy = catStartY + row * (catCellH + catGapY)
            if (localX in cx..(cx + catCellW) && localY in cy..(cy + catCellH)) {
                selectedCatIndex = idx
                if (cat == FormatCategory.VGC_DOUBLES && selectedTeamSize < 2) {
                    selectedTeamSize = 4
                }
                return true
            }
        }

        // 3. Level pills
        val optY = 172
        val levels = listOf(50 to 16, 100 to 74, 0 to 134)
        for ((lvl, lx) in levels) {
            if (localX in lx..(lx + 54) && localY in (optY + 11)..(optY + 29)) {
                selectedLevel = lvl
                return true
            }
        }

        // 4. Size pills
        val sizes = listOf(6 to 204, 4 to 242, 3 to 280, 2 to 318, 1 to 356)
        for ((sz, sx) in sizes) {
            if (selectedCat == FormatCategory.VGC_DOUBLES && sz < 2) continue
            if (localX in sx..(sx + 34) && localY in (optY + 11)..(optY + 29)) {
                selectedTeamSize = sz
                return true
            }
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun isPauseScreen() = false

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }
}
