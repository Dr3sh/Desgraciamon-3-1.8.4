package com.desgraciamon.discordbridge

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import kotlin.math.pow
import kotlin.math.roundToInt

class DiscordSettingsScreen(private val previousScreen: Screen?) : Screen(TextCatalog.component("settings.title")) {
    companion object {
        private const val BASE_W = 408f
        private const val BASE_H = 294f
        private const val PAD_X = 14
        private const val HEADER_H = 34
        private const val BADGES_CARD_Y = 42
        private const val BADGES_CARD_H = 34
        private const val LIST_TOP = 84
        private const val FOOTER_H = 30
        private const val ROW_H = 32
        private const val ROW_GAP = 4
        private const val SECTION_H = 16
    }

    private var uiScale = 1.0f
    private var renderX = 0f
    private var renderY = 0f

    private val openedAtNanos = System.nanoTime()
    private var scrollOffset: Double = 0.0

    @JvmField
    val hasBadgesTile: Boolean = true

    private fun openingProgress(): Float = ((System.nanoTime() - openedAtNanos) / 2.8e8f).coerceIn(0.0f, 1.0f)

    private fun playClick() {
        minecraft?.soundManager?.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    override fun renderBlurredBackground(delta: Float) {}
    override fun renderMenuBackground(graphics: GuiGraphics) {}

    private sealed class ListItem {
        data class SectionHeader(val title: String) : ListItem()
        data class SettingRow(
            val id: String,
            val title: String,
            val description: String,
            val isEnabled: () -> Boolean,
            val isAvailable: () -> Boolean = { true },
            val enabledLabel: String = "ACTIVADO",
            val disabledLabel: String = "DESACTIVADO",
            val customPillText: (() -> String)? = null,
            val disabledReason: (() -> String?)? = null,
            val onToggle: () -> Unit
        ) : ListItem()
    }

    private val items: List<ListItem> = listOf(
        ListItem.SectionHeader("PERFIL Y PANTALLA"),
        ListItem.SettingRow(
            id = "privacy",
            title = "Privacidad de Equipo (Discord)",
            description = "Oculta tu equipo al usar /perfil o /equipo.",
            isEnabled = { DiscordSettingsClientData.isDiscordLinked && DiscordSettingsClientData.isTeamPrivate == true },
            isAvailable = { DiscordSettingsClientData.isDiscordLinked },
            enabledLabel = "PRIVADO",
            disabledLabel = "PUBLICO",
            customPillText = {
                if (!DiscordSettingsClientData.isDiscordLinked) "NO VINCULADO"
                else if (DiscordSettingsClientData.isTeamPrivate == true) "PRIVADO"
                else "PUBLICO"
            },
            disabledReason = {
                if (!DiscordSettingsClientData.isDiscordLinked) "Tu perfil de juego debe estar vinculado con discord (/vincular)"
                else null
            },
            onToggle = {
                if (DiscordSettingsClientData.isDiscordLinked) {
                    ClientPlayNetworking.send(TeamPrivacyToggleRequest)
                }
            }
        ),
        ListItem.SettingRow(
            id = "bossbar",
            title = "Barra de Cooldown Legendarios",
            description = "Muestra el tiempo restante para capturar legendarios.",
            isEnabled = { DiscordSettingsClientData.cooldownBossBarEnabled },
            isAvailable = { DiscordSettingsClientData.cooldownInstalled },
            onToggle = {
                if (DiscordSettingsClientData.cooldownInstalled) {
                    ClientPlayNetworking.send(CooldownPreferenceToggleRequest("bossbar"))
                }
            }
        ),
        ListItem.SettingRow(
            id = "guild_synergy_bar",
            title = "Barra de Sinergia de Gremio",
            description = "Muestra tu bono de XP cerca de tu gremio.",
            isEnabled = { GuildSynergySettingsIntegration.isEnabled() },
            isAvailable = { GuildSynergySettingsIntegration.isInstalled() },
            customPillText = { GuildSynergySettingsIntegration.getModeName() },
            onToggle = {
                if (GuildSynergySettingsIntegration.isInstalled()) {
                    GuildSynergySettingsIntegration.cycle()
                }
            }
        ),
        ListItem.SectionHeader("AVISOS EN CHAT"),
        ListItem.SettingRow(
            id = "mission_progress_chat",
            title = "Progreso de Misiones",
            description = "Avisa en chat al avanzar en tus misiones.",
            isEnabled = { ClientAlertPreferences.data.missionProgressChat },
            onToggle = { ClientAlertPreferences.toggleMissionProgress() }
        ),
        ListItem.SettingRow(
            id = "biome_atlas_chat",
            title = "Avisos del Radar de Biomas",
            description = "Muestra descubrimientos y coords en el chat.",
            isEnabled = { ClientAlertPreferences.data.biomeAtlasChat },
            onToggle = { ClientAlertPreferences.toggleBiomeAtlas() }
        ),
        ListItem.SettingRow(
            id = "captures",
            title = "Capturas de Legendarios",
            description = "Avisa en chat cuando alguien captura un legendario.",
            isEnabled = { DiscordSettingsClientData.captureNoticeEnabled },
            isAvailable = { DiscordSettingsClientData.catchAssistantInstalled },
            onToggle = {
                if (DiscordSettingsClientData.catchAssistantInstalled) {
                    ClientPlayNetworking.send(CooldownPreferenceToggleRequest("capture"))
                }
            }
        ),
        ListItem.SettingRow(
            id = "join_notice",
            title = "Aviso de Cooldown al Entrar",
            description = "Informa al entrar si ya puedes capturar legendarios.",
            isEnabled = { DiscordSettingsClientData.cooldownJoinNoticeEnabled },
            isAvailable = { DiscordSettingsClientData.cooldownInstalled },
            onToggle = {
                if (DiscordSettingsClientData.cooldownInstalled) {
                    ClientPlayNetworking.send(CooldownPreferenceToggleRequest("notice"))
                }
            }
        ),
        ListItem.SettingRow(
            id = "market_alerts",
            title = "Alertas del Mercado",
            description = "Avisa si un objeto baja mucho de precio.",
            isEnabled = { DiscordSettingsClientData.marketAlertsEnabled },
            isAvailable = { MarketAlertSettingsIntegration.isInstalled() },
            onToggle = {
                if (MarketAlertSettingsIntegration.isInstalled()) {
                    DiscordSettingsClientData.marketAlertsEnabled = MarketAlertSettingsIntegration.toggle()
                }
            }
        )
    )

    private fun visibleItems(): List<ListItem> {
        val out = mutableListOf<ListItem>()
        for (item in items) {
            if (item is ListItem.SettingRow && !item.isAvailable()) continue
            out.add(item)
        }
        return out
    }

    private fun rowHeightFor(row: ListItem.SettingRow, rowW: Int): Int {
        val pillText = row.customPillText?.invoke() ?: if (row.isEnabled()) row.enabledLabel else row.disabledLabel
        val pillW = maxOf(74, font.width(pillText) + 14)
        val textMaxW = (rowW - pillW - 26).coerceAtLeast(80)
        val lines = font.split(Component.literal(row.description), textMaxW)
        return maxOf(ROW_H, 20 + lines.size * 10)
    }

    private fun computeTotalListHeight(list: List<ListItem>, rowW: Int = BASE_W.toInt() - PAD_X * 2 - 8): Int {
        var h = 0
        for (item in list) {
            h += when (item) {
                is ListItem.SectionHeader -> SECTION_H + 2
                is ListItem.SettingRow -> rowHeightFor(item, rowW) + ROW_GAP
            }
        }
        return h
    }

    private fun maxScrollFor(list: List<ListItem>): Double {
        val visibleH = BASE_H.toInt() - FOOTER_H - LIST_TOP
        return (computeTotalListHeight(list) - visibleH).coerceAtLeast(0).toDouble()
    }

    override fun init() {
        val margin = 10f
        uiScale = minOf(1.0f, (width - margin * 2) / BASE_W, (height - margin * 2) / BASE_H).coerceAtLeast(0.45f)
        renderX = (width - BASE_W * uiScale) / 2f
        renderY = (height - BASE_H * uiScale) / 2f

        ClientPlayNetworking.send(TeamPrivacyRequest)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val list = visibleItems()
        val maxScroll = maxScrollFor(list)
        if (maxScroll > 0.0) {
            scrollOffset = (scrollOffset - verticalAmount * 24.0).coerceIn(0.0, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (openingProgress() < 1.0F) return true
        val localX = ((mouseX - renderX) / uiScale).toInt()
        val localY = ((mouseY - renderY) / uiScale).toInt()
        val panelW = BASE_W.toInt()
        val panelH = BASE_H.toInt()

        // 1. Featured top Insignias card (always first!)
        val cardW = panelW - PAD_X * 2
        if (BadgesTileIntegration.isInstalled() &&
            localX in PAD_X..(PAD_X + cardW) &&
            localY in BADGES_CARD_Y..(BADGES_CARD_Y + BADGES_CARD_H)
        ) {
            BadgesTileIntegration.openScreen(this)
            return true
        }

        // 2. Scrollable setting rows
        val listBottom = panelH - FOOTER_H
        if (localX in PAD_X..(panelW - PAD_X - 8) && localY in LIST_TOP..listBottom) {
            val list = visibleItems()
            val rowW = cardW - (if (maxScrollFor(list) > 0.0) 8 else 0)
            var curY = LIST_TOP - scrollOffset.toInt()
            for (item in list) {
                when (item) {
                    is ListItem.SectionHeader -> curY += SECTION_H + 2
                    is ListItem.SettingRow -> {
                        val rh = rowHeightFor(item, rowW)
                        if (localY in curY..(curY + rh)) {
                            if (item.isAvailable()) {
                                playClick()
                                item.onToggle()
                            }
                            return true
                        }
                        curY += rh + ROW_GAP
                    }
                }
            }
        }

        // 3. Custom flat Close button
        val closeW = 124
        val closeX = (panelW - closeW) / 2
        val closeY = panelH - 24
        if (button == 0 && localX in closeX..(closeX + closeW) && localY in closeY..(closeY + 18)) {
            playClick()
            onClose()
            return true
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val localX = ((mouseX - renderX) / uiScale).toInt()
        val localY = ((mouseY - renderY) / uiScale).toInt()
        val panelW = BASE_W.toInt()
        val panelH = BASE_H.toInt()

        graphics.fill(0, 0, width, height, 0xA8070B0E.toInt())

        val progress = openingProgress()
        val eased = 1.0f - (1.0f - progress).toDouble().pow(3.0).toFloat()
        val animScale = 0.08f + eased * 0.92f

        val matrices = graphics.pose()
        matrices.pushPose()
        matrices.translate(renderX, renderY, 0.0f)
        matrices.scale(uiScale, uiScale, 1.0f)
        if (animScale < 0.999f) {
            val cx = BASE_W / 2.0f
            val cy = BASE_H / 2.0f
            matrices.translate(cx, cy, 0.0f)
            matrices.scale(animScale, animScale, 1.0f)
            matrices.translate(-cx, -cy, 0.0f)
        }

        // Main panel frame (vibrant indigo-slate theme)
        graphics.fill(0, 0, panelW, panelH, 0xF5101424.toInt())
        graphics.fill(0, 0, panelW, HEADER_H, 0xFF171D34.toInt())
        graphics.fill(0, 0, panelW, 2, 0xFF38BDF8.toInt())
        graphics.fill(0, HEADER_H, panelW, HEADER_H + 1, 0xFF2B3659.toInt())
        drawOutline(graphics, 0, 0, panelW, panelH, 0xFF2B3659.toInt())

        // Header text
        graphics.drawString(font, "CONFIGURACION DE DESGRACIAMON 3", PAD_X, 9, 0xFFF8FAFC.toInt(), false)
        graphics.drawString(font, "Insignias, avisos en chat y preferencias visuales", PAD_X, 20, 0xFF93C5FD.toInt(), false)

        // 1. Featured Insignias card (ALWAYS FIRST — royal gold & amethyst)
        val cardW = panelW - PAD_X * 2
        val badgesInstalled = BadgesTileIntegration.isInstalled()
        val badgesHover = badgesInstalled &&
            localX in PAD_X..(PAD_X + cardW) &&
            localY in BADGES_CARD_Y..(BADGES_CARD_Y + BADGES_CARD_H)
        val badgesBg = if (badgesHover) 0xFF2A2344.toInt() else 0xFF1F1B36.toInt()
        val badgesBorder = if (badgesHover) 0xFFFBBF24.toInt() else 0xFF6D5196.toInt()

        graphics.fill(PAD_X, BADGES_CARD_Y, PAD_X + cardW, BADGES_CARD_Y + BADGES_CARD_H, badgesBg)
        graphics.fill(PAD_X, BADGES_CARD_Y, PAD_X + 3, BADGES_CARD_Y + BADGES_CARD_H, 0xFFF59E0B.toInt())
        drawOutline(graphics, PAD_X, BADGES_CARD_Y, cardW, BADGES_CARD_H, badgesBorder)

        val badgeTitle = BadgesTileIntegration.summaryLabel()
        graphics.drawString(font, badgeTitle, PAD_X + 10, BADGES_CARD_Y + 6, 0xFFFDE68A.toInt(), false)
        graphics.drawString(
            font,
            "Muestra u oculta tus insignias en chat y nombre",
            PAD_X + 10,
            BADGES_CARD_Y + 19,
            0xFFD8B4FE.toInt(),
            false
        )

        val openPillW = 62
        val openPillX = PAD_X + cardW - openPillW - 8
        val openPillY = BADGES_CARD_Y + 9
        graphics.fill(openPillX, openPillY, openPillX + openPillW, openPillY + 16, if (badgesHover) 0xFFD97706.toInt() else 0xFF92400E.toInt())
        drawOutline(graphics, openPillX, openPillY, openPillW, 16, 0xFFFBBF24.toInt())
        graphics.drawCenteredString(
            font,
            "ABRIR >",
            openPillX + openPillW / 2,
            openPillY + 4,
            0xFFFFFFFF.toInt()
        )

        // 2. Scrollable clean vertical list
        val list = visibleItems()
        val maxScroll = maxScrollFor(list)
        scrollOffset = scrollOffset.coerceIn(0.0, maxScroll)

        val listBottom = panelH - FOOTER_H
        val cxSc = BASE_W / 2.0f
        val cySc = BASE_H / 2.0f
        val scissorLeft = (renderX + (cxSc + (PAD_X - cxSc) * animScale) * uiScale).roundToInt()
        val scissorTop = (renderY + (cySc + (LIST_TOP - cySc) * animScale) * uiScale).roundToInt()
        val scissorRight = (renderX + (cxSc + (panelW - PAD_X - cxSc) * animScale) * uiScale).roundToInt()
        val scissorBottom = (renderY + (cySc + (listBottom - cySc) * animScale) * uiScale).roundToInt()

        if (scissorRight > scissorLeft && scissorBottom > scissorTop) {
            graphics.enableScissor(scissorLeft, scissorTop, scissorRight, scissorBottom)
        }

        val rowW = cardW - (if (maxScrollFor(list) > 0.0) 8 else 0)
        var curY = LIST_TOP - scrollOffset.toInt()
        var hoveredTooltip: String? = null

        for (item in list) {
            when (item) {
                is ListItem.SectionHeader -> {
                    if (curY + SECTION_H >= LIST_TOP && curY <= listBottom) {
                        val secColor = if (item.title.contains("CHAT")) 0xFFC084FC.toInt() else 0xFF38BDF8.toInt()
                        graphics.drawString(font, item.title, PAD_X + 2, curY + 4, secColor, false)
                        val lineX = PAD_X + font.width(item.title) + 8
                        if (lineX < PAD_X + rowW) {
                            graphics.fill(lineX, curY + 8, PAD_X + rowW, curY + 9, 0xFF2B3659.toInt())
                        }
                    }
                    curY += SECTION_H + 2
                }
                is ListItem.SettingRow -> {
                    val rh = rowHeightFor(item, rowW)
                    if (curY + rh >= LIST_TOP && curY <= listBottom) {
                        val available = item.isAvailable()
                        val isHover = localX in PAD_X..(PAD_X + rowW) &&
                            localY in maxOf(curY, LIST_TOP)..minOf(curY + rh, listBottom)
                        if (isHover && !available) {
                            hoveredTooltip = item.disabledReason?.invoke()
                        }
                        val enabled = item.isEnabled()
                        val bg = when {
                            !available -> 0xFF111524.toInt()
                            isHover -> 0xFF1F2847.toInt()
                            else -> 0xFF161C33.toInt()
                        }
                        val border = when {
                            !available && isHover -> 0xFF475569.toInt()
                            !available -> 0xFF1E263D.toInt()
                            isHover -> 0xFF60A5FA.toInt()
                            else -> 0xFF263052.toInt()
                        }
                        val accent = when {
                            !available -> 0xFF475569.toInt()
                            enabled -> 0xFF34D399.toInt()
                            else -> 0xFF64748B.toInt()
                        }

                        graphics.fill(PAD_X, curY, PAD_X + rowW, curY + rh, bg)
                        graphics.fill(PAD_X, curY, PAD_X + 3, curY + rh, accent)
                        drawOutline(graphics, PAD_X, curY, rowW, rh, border)

                        val pillText = item.customPillText?.invoke() ?: if (enabled) item.enabledLabel else item.disabledLabel
                        val pillW = maxOf(74, font.width(pillText) + 14)
                        val pillX = PAD_X + rowW - pillW - 8
                        val pillY = curY + (rh - 16) / 2
                        val pillBg = when {
                            !available -> 0xFF1E2436.toInt()
                            enabled -> 0xFF065F46.toInt()
                            else -> 0xFF283044.toInt()
                        }
                        val pillBorder = when {
                            !available -> 0xFF334155.toInt()
                            enabled -> 0xFF34D399.toInt()
                            else -> 0xFF475569.toInt()
                        }
                        val pillTxtCol = when {
                            !available -> 0xFF64748B.toInt()
                            enabled -> 0xFFA7F3D0.toInt()
                            else -> 0xFFE2E8F0.toInt()
                        }

                        graphics.fill(pillX, pillY, pillX + pillW, pillY + 16, pillBg)
                        drawOutline(graphics, pillX, pillY, pillW, 16, pillBorder)
                        graphics.drawCenteredString(font, pillText, pillX + pillW / 2, pillY + 4, pillTxtCol)

                        val textMaxW = (pillX - (PAD_X + 10) - 8).coerceAtLeast(80)
                        val titleColor = if (available) 0xFFFFFFFF.toInt() else 0xFF64748B.toInt()
                        graphics.drawString(
                            font,
                            font.plainSubstrByWidth(item.title, textMaxW),
                            PAD_X + 10,
                            curY + 6,
                            titleColor,
                            false
                        )
                        val descLines = font.split(Component.literal(item.description), textMaxW)
                        val descColor = when {
                            !available -> 0xFF475569.toInt()
                            isHover -> 0xFFCBD5E1.toInt()
                            else -> 0xFF94A3B8.toInt()
                        }
                        for ((li, line) in descLines.withIndex()) {
                            graphics.drawString(font, line, PAD_X + 10, curY + 18 + li * 10, descColor, false)
                        }
                    }
                    curY += rh + ROW_GAP
                }
            }
        }

        if (scissorRight > scissorLeft && scissorBottom > scissorTop) {
            graphics.disableScissor()
        }

        // Scrollbar if needed
        if (maxScroll > 0.0) {
            val trackX = panelW - PAD_X - 4
            val trackY = LIST_TOP
            val trackH = listBottom - LIST_TOP
            graphics.fill(trackX, trackY, trackX + 3, trackY + trackH, 0xFF1A2328.toInt())
            val totalH = computeTotalListHeight(list).coerceAtLeast(1)
            val thumbH = ((trackH.toFloat() / totalH.toFloat()) * trackH).toInt().coerceIn(18, trackH)
            val thumbY = trackY + ((scrollOffset / maxScroll) * (trackH - thumbH)).toInt()
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF34D399.toInt())
        }

        // Footer divider + Custom flat Close button
        graphics.fill(1, listBottom + 2, panelW - 1, listBottom + 3, 0xFF222E33.toInt())
        val closeW = 124
        val closeX = (panelW - closeW) / 2
        val closeY = panelH - 24
        val closeHover = localX in closeX..(closeX + closeW) && localY in closeY..(closeY + 18)
        graphics.fill(closeX, closeY, closeX + closeW, closeY + 18, if (closeHover) 0xFF23373D.toInt() else 0xFF172226.toInt())
        drawOutline(graphics, closeX, closeY, closeW, 18, if (closeHover) 0xFF5EEAD4.toInt() else 0xFF2D4349.toInt())
        graphics.drawCenteredString(font, "Cerrar", closeX + closeW / 2, closeY + 5, 0xFFFFFFFF.toInt())

        matrices.popPose()
        if (hoveredTooltip != null) {
            graphics.renderTooltip(font, Component.literal(hoveredTooltip), mouseX, mouseY)
        }
    }

    override fun onClose() {
        minecraft?.setScreen(previousScreen)
    }

    fun updateSettings(payload: TeamPrivacyStatusPayload) {
        DiscordSettingsClientData.isTeamPrivate = payload.isPrivate
        DiscordSettingsClientData.isDiscordLinked = payload.isDiscordLinked
        DiscordSettingsClientData.cooldownInstalled = payload.cooldownInstalled
        DiscordSettingsClientData.cooldownBossBarEnabled = payload.cooldownBossBarEnabled
        DiscordSettingsClientData.cooldownJoinNoticeEnabled = payload.cooldownJoinNoticeEnabled
        DiscordSettingsClientData.catchAssistantInstalled = payload.catchAssistantInstalled
        DiscordSettingsClientData.captureNoticeEnabled = payload.catchNoticeEnabled
    }

    private fun drawOutline(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, color: Int) {
        graphics.fill(x, y, x + w, y + 1, color)
        graphics.fill(x, y + h - 1, x + w, y + h, color)
        graphics.fill(x, y + 1, x + 1, y + h - 1, color)
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color)
    }
}

object DiscordSettingsClientData {
    var isTeamPrivate: Boolean? = null
    var isDiscordLinked = false
    var cooldownInstalled = false
    var cooldownBossBarEnabled = false
    var cooldownJoinNoticeEnabled = false
    var catchAssistantInstalled = false
    var captureNoticeEnabled = false
    var marketAlertsEnabled = MarketAlertSettingsIntegration.read()
    var mirrorMotionBlurEnabled = MirrorMotionBlurIntegration.read()
    var guildSynergyInvEnabled = GuildSynergySettingsIntegration.readInventory()
    var guildSynergyActionBarEnabled = GuildSynergySettingsIntegration.readActionBar()
}

internal object MirrorMotionBlurIntegration {
    private const val SETTINGS_CLASS = "com.desgraciamon.mirror.MirrorClientSettings"

    fun isInstalled(): Boolean = runCatching {
        Class.forName(SETTINGS_CLASS)
        true
    }.getOrDefault(false)

    fun read(): Boolean = runCatching {
        val clazz = Class.forName(SETTINGS_CLASS)
        clazz.getMethod("isMotionBlurEnabled").invoke(null) as Boolean
    }.getOrDefault(true)

    fun toggle(): Boolean = runCatching {
        val clazz = Class.forName(SETTINGS_CLASS)
        clazz.getMethod("toggleMotionBlur").invoke(null) as Boolean
    }.getOrDefault(true)
}

internal object MarketAlertSettingsIntegration {
    private const val SETTINGS_CLASS = "com.desgraciamon.economy.MarketClientConfig"

    fun isInstalled(): Boolean = runCatching {
        Class.forName(SETTINGS_CLASS)
        true
    }.getOrDefault(false)

    fun read(): Boolean = runCatching {
        val clazz = Class.forName(SETTINGS_CLASS)
        clazz.getMethod("isAlertsEnabled").invoke(null) as Boolean
    }.getOrDefault(true)

    fun toggle(): Boolean = runCatching {
        val clazz = Class.forName(SETTINGS_CLASS)
        clazz.getMethod("toggleAlerts").invoke(null) as Boolean
    }.getOrDefault(true)
}

internal object BadgesTileIntegration {
    private const val BADGES_STATE_CLASS = "com.desgraciamon.badges.BadgesClientState"

    fun isInstalled(): Boolean = runCatching {
        Class.forName(BADGES_STATE_CLASS)
        true
    }.getOrDefault(false)

    fun summaryLabel(): String = runCatching {
        val clazz = Class.forName(BADGES_STATE_CLASS)
        val comp = clazz.getMethod("f7ButtonSummary").invoke(null) as? Component
        comp?.string?.takeIf { it.isNotBlank() } ?: "Mis Insignias"
    }.getOrDefault("Mis Insignias")

    fun openScreen(parent: Screen) {
        runCatching {
            val clazz = Class.forName(BADGES_STATE_CLASS)
            clazz.getMethod("openBadgesScreen", Screen::class.java).invoke(null, parent)
        }
    }
}

internal object GuildSynergySettingsIntegration {
    private const val CONFIG_CLASS = "net.desgraciamon.guilds.GuildClientConfig"

    fun isInstalled(): Boolean = runCatching {
        Class.forName(CONFIG_CLASS)
        true
    }.getOrDefault(false)

    fun isEnabled(): Boolean = runCatching {
        val clazz = Class.forName(CONFIG_CLASS)
        clazz.getMethod("isSynergyBarEnabled").invoke(null) as Boolean
    }.getOrDefault(true)

    fun getModeName(): String = runCatching {
        val clazz = Class.forName(CONFIG_CLASS)
        clazz.getMethod("getSynergyBarModeName").invoke(null) as String
    }.getOrDefault("CICLO (6s)")

    fun cycle(): String = runCatching {
        val clazz = Class.forName(CONFIG_CLASS)
        clazz.getMethod("cycleSynergyBarMode").invoke(null) as String
    }.getOrDefault("CICLO (6s)")

    fun readInventory(): Boolean = false
    fun toggleInventory(): Boolean = false
    fun readActionBar(): Boolean = isEnabled()
    fun toggleActionBar(): Boolean { cycle(); return isEnabled() }
}

object ClientAlertPreferences {
    private val file = java.io.File("config/desgraciamon4/client_alerts.json")
    private val gson = com.google.gson.Gson()

    data class Data(
        var biomeAtlasChat: Boolean = false,
        var missionProgressChat: Boolean = true
    )

    var data = Data()

    init {
        load()
    }

    fun load() {
        try {
            if (file.exists()) {
                data = gson.fromJson(file.readText(), Data::class.java) ?: Data()
            }
        } catch (_: Exception) {}
    }

    fun save() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(data))
        } catch (_: Exception) {}
    }

    fun toggleBiomeAtlas(): Boolean {
        data.biomeAtlasChat = !data.biomeAtlasChat
        save()
        return data.biomeAtlasChat
    }

    fun toggleMissionProgress(): Boolean {
        data.missionProgressChat = !data.missionProgressChat
        save()
        return data.missionProgressChat
    }
}
