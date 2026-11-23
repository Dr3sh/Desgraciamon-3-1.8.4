package com.desgraciamon.announcements

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.ProfileTransformType
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.client.render.models.blockbench.repository.VaryingModelRepository
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import org.joml.Quaternionf
import org.lwjgl.glfw.GLFW
import java.io.ByteArrayInputStream
import java.text.NumberFormat
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.sin

private val EXCLAMATION_TEXTURE = ResourceLocation.fromNamespaceAndPath("desgraciamon4_announcements", "textures/gui/exclamation.png")
private val MISSIONS_TOME_TEXTURE = ResourceLocation.fromNamespaceAndPath("desgraciamon4_announcements", "textures/gui/missions_tome.png")

internal object BulletinClientSyncTracker {
    @Volatile
    var snapshotReceivedAtMs: Long = System.currentTimeMillis()
}

private data class ActiveHudNotice(
    val announcement: Announcement,
    val startedAtMillis: Long,
    val durationMillis: Long
)

private object HudNoticeQueue {
    private val queue = ArrayDeque<Announcement>()
    var current: ActiveHudNotice? = null
        private set

    fun enqueue(announcement: Announcement) {
        if (current?.announcement?.id == announcement.id || queue.any { it.id == announcement.id }) return
        val durationMs = ((if (announcement.hudDurationSeconds <= 0) 15 else announcement.hudDurationSeconds).coerceIn(3, 300)) * 1000L
        if (current == null) {
            current = ActiveHudNotice(announcement, System.currentTimeMillis(), durationMs)
        } else {
            queue.addLast(announcement)
        }
    }

    fun dismissCurrent() {
        val next = queue.removeFirstOrNull()
        current = next?.let {
            val durationMs = ((if (it.hudDurationSeconds <= 0) 15 else it.hudDurationSeconds).coerceIn(3, 300)) * 1000L
            ActiveHudNotice(it, System.currentTimeMillis(), durationMs)
        }
    }

    fun tick() {
        val active = current ?: return
        val elapsed = System.currentTimeMillis() - active.startedAtMillis
        if (elapsed >= active.durationMillis) {
            dismissCurrent()
        }
    }

    fun clear() {
        queue.clear()
        current = null
    }
}

internal object ClientRichTextHelper {
    fun processChatEmojis(component: Component): Component {
        val withServer = com.desgraciamon.serverassets.ServerAssetsClientState.processEmojis(component)
        if (!FabricLoader.getInstance().isModLoaded("chat_emojis")) return withServer
        return runCatching {
            val clazz = Class.forName("com.dr3sh.chatemojis.EmojiManager")
            val method = clazz.getMethod("process", Component::class.java)
            (method.invoke(null, withServer) as? Component) ?: withServer
        }.getOrDefault(withServer)
    }

    fun format(text: String, defaultHex: String = "#FFFFFF", boldDefault: Boolean = false): Component {
        val cleanHex = normalizeRichHex(defaultHex, "#FFFFFF")
        val wrapped = if (boldDefault) {
            "<color=$cleanHex><bold>$text</bold></color>"
        } else {
            "<color=$cleanHex>$text</color>"
        }
        return processChatEmojis(RichText.parse(wrapped, emptyMap()))
    }

    private fun normalizeRichHex(raw: String, fallback: String): String {
        val clean = raw.trim().removePrefix("#")
        return when {
            clean.matches(HEX_3_OR_6_REGEX) -> "#$clean"
            clean.matches(HEX_8_REGEX) -> "#${clean.substring(2)}"
            else -> fallback
        }
    }
}

private val HEX_3_OR_6_REGEX = Regex("^(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")
private val HEX_8_REGEX = Regex("^[0-9a-fA-F]{8}$")
private val HEX_COLOR_REGEX = Regex("^(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

internal fun parseThemeColor(hex: String, fallback: Int): Int {
    val clean = hex.trim().removePrefix("#")
    if (!HEX_COLOR_REGEX.matches(clean)) return fallback
    return when (clean.length) {
        6 -> (0xFF shl 24) or (clean.toInt(16) and 0xFFFFFF)
        8 -> clean.toLong(16).toInt()
        3 -> {
            val r = clean[0].digitToInt(16) * 17
            val g = clean[1].digitToInt(16) * 17
            val b = clean[2].digitToInt(16) * 17
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        else -> fallback
    }
}

class AnnouncementsClient : ClientModInitializer {
    private lateinit var openKey: KeyMapping
    private lateinit var dismissKey: KeyMapping
    private var mouseWasDown = false

    override fun onInitializeClient() {
        TextCatalog.load(mapOf(
            "tooltip.open" to "Abrir anuncios y misiones",
            "screen.title" to "Bandeja de entrada",
            "screen.back" to "< Volver",
            "screen.inbox" to "Centro de Anuncios y Misiones",
            "tab.announcements" to "Anuncios",
            "tab.announcements.selected" to "Anuncios",
            "tab.missions" to "Misiones",
            "tab.missions.selected" to "Misiones",
            "screen.scroll" to "Usa la rueda del ratón para desplazarte",
            "species.any" to "cualquier Pokémon",
            "mission.completed" to "Objetivo completado",
            "mission.progress" to "Progreso: {progress} / {amount}",
            "mission.claim" to "RECLAMAR RECOMPENSA",
            "mission.claimed" to "RECOMPENSA RECLAMADA",
            "mission.repeatable" to "Misión repetible",
            "mission.daily" to "Misión diaria",
            "mission.permanent" to "Misión permanente",
            "mission.claim_short" to " [Reclamar]",
            "mission.progress_short" to " ({progress}/{amount})",
            "reward.none" to "Recompensa: ninguna",
            "reward.label" to "Recompensas de la misión:",
            "reward.pokemon" to "Pokémon: {species}",
            "reward.money" to "CobbleDollars: ${'$'}{amount}",
            "chat.announcement.header" to "<color=#FF5555>[Anuncio]</color> {title}",
            "hud.dismiss_hint" to "<color=#8B949E>[Presiona {key}] para ocultar aviso</color>"
        ))

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is InventoryScreen) {
                Screens.getButtons(screen).removeIf { widget ->
                    val name = widget.javaClass.simpleName
                    val isRecipeButton = (widget.width == 20 && widget.height == 18) ||
                        name.contains("Recipe", ignoreCase = true) ||
                        name.contains("ImageButton", ignoreCase = true)
                    if (isRecipeButton) {
                        widget.visible = false
                        widget.active = false
                        widget.setPosition(-9999, -9999)
                    }
                    isRecipeButton
                }
                (screen.children() as? MutableList<*>)?.removeIf { widget ->
                    if (widget != null) {
                        val name = widget.javaClass.simpleName
                        name.contains("Recipe", ignoreCase = true) || name.contains("ImageButton", ignoreCase = true)
                    } else false
                }

                val leftPos = (screen.width - 176) / 2
                val topPos = (screen.height - 166) / 2
                val buttonX = leftPos + 118
                val buttonY = topPos + 62
                val buttonSize = 18
                val announcementsBtn = object : Button(
                    buttonX,
                    buttonY,
                    buttonSize,
                    buttonSize,
                    Component.empty(),
                    { open(Minecraft.getInstance()) },
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
                        val levitateY = getLevitateOffsetY()

                        val iconX = x + (width - 16) / 2
                        val iconY = y + (height - 16) / 2

                        val pose = graphics.pose()
                        pose.pushPose()
                        pose.translate(centerX, centerY + levitateY, 0.0f)
                        pose.scale(scale, scale, 1.0f)
                        pose.translate(-centerX, -centerY, 0.0f)

                        RenderSystem.enableBlend()
                        RenderSystem.setShaderColor(brightness, brightness, brightness, 1.0f)
                        graphics.blit(MISSIONS_TOME_TEXTURE, iconX, iconY, 16, 16, 0f, 0f, 16, 16, 16, 16)
                        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)

                        pose.popPose()

                        if (hasUnreadContent()) {
                            val bounce = getLevitateOffsetY()
                            val exX = (centerX + 1).toInt()
                            val exY = (centerY - 14 + bounce).toInt()
                            RenderSystem.enableBlend()
                            graphics.pose().pushPose()
                            graphics.pose().translate(0f, 0f, 1f)
                            graphics.blit(EXCLAMATION_TEXTURE, exX, exY, 12, 12, 0f, 0f, 64, 64, 64, 64)
                            graphics.pose().popPose()
                        }
                    }
                }

                Screens.getButtons(screen).add(announcementsBtn)
                ScreenEvents.afterRender(screen).register { _, graphics, mouseX, mouseY, _ ->
                    if (announcementsBtn.visible &&
                        mouseX >= announcementsBtn.x && mouseX <= announcementsBtn.x + announcementsBtn.width &&
                        mouseY >= announcementsBtn.y && mouseY <= announcementsBtn.y + announcementsBtn.height
                    ) {
                        val tooltip = listOf(
                            RichText.parse("<prism=#7DD3FC,#38BDF8,#0EA5E9,#BAE6FD>Tablón de Anuncios y Misiones</prism>", emptyMap()),
                            Component.literal("§7Revisa avisos y misiones del servidor")
                        )
                        graphics.renderComponentTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY)
                    }
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(OpenAdminScreenPayload.TYPE) { payload, context ->
            context.client().execute {
                when (payload.initialTab) {
                    "open_inbox" -> Minecraft.getInstance().setScreen(AnnouncementsScreen(true))
                    "open_missions" -> Minecraft.getInstance().setScreen(AnnouncementsScreen(true))
                    else -> Minecraft.getInstance().setScreen(AnnouncementsAdminScreen(payload.initialTab))
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(BulletinSyncPayload.TYPE) { payload, context ->
            val next = BulletinJson.gson.fromJson(payload.json, ClientBulletinSnapshot::class.java)
            context.client().execute {
                val previous = BulletinClientData.snapshot
                BulletinClientData.snapshot = next
                BulletinClientSyncTracker.snapshotReceivedAtMs = System.currentTimeMillis()
                if (next.newAnnouncementCount + next.newMissionCount > 0 &&
                    (next.newAnnouncementCount != previous.newAnnouncementCount || next.newMissionCount != previous.newMissionCount)) {
                    Minecraft.getInstance().player?.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.45F, 1F)
                }
                for (newId in next.newAnnouncementIds.orEmpty()) {
                    val ann = next.document.announcements.firstOrNull { it.id == newId } ?: continue
                    if (ann.showInHud) {
                        HudNoticeQueue.enqueue(ann)
                    }
                }
            }
        }

        openKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping("key.desgraciamon4_announcements.open", GLFW.GLFW_KEY_U, "key.category.desgraciamon3")
        )
        dismissKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping("key.desgraciamon4_announcements.dismiss", GLFW.GLFW_KEY_O, "key.category.desgraciamon3")
        )

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            BulletinClientData.snapshot = ClientBulletinSnapshot(BulletinDocument(), PlayerBulletinState())
            HudNoticeQueue.clear()
        }

        HudRenderCallback.EVENT.register(::renderHud)
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            HudNoticeQueue.tick()
            while (openKey.consumeClick()) open(client)
            while (dismissKey.consumeClick()) {
                if (HudNoticeQueue.current != null) {
                    HudNoticeQueue.dismissCurrent()
                    client.player?.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.35F, 1.2F)
                }
            }
            val down = GLFW.glfwGetMouseButton(client.window.window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
            if (down && !mouseWasDown && client.screen == null && insideIcon(client)) open(client)
            mouseWasDown = down
        }
    }

    private fun open(client: Minecraft) {
        if (client.player == null || client.connection == null) return
        ClientPlayNetworking.send(BulletinActionPayload("sync", ""))
        client.setScreen(AnnouncementsScreen(true))
    }

    private fun getHudPokeballPos(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val hotbarRight = screenWidth / 2 + 91
        val moneyLeft = screenWidth - 85
        val x = if (moneyLeft > hotbarRight + 26) {
            (hotbarRight + moneyLeft) / 2 - 11
        } else {
            hotbarRight + 8
        }
        val y = screenHeight - 24
        return Pair(x, y)
    }

    private fun insideIcon(client: Minecraft): Boolean {
        val mouseX = (client.mouseHandler.xpos() * client.window.guiScaledWidth / client.window.width).toInt()
        val mouseY = (client.mouseHandler.ypos() * client.window.guiScaledHeight / client.window.height).toInt()
        val (x, y) = getHudPokeballPos(client.window.guiScaledWidth, client.window.guiScaledHeight)
        return mouseX >= x - 2 && mouseX <= x + 24 && mouseY >= y - 2 && mouseY <= y + 24
    }

    private fun getLevitateOffsetY(): Float {
        if (!hasUnreadContent()) return 0f
        return (sin(System.currentTimeMillis() / 460.0) * 2.4).toFloat()
    }

    private fun hasUnreadContent(): Boolean {
        val snapshot = BulletinClientData.snapshot
        if (snapshot.document.announcements.any { it.id !in snapshot.state.readIds }) return true
        if (snapshot.document.missions.any {
            !it.isExpired() && (it.id !in snapshot.state.readMissionIds ||
                (it.id in snapshot.state.completedMissions && it.id !in snapshot.state.claimedMissions))
        }) return true
        val cycle = snapshot.dailyCycle
        if (!cycle.paused) {
            if (cycle.slots.any { slot ->
                slot.unlocked && slot.mission != null &&
                    (slot.mission.id !in snapshot.state.readMissionIds || (slot.completed && !slot.claimed))
            }) return true
            if (cycle.bonus.completedCount >= cycle.bonus.totalSlots && !cycle.bonus.claimed) return true
        }
        return false
    }

    private fun renderHud(graphics: GuiGraphics, tickCounter: net.minecraft.client.DeltaTracker) {
        val client = Minecraft.getInstance()
        if (client.player == null || client.options.hideGui) return

        renderActiveHudNotice(graphics, client)

        if (client.screen != null) return
        val (x, y) = getHudPokeballPos(client.window.guiScaledWidth, client.window.guiScaledHeight)

        val cx = x + 10.8f
        val cy = y + 10.8f
        val levitateY = getLevitateOffsetY()

        graphics.pose().pushPose()
        graphics.pose().translate(cx, cy + levitateY, 0f)
        graphics.pose().scale(1.35f, 1.35f, 1f)
        graphics.pose().translate(-8f, -8f, 0f)
        RenderSystem.enableBlend()
        graphics.blit(MISSIONS_TOME_TEXTURE, 0, 0, 16, 16, 0f, 0f, 16, 16, 16, 16)
        graphics.pose().popPose()

        if (hasUnreadContent()) {
            val exX = x + 14
            val exY = (y - 5 + levitateY).toInt()
            RenderSystem.enableBlend()
            graphics.pose().pushPose()
            graphics.pose().translate(0f, 0f, 1f)
            graphics.blit(EXCLAMATION_TEXTURE, exX, exY, 14, 14, 0f, 0f, 64, 64, 64, 64)
            graphics.pose().popPose()
        }
    }

    private fun renderActiveHudNotice(graphics: GuiGraphics, client: Minecraft) {
        val active = HudNoticeQueue.current ?: return
        val now = System.currentTimeMillis()
        val elapsed = (now - active.startedAtMillis).coerceAtLeast(0L)
        if (elapsed >= active.durationMillis) return

        val remainingFraction = (1.0F - (elapsed.toFloat() / active.durationMillis.toFloat())).coerceIn(0.0F, 1.0F)
        val font = client.font
        val ann = active.announcement
        val boxX = 8
        val boxY = 8
        val maxBoxWidth = minOf(255, client.window.guiScaledWidth - 20)
        val innerWidth = maxBoxWidth - 16

        val accentColor = parseThemeColor(ann.color, 0xFF38BDF8.toInt())

        val titleComp = ClientRichTextHelper.format(ann.title, ann.color.ifBlank { "#38BDF8" }, boldDefault = true)
        val titleLines = font.split(titleComp, innerWidth - 12)

        val bodyComp = ClientRichTextHelper.format(ann.body, ann.bodyColor.ifBlank { "#E6EDF3" }, boldDefault = false)
        val bodyLines = if (ann.body.isNotBlank()) font.split(bodyComp, innerWidth).take(8) else emptyList()

        val boxHeight = 14 + (titleLines.size * (font.lineHeight + 1)) +
            (if (bodyLines.isNotEmpty()) font.lineHeight + bodyLines.size * (font.lineHeight + 1) else 0) + 6

        graphics.fill(boxX - 1, boxY - 1, boxX + maxBoxWidth + 1, boxY + boxHeight + 1, 0xFF2D333B.toInt())
        graphics.fill(boxX, boxY, boxX + maxBoxWidth, boxY + boxHeight, 0xE60E1117.toInt())
        graphics.fill(boxX, boxY, boxX + 2, boxY + boxHeight, accentColor)

        var cursorY = boxY + 7
        graphics.fill(boxX + 7, cursorY + 1, boxX + 13, cursorY + 7, accentColor)
        graphics.fill(boxX + 8, cursorY + 2, boxX + 12, cursorY + 6, 0xFF161B22.toInt())

        titleLines.forEachIndexed { idx, line ->
            val indent = if (idx == 0) 17 else 7
            graphics.drawString(font, line, boxX + indent, cursorY, accentColor, true)
            cursorY += font.lineHeight + 1
        }

        if (bodyLines.isNotEmpty()) {
            cursorY += font.lineHeight
            for (line in bodyLines) {
                graphics.drawString(font, line, boxX + 7, cursorY, 0xFFE6EDF3.toInt(), true)
                cursorY += font.lineHeight + 1
            }
        }

        val barTop = boxY + boxHeight - 2
        val barBottom = boxY + boxHeight
        graphics.fill(boxX, barTop, boxX + maxBoxWidth, barBottom, 0xFF161B22.toInt())
        val fillWidth = (maxBoxWidth * remainingFraction).toInt().coerceIn(0, maxBoxWidth)
        if (fillWidth > 0) {
            graphics.fill(boxX, barTop, boxX + fillWidth, barBottom, accentColor)
        }

        val keyName = dismissKey.translatedKeyMessage.string.uppercase()
        val hintComp = TextCatalog.component("hud.dismiss_hint", "key" to keyName)
        graphics.drawString(font, hintComp, boxX + 4, boxY + boxHeight + 5, 0xFF8B949E.toInt(), true)
    }
}

class AnnouncementsScreen(private var showingMissions: Boolean = true) : Screen(TextCatalog.component("screen.title")) {
    companion object {
        private const val BASE_W = 440f
        private const val BASE_H = 310f
        private var hideFinishedMissions: Boolean = true
    }

    private data class ClickRegion(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val requiresViewportBounds: Boolean = true,
        val onClick: () -> Unit
    )

    private data class ResolvedTheme(
        val bgBase: Int,
        val bgCard: Int,
        val border: Int,
        val dailyAccent: Int,
        val textColor: Int,
        val mutedText: Int,
        val missionAccent: Int,
        val guildAccent: Int,
        val permanentAccent: Int,
        val completedAccent: Int
    )

    private var uiScale = 1.0f
    private var renderX = 0f
    private var renderY = 0f

    private var selectedId: String? = null
    private var announcementsScroll = 0
    private var missionsScroll = 0
    private var missionsContentHeight = 0
    private var detailScroll = 0
    private var detailContentHeight = 0
    private val openedAtNanos = System.nanoTime()
    private val previewModelStates = mutableMapOf<String, FloatingState>()
    private val clickRegions = mutableListOf<ClickRegion>()
    private var hoveredTooltipStack: ItemStack? = null

    override fun init() {
        super.init()
        if (showingMissions) {
            markAllMissionsRead()
        } else {
            markAllAnnouncementsRead()
        }
    }

    private fun resolveTheme(snapshot: ClientBulletinSnapshot): ResolvedTheme {
        val cfg = snapshot.uiTheme
        return ResolvedTheme(
            bgBase = parseThemeColor(cfg.bgBaseHex, 0xFF0E1117.toInt()),
            bgCard = parseThemeColor(cfg.bgCardHex, 0xFF161B22.toInt()),
            border = parseThemeColor(cfg.borderHex, 0xFF2D333B.toInt()),
            dailyAccent = parseThemeColor(cfg.dailyAccentHex, 0xFF38BDF8.toInt()),
            textColor = parseThemeColor(cfg.textColorHex, 0xFFE6EDF3.toInt()),
            mutedText = parseThemeColor(cfg.mutedTextColorHex, 0xFF8B949E.toInt()),
            missionAccent = parseThemeColor(cfg.missionAccentHex, 0xFF38BDF8.toInt()),
            guildAccent = parseThemeColor(cfg.guildAccentHex, 0xFFA78BFA.toInt()),
            permanentAccent = parseThemeColor(cfg.permanentAccentHex, 0xFF34D399.toInt()),
            completedAccent = parseThemeColor(cfg.completedAccentHex, 0xFF6E7681.toInt())
        )
    }

    private fun markAllAnnouncementsRead() {
        val snapshot = BulletinClientData.snapshot
        val unread = snapshot.document.announcements.filter { it.id !in snapshot.state.readIds }
        for (ann in unread) {
            snapshot.state.readIds.add(ann.id)
            ClientPlayNetworking.send(BulletinActionPayload("read", ann.id))
        }
    }

    private fun markAllMissionsRead() {
        val snapshot = BulletinClientData.snapshot
        val unreadManual = snapshot.document.missions.filter { it.id !in snapshot.state.readMissionIds }
        val unreadDaily = if (!snapshot.dailyCycle.paused) {
            snapshot.dailyCycle.slots.mapNotNull { slot ->
                if (slot.unlocked) slot.mission?.takeIf { it.id !in snapshot.state.readMissionIds } else null
            }
        } else emptyList()
        for (mis in unreadManual + unreadDaily) {
            snapshot.state.readMissionIds.add(mis.id)
            ClientPlayNetworking.send(BulletinActionPayload("missionRead", mis.id))
        }
    }

    private fun openingProgress(): Float {
        return Math.min(1.0F, (System.nanoTime() - openedAtNanos) / 280_000_000.0F)
    }

    override fun renderBlurredBackground(delta: Float) {}
    override fun renderMenuBackground(context: GuiGraphics) {}

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val margin = 8f
        uiScale = minOf(1.0f, (width - margin * 2) / BASE_W, (height - margin * 2) / BASE_H)
        renderX = (width - BASE_W * uiScale) / 2f
        renderY = (height - BASE_H * uiScale) / 2f

        val localMouseX = ((mouseX - renderX) / uiScale).toInt()
        val localMouseY = ((mouseY - renderY) / uiScale).toInt()

        val snapshot = BulletinClientData.snapshot
        val theme = resolveTheme(snapshot)
        clickRegions.clear()
        hoveredTooltipStack = null

        graphics.fill(0, 0, width, height, 0xB806080C.toInt())
        val panelWidth = BASE_W.toInt()
        val panelHeight = BASE_H.toInt()
        val left = 0
        val top = 0

        val matrices = graphics.pose()
        matrices.pushPose()
        matrices.translate(renderX, renderY, 0.0f)
        matrices.scale(uiScale, uiScale, 1.0f)

        val progress = openingProgress()
        if (progress < 1.0f) {
            val ease = 1f - (1f - progress).let { it * it * it }
            val scale = 0.08f + 0.92f * ease
            val cx = BASE_W / 2f
            val cy = BASE_H / 2f
            matrices.translate(cx, cy, 0f)
            matrices.scale(scale, scale, 1f)
            matrices.translate(-cx, -cy, 0f)
        }

        // Outer frame & main slate background
        graphics.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, theme.border)
        graphics.fill(left, top, left + panelWidth, top + panelHeight, theme.bgBase)

        // Top header bar
        graphics.fill(left, top, left + panelWidth, top + 26, theme.bgCard)
        val topAccent = if (showingMissions) theme.dailyAccent else 0xFFF5C842.toInt()
        graphics.fill(left, top + 26, left + panelWidth, top + 27, topAccent)

        val viewingDetails = selectedId != null
        if (viewingDetails) {
            val backHovered = localMouseX in left + 8..left + 84 && localMouseY in top + 4..top + 22
            graphics.fill(left + 8, top + 4, left + 84, top + 22, if (backHovered) 0xFF212832.toInt() else theme.bgBase)
            graphics.renderOutline(left + 8, top + 4, 76, 18, if (backHovered) topAccent else theme.border)
            graphics.drawCenteredString(font, TextCatalog.component("screen.back"), left + 46, top + 9, theme.textColor)
        } else {
            val titleComp = Component.literal(TextCatalog.string("screen.inbox")).withStyle { it.withBold(true) }
            graphics.drawString(font, titleComp, left + 12, top + 9, theme.textColor, false)
        }

        val unreadAnn = snapshot.document.announcements.count { it.id !in snapshot.state.readIds }
        val unreadManualMis = snapshot.document.missions.count {
            !it.isExpired() && (it.id !in snapshot.state.readMissionIds ||
                (it.id in snapshot.state.completedMissions && it.id !in snapshot.state.claimedMissions))
        }
        val unreadDailyMis = if (!snapshot.dailyCycle.paused) {
            snapshot.dailyCycle.slots.count { slot ->
                slot.unlocked && slot.mission != null &&
                    (slot.mission.id !in snapshot.state.readMissionIds || (slot.completed && !slot.claimed))
            }
        } else 0
        val unreadMis = unreadManualMis + unreadDailyMis

        // 2 Main Tabs: Misiones & Anuncios
        drawTabButton(
            graphics, left + 10, top + 31, 128, 20,
            selected = showingMissions,
            hovered = localMouseX in left + 10..left + 138 && localMouseY in top + 31..top + 51,
            label = TextCatalog.component(if (showingMissions) "tab.missions.selected" else "tab.missions"),
            badgeCount = if (showingMissions) 0 else unreadMis,
            accentColor = theme.dailyAccent,
            theme = theme
        )
        drawTabButton(
            graphics, left + 144, top + 31, 128, 20,
            selected = !showingMissions,
            hovered = localMouseX in left + 144..left + 272 && localMouseY in top + 31..top + 51,
            label = TextCatalog.component(if (!showingMissions) "tab.announcements.selected" else "tab.announcements"),
            badgeCount = if (!showingMissions) 0 else unreadAnn,
            accentColor = 0xFFF5C842.toInt(),
            theme = theme
        )

        if (snapshot.state.adminMode) {
            drawTabButton(
                graphics, left + panelWidth - 78, top + 4, 70, 18,
                selected = false,
                hovered = localMouseX in left + panelWidth - 78..left + panelWidth - 8 && localMouseY in top + 4..top + 22,
                label = Component.literal("Admin"),
                badgeCount = 0,
                accentColor = theme.dailyAccent,
                theme = theme
            )
        }

        if (showingMissions && selectedId == null) {
            val cbX = left + 280
            val cbY = top + 35
            val cbBoxSize = 12
            val cbLabel = "Ocultar terminadas"
            val cbRight = cbX + cbBoxSize + 5 + font.width(cbLabel)
            val cbHovered = localMouseX in (cbX - 2)..(cbRight + 2) && localMouseY in (cbY - 2)..(cbY + cbBoxSize + 2)
            graphics.fill(cbX, cbY, cbX + cbBoxSize, cbY + cbBoxSize, if (cbHovered) 0xFF1D232D.toInt() else 0xFF12161D.toInt())
            graphics.renderOutline(cbX, cbY, cbBoxSize, cbBoxSize, if (hideFinishedMissions || cbHovered) theme.dailyAccent else theme.border)
            if (hideFinishedMissions) {
                graphics.fill(cbX + 2, cbY + 2, cbX + cbBoxSize - 2, cbY + cbBoxSize - 2, theme.dailyAccent)
            }
            graphics.drawString(font, cbLabel, cbX + cbBoxSize + 5, cbY + 2, if (cbHovered || hideFinishedMissions) theme.textColor else theme.mutedText, false)
            clickRegions.add(ClickRegion(cbX - 2, cbY - 2, cbRight + 2, cbY + cbBoxSize + 2, requiresViewportBounds = false) {
                hideFinishedMissions = !hideFinishedMissions
                missionsScroll = 0
            })
        }

        // Inner panel container
        val innerTop = top + 55
        val innerBottom = top + panelHeight - 15
        graphics.fill(left + 10, innerTop, left + panelWidth - 10, innerBottom, 0xFF0B0E14.toInt())
        graphics.renderOutline(left + 10, innerTop, panelWidth - 20, innerBottom - innerTop, theme.border)

        if (showingMissions) {
            renderMissions(graphics, localMouseX, localMouseY, left, top, panelWidth, panelHeight, snapshot, theme, partialTick)
        } else {
            renderAnnouncements(graphics, localMouseX, localMouseY, left, top, panelWidth, panelHeight, snapshot, theme)
        }

        if (selectedId == null) {
            graphics.drawString(font, TextCatalog.component("screen.scroll"), left + 12, top + panelHeight - 12, theme.mutedText, false)
        }

        children().filterIsInstance<net.minecraft.client.gui.components.Renderable>().forEach {
            it.render(graphics, localMouseX, localMouseY, partialTick)
        }
        matrices.popPose()

        hoveredTooltipStack?.let { stack ->
            graphics.renderTooltip(font, stack, mouseX, mouseY)
        }
    }

    private fun drawTabButton(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        selected: Boolean,
        hovered: Boolean,
        label: Component,
        badgeCount: Int,
        accentColor: Int,
        theme: ResolvedTheme
    ) {
        val bg = when {
            selected -> theme.bgCard
            hovered -> 0xFF1D232D.toInt()
            else -> 0xFF12161D.toInt()
        }
        graphics.fill(x, y, x + w, y + h, bg)
        graphics.renderOutline(x, y, w, h, if (selected) accentColor else theme.border)
        if (selected) {
            graphics.fill(x, y + h - 2, x + w, y + h, accentColor)
        }
        val styledLabel = if (selected) label.copy().withStyle { it.withBold(true) } else label
        graphics.drawString(font, styledLabel, x + 10, y + 6, if (selected) accentColor else theme.mutedText, false)
        if (badgeCount > 0) {
            val badgeText = badgeCount.toString()
            val bw = maxOf(14, font.width(badgeText) + 6)
            graphics.fill(x + w - bw - 5, y + 4, x + w - 5, y + 16, 0xFFDC2626.toInt())
            graphics.drawCenteredString(font, badgeText, x + w - 5 - bw / 2, y + 6, 0xFFFFFFFF.toInt())
        }
    }

    private fun renderAnnouncements(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        left: Int,
        top: Int,
        panelWidth: Int,
        panelHeight: Int,
        snapshot: ClientBulletinSnapshot,
        theme: ResolvedTheme
    ) {
        val selected = selectedId?.let { id -> snapshot.document.announcements.firstOrNull { it.id == id } }
        if (selected != null) {
            val titleComp = ClientRichTextHelper.format(selected.title, selected.color.ifBlank { "#F5C842" }, boldDefault = true)
            drawScaledComponent(graphics, titleComp, left + 18, top + 64, 1.2f, parseThemeColor(selected.color, 0xFFF5C842.toInt()))

            var badgeX = left + panelWidth - 18
            if (selected.showInHud) {
                val tag = "HUD (${if (selected.hudDurationSeconds <= 0) 15 else selected.hudDurationSeconds}s)"
                val tw = font.width(tag) + 8
                badgeX -= tw
                graphics.fill(badgeX, top + 62, badgeX + tw, top + 74, theme.bgCard)
                graphics.renderOutline(badgeX, top + 62, tw, 12, theme.border)
                graphics.drawString(font, tag, badgeX + 4, top + 64, theme.permanentAccent, false)
                badgeX -= 4
            }
            if (selected.showInChat) {
                val tag = "CHAT"
                val tw = font.width(tag) + 8
                badgeX -= tw
                graphics.fill(badgeX, top + 62, badgeX + tw, top + 74, theme.bgCard)
                graphics.renderOutline(badgeX, top + 62, tw, 12, theme.border)
                graphics.drawString(font, tag, badgeX + 4, top + 64, 0xFFF5C842.toInt(), false)
            }

            graphics.fill(left + 18, top + 81, left + panelWidth - 18, top + 82, theme.border)

            val contentTop = top + 87
            val contentBottom = top + panelHeight - 20
            enableVirtualScissor(graphics, left + 12, contentTop, left + panelWidth - 12, contentBottom)
            var cursorY = contentTop - detailScroll

            val bodyComp = ClientRichTextHelper.format(selected.body, selected.bodyColor.ifBlank { "#E6EDF3" }, boldDefault = false)
            font.split(bodyComp, panelWidth - 38).forEach { line ->
                graphics.drawString(font, line, left + 18, cursorY, parseThemeColor(selected.bodyColor, theme.textColor), false)
                cursorY += font.lineHeight + 3
            }

            BulletinImages.texture(selected.imageUrl)?.let { image ->
                cursorY += 8
                val maxImgW = minOf(190, panelWidth - 40)
                val scale = minOf(maxImgW.toFloat() / image.width.toFloat(), 110.0F / image.height.toFloat())
                val drawW = (image.width * scale).toInt().coerceAtLeast(16)
                val drawH = (image.height * scale).toInt().coerceAtLeast(16)
                graphics.fill(left + 17, cursorY - 1, left + 19 + drawW, cursorY + drawH + 1, theme.border)
                graphics.blit(image.id, left + 18, cursorY, drawW, drawH, 0F, 0F, image.width, image.height, image.width, image.height)
                cursorY += drawH + 6
            }

            detailContentHeight = (cursorY - (contentTop - detailScroll)).coerceAtLeast(0)
            graphics.disableScissor()
            return
        }

        if (snapshot.document.announcements.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal("No hay anuncios publicados todavía."), left + panelWidth / 2, top + 135, theme.mutedText)
            return
        }

        val listTop = top + 62
        val listBottom = top + panelHeight - 20
        val rowH = 27
        val maxRows = ((listBottom - listTop) / rowH).coerceAtLeast(1)
        val maxScroll = (snapshot.document.announcements.size - maxRows).coerceAtLeast(0)
        announcementsScroll = announcementsScroll.coerceIn(0, maxScroll)

        snapshot.document.announcements.drop(announcementsScroll).take(maxRows).forEachIndexed { index, announcement ->
            val unread = announcement.id !in snapshot.state.readIds
            val accent = if (unread) parseThemeColor(announcement.color, 0xFFF5C842.toInt()) else theme.completedAccent
            val titleComp = ClientRichTextHelper.format(
                announcement.title,
                if (unread) announcement.color.ifBlank { "#F5C842" } else "#8B949E",
                boldDefault = unread
            )
            val rowY = listTop + index * rowH
            val rowLeft = left + 16
            val rowRight = left + panelWidth - 16
            val hovered = mouseX in rowLeft..rowRight && mouseY in rowY..rowY + 23
            graphics.fill(rowLeft, rowY, rowRight, rowY + 23, if (hovered) 0xFF1D242E.toInt() else theme.bgCard)
            graphics.renderOutline(rowLeft, rowY, rowRight - rowLeft, 23, if (hovered) accent else theme.border)
            graphics.fill(rowLeft, rowY, rowLeft + 3, rowY + 23, accent)

            val rightLabel = if (unread) Component.literal("NUEVO").withStyle { it.withColor(0xFFF5C842.toInt()).withBold(true) } else null
            val rightW = rightLabel?.let { font.width(it) + 12 } ?: 0
            val maxTitleW = (rowRight - rowLeft - 20 - rightW).coerceAtLeast(40)
            val clippedTitle = font.split(titleComp, maxTitleW).firstOrNull()
            if (clippedTitle != null) {
                graphics.drawString(font, clippedTitle, rowLeft + 10, rowY + 7, theme.textColor, false)
            }
            if (rightLabel != null) {
                graphics.drawString(font, rightLabel, rowRight - font.width(rightLabel) - 8, rowY + 7, 0xFFF5C842.toInt(), false)
            }

            clickRegions.add(ClickRegion(rowLeft, rowY, rowRight, rowY + 23, requiresViewportBounds = false) {
                selectedId = announcement.id
                detailScroll = 0
                ClientPlayNetworking.send(BulletinActionPayload("read", announcement.id))
            })
        }
    }

    private fun findMissionById(snapshot: ClientBulletinSnapshot, id: String): Mission? {
        snapshot.document.missions.firstOrNull { it.id == id }?.let { return it }
        if (!snapshot.dailyCycle.paused) {
            snapshot.dailyCycle.slots.forEach { slot ->
                if (slot.unlocked && slot.mission?.id == id) return slot.mission
            }
        }
        return null
    }

    private fun renderMissions(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        left: Int,
        top: Int,
        panelWidth: Int,
        panelHeight: Int,
        snapshot: ClientBulletinSnapshot,
        theme: ResolvedTheme,
        partialTick: Float
    ) {
        val selected = selectedId?.let { id -> findMissionById(snapshot, id) }
        if (selected != null) {
            renderMissionDetail(graphics, mouseX, mouseY, left, top, panelWidth, panelHeight, snapshot, theme, selected, partialTick)
            return
        }

        renderMissionsList(graphics, mouseX, mouseY, left, top, panelWidth, panelHeight, snapshot, theme)
    }

    private fun renderMissionsList(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        left: Int,
        top: Int,
        panelWidth: Int,
        panelHeight: Int,
        snapshot: ClientBulletinSnapshot,
        theme: ResolvedTheme
    ) {
        val viewLeft = left + 12
        val viewTop = top + 57
        val viewRight = left + panelWidth - 12
        val viewBottom = top + panelHeight - 17
        val viewHeight = (viewBottom - viewTop).coerceAtLeast(1)
        val cardLeft = viewLeft + 4
        val cardW = (viewRight - viewLeft - 14).coerceAtLeast(120)
        val cardRight = cardLeft + cardW
        val mouseInsideView = mouseX in viewLeft..viewRight && mouseY in viewTop..viewBottom

        val elapsedSec = ((System.currentTimeMillis() - BulletinClientSyncTracker.snapshotReceivedAtMs) / 1000L).coerceAtLeast(0L)
        val cycle = snapshot.dailyCycle
        val isPaused = cycle.paused
        val liveResetSec = if (isPaused) 0L else (cycle.resetRemainingSeconds - elapsedSec).coerceAtLeast(0L)
        val memberCount = snapshot.state.guildMemberCount.coerceAtLeast(1)

        enableVirtualScissor(graphics, viewLeft, viewTop, viewRight, viewBottom)
        var cursorY = viewTop + 4 - missionsScroll

        // ==================== SECTION 1: MISIONES DIARIAS ====================
        val headerH = 62
        graphics.fill(cardLeft, cursorY, cardRight, cursorY + headerH, theme.bgCard)
        graphics.renderOutline(cardLeft, cursorY, cardW, headerH, theme.dailyAccent)
        graphics.fill(cardLeft, cursorY, cardLeft + 3, cursorY + headerH, theme.dailyAccent)

        drawScaledString(graphics, "MISIONES DIARIAS", cardLeft + 10, cursorY + 6, 1.1f, theme.dailyAccent, bold = true)

        val resetLabel = if (isPaused) {
            Component.literal("PAUSADAS").withStyle { it.withColor(0xFFF59E0B.toInt()).withBold(true) }
        } else {
            Component.literal("Reinicio en: (${formatHms(liveResetSec)})").withStyle { it.withColor(theme.textColor).withBold(true) }
        }
        val resetW = font.width(resetLabel)
        graphics.drawString(font, resetLabel, cardRight - resetW - 8, cursorY + 7, theme.textColor, false)

        graphics.fill(cardLeft + 8, cursorY + 21, cardRight - 8, cursorY + 22, theme.border)

        // Recompensa del Dia (Bonus 3/3) ALWAYS visible in header
        val bonus = cycle.bonus
        val totalSlots = bonus.totalSlots.coerceAtLeast(3)
        val bonusTitle = Component.literal("Recompensa del Dia").withStyle { it.withBold(true) }
        graphics.drawString(font, bonusTitle, cardLeft + 10, cursorY + 26, theme.textColor, false)

        val progBadgeText = "${bonus.completedCount}/$totalSlots"
        val progBadgeX = cardLeft + 10 + font.width(bonusTitle) + 8
        val progBadgeW = font.width(progBadgeText) + 10
        val bonusReady = !isPaused && bonus.completedCount >= totalSlots && !bonus.claimed
        val progBg = if (bonus.claimed) 0xFF14532D.toInt() else if (bonusReady) 0xFF1E3A2F.toInt() else 0xFF1F242C.toInt()
        val progCol = if (bonus.claimed || bonusReady) 0xFF4ADE80.toInt() else theme.dailyAccent
        graphics.fill(progBadgeX, cursorY + 24, progBadgeX + progBadgeW, cursorY + 35, progBg)
        graphics.renderOutline(progBadgeX, cursorY + 24, progBadgeW, 11, progCol)
        graphics.drawCenteredString(font, progBadgeText, progBadgeX + progBadgeW / 2, cursorY + 26, progCol)

        // Bonus rewards row: money + item icons with x(CANTIDAD)
        var rx = cardLeft + 10
        val ry = cursorY + 41
        if (bonus.moneyReward > 0L) {
            val moneyComp = Component.literal("$ ${formatMoney(bonus.moneyReward)}").withStyle { it.withColor(0xFFFBBF24.toInt()).withBold(true) }
            graphics.drawString(font, moneyComp, rx, ry + 3, 0xFFFBBF24.toInt(), false)
            rx += font.width(moneyComp) + 10
        }
        val maxRewardRight = cardRight - 88
        for (reward in bonus.itemRewards) {
            if (rx + 36 > maxRewardRight) break
            val stack = itemStack(reward.itemId)
            if (stack != null && !stack.isEmpty) {
                graphics.renderItem(stack, rx, ry - 2)
                if (mouseInsideView && mouseX in rx..rx + 16 && mouseY in (ry - 2)..(ry + 14)) {
                    hoveredTooltipStack = stack
                }
                rx += 17
            }
            val qtyStr = "x${reward.count}"
            graphics.drawString(font, qtyStr, rx, ry + 3, theme.textColor, false)
            rx += font.width(qtyStr) + 8
        }

        // Bonus Claim / Status button on right
        val btnW = 74
        val btnH = 22
        val btnX = cardRight - btnW - 8
        val btnY = cursorY + 30
        val btnHovered = mouseInsideView && mouseX in btnX..btnX + btnW && mouseY in btnY..btnY + btnH
        when {
            isPaused -> {
                graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0xFF1A1F27.toInt())
                graphics.renderOutline(btnX, btnY, btnW, btnH, theme.border)
                graphics.drawCenteredString(font, "PAUSADAS", btnX + btnW / 2, btnY + 7, 0xFFF59E0B.toInt())
            }
            bonus.claimed -> {
                graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0xFF132A1F.toInt())
                graphics.renderOutline(btnX, btnY, btnW, btnH, 0xFF22C55E.toInt())
                graphics.drawCenteredString(font, "Reclamada", btnX + btnW / 2, btnY + 7, 0xFF4ADE80.toInt())
            }
            bonusReady -> {
                graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, if (btnHovered) 0xFF16A34A.toInt() else 0xFF15803D.toInt())
                graphics.renderOutline(btnX, btnY, btnW, btnH, 0xFF4ADE80.toInt())
                val lbl = Component.literal("Reclamar").withStyle { it.withBold(true) }
                graphics.drawCenteredString(font, lbl, btnX + btnW / 2, btnY + 7, 0xFFFFFFFF.toInt())
                clickRegions.add(ClickRegion(btnX, btnY, btnX + btnW, btnY + btnH) {
                    ClientPlayNetworking.send(BulletinActionPayload("claimDailyBonus", cycle.cycleId))
                })
            }
            else -> {
                graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0xFF12161D.toInt())
                graphics.renderOutline(btnX, btnY, btnW, btnH, theme.border)
                graphics.drawCenteredString(font, "${bonus.completedCount}/$totalSlots", btnX + btnW / 2, btnY + 7, theme.mutedText)
            }
        }
        cursorY += headerH + 6

        // 3 Vertical Daily Slots
        val normalizedSlots = (1..3).map { slotNum ->
            cycle.slots.firstOrNull { it.slotIndex == slotNum }
                ?: DailySlotSnapshot(slotIndex = slotNum, unlocked = false, unlockRemainingSeconds = 0L)
        }
        for (slot in normalizedSlots) {
            val slotMission = slot.mission
            if (isPaused || !slot.unlocked || slotMission == null) {
                // Locked / Paused slot: muted dark gray card showing ONLY centered countdown HH:MM:SS (or PAUSADA)
                val lockedH = 40
                graphics.fill(cardLeft, cursorY, cardRight, cursorY + lockedH, 0xFF14181F.toInt())
                graphics.renderOutline(cardLeft, cursorY, cardW, lockedH, 0xFF252A33.toInt())
                val centerLabel = if (isPaused) {
                    Component.literal("PAUSADA").withStyle { it.withBold(true) }
                } else {
                    val liveSlotRem = (slot.unlockRemainingSeconds - elapsedSec).coerceAtLeast(0L)
                    Component.literal(formatHms(liveSlotRem)).withStyle { it.withBold(true) }
                }
                val labelColor = if (isPaused) 0xFF6E7681.toInt() else 0xFF8B949E.toInt()
                val labelW = (font.width(centerLabel) * 1.15f).toInt()
                drawScaledComponent(
                    graphics,
                    centerLabel,
                    cardLeft + (cardW - labelW) / 2,
                    cursorY + 15,
                    1.15f,
                    labelColor
                )
                cursorY += lockedH + 5
            } else {
                // Unlocked / Active / Completed / Claimed slot stays in place
                val slotH = 60
                val hovered = mouseInsideView && mouseX in cardLeft..cardRight && mouseY in cursorY..cursorY + slotH
                val readyToClaim = slot.completed && !slot.claimed
                val slotAccent = when {
                    readyToClaim -> 0xFFFBBF24.toInt()
                    slot.claimed -> theme.completedAccent
                    else -> theme.dailyAccent
                }
                val cardBg = if (hovered) 0xFF1C232D.toInt() else if (slot.claimed) 0xFF131820.toInt() else theme.bgCard
                val cardOutline = if (hovered) slotAccent else if (readyToClaim) 0xFFFBBF24.toInt() else theme.border

                graphics.fill(cardLeft, cursorY, cardRight, cursorY + slotH, cardBg)
                graphics.renderOutline(cardLeft, cursorY, cardW, slotH, cardOutline)
                graphics.fill(cardLeft, cursorY, cardLeft + 3, cursorY + slotH, slotAccent)

                // Top-right category tag: (CAPTURA), (ITEMS), (COMBATE), (ESPECIAL)
                val catText = "(${slotMission.resolvedCategoryTag()})"
                val catW = font.width(catText) + 8
                val catX = cardRight - catW - 6
                graphics.fill(catX, cursorY + 4, catX + catW, cursorY + 15, 0xFF1F2733.toInt())
                graphics.renderOutline(catX, cursorY + 4, catW, 11, slotAccent)
                graphics.drawString(font, catText, catX + 4, cursorY + 6, slotAccent, false)

                // Title
                val titleMaxW = (catX - (cardLeft + 10) - 6).coerceAtLeast(40)
                val titleComp = ClientRichTextHelper.format(
                    slotMission.title,
                    if (slot.claimed) snapshot.uiTheme.mutedTextColorHex else snapshot.uiTheme.textColorHex,
                    boldDefault = true
                )
                val clippedTitle = font.split(titleComp, titleMaxW).firstOrNull()
                if (clippedTitle != null) {
                    graphics.drawString(font, clippedTitle, cardLeft + 10, cursorY + 6, theme.textColor, false)
                }

                // Short description
                val descText = slotMission.body.ifBlank { "Mision diaria activa" }.replace('\n', ' ')
                val clippedDesc = font.plainSubstrByWidth(descText, cardW - 20)
                graphics.drawString(font, clippedDesc, cardLeft + 10, cursorY + 17, theme.mutedText, false)

                // Simple objective + progress
                val objProgMap = snapshot.state.objectiveProgress[slotMission.id].orEmpty()
                val fallbackProg = snapshot.state.missionProgress[slotMission.id] ?: 0
                val summary = CleanMissionDisplay.simpleMissionSummary(slotMission, objProgMap, fallbackProg, memberCount)
                val summaryCol = when {
                    slot.claimed -> 0xFF4ADE80.toInt()
                    readyToClaim -> 0xFFFBBF24.toInt()
                    else -> theme.dailyAccent
                }
                val clippedSummary = font.plainSubstrByWidth(summary, cardW - 20)
                graphics.drawString(font, Component.literal(clippedSummary).withStyle { it.withBold(true) }, cardLeft + 10, cursorY + 29, summaryCol, false)

                // Bottom row: Rewards (item icons xN + $ money) + Status
                val statusLabel = when {
                    slot.claimed -> "RECLAMADA"
                    readyToClaim -> "LISTA PARA RECLAMAR"
                    else -> "ACTIVA"
                }
                val statusCol = when {
                    slot.claimed -> 0xFF34D399.toInt()
                    readyToClaim -> 0xFFFBBF24.toInt()
                    else -> theme.dailyAccent
                }
                val statusComp = Component.literal(statusLabel).withStyle { it.withBold(readyToClaim || slot.claimed) }
                val statusW = font.width(statusComp)
                graphics.drawString(font, statusComp, cardRight - statusW - 8, cursorY + 45, statusCol, false)

                drawInlineRewardsRow(
                    graphics,
                    slotMission,
                    cardLeft + 10,
                    cursorY + 42,
                    cardRight - statusW - 14,
                    mouseInsideView,
                    mouseX,
                    mouseY,
                    theme
                )

                clickRegions.add(ClickRegion(cardLeft, cursorY, cardRight, cursorY + slotH) {
                    selectedId = slotMission.id
                    detailScroll = 0
                    ClientPlayNetworking.send(BulletinActionPayload("missionRead", slotMission.id))
                })
                cursorY += slotH + 5
            }
        }

        // ==================== SECTION 2: MISIONES PERMANENTES / MANUALES Y DE GREMIO ====================
        cursorY += 6
        val permanentMissions = snapshot.document.missions.filter { mis ->
            mis.dailySlot == 0 && !mis.isExpired() && mis.id !in snapshot.state.claimedMissions
        }
        drawScaledString(
            graphics,
            "MISIONES PERMANENTES Y DE GREMIO (${permanentMissions.size})",
            cardLeft + 2,
            cursorY,
            1.05f,
            theme.permanentAccent,
            bold = true
        )
        cursorY += 12
        graphics.fill(cardLeft, cursorY, cardRight, cursorY + 1, theme.border)
        cursorY += 5

        if (permanentMissions.isEmpty()) {
            graphics.drawString(font, "No hay misiones permanentes o de gremio activas.", cardLeft + 4, cursorY + 2, theme.mutedText, false)
            cursorY += 18
        } else {
            for (mission in permanentMissions) {
                val hasBody = mission.body.isNotBlank()
                val cardH = if (hasBody) 60 else 50
                val hovered = mouseInsideView && mouseX in cardLeft..cardRight && mouseY in cursorY..cursorY + cardH
                val completed = mission.id in snapshot.state.completedMissions
                val baseAccent = when {
                    completed -> 0xFFFBBF24.toInt()
                    mission.isGuildMission -> theme.guildAccent
                    else -> parseThemeColor(mission.color, theme.permanentAccent)
                }

                graphics.fill(cardLeft, cursorY, cardRight, cursorY + cardH, if (hovered) 0xFF1C232D.toInt() else theme.bgCard)
                graphics.renderOutline(cardLeft, cursorY, cardW, cardH, if (hovered) baseAccent else if (completed) 0xFFFBBF24.toInt() else theme.border)
                graphics.fill(cardLeft, cursorY, cardLeft + 3, cursorY + cardH, baseAccent)

                // Top-right tag: (CAPTURA), (ITEMS), (COMBATE), (ESPECIAL)
                val catText = "(${mission.resolvedCategoryTag()})"
                val catW = font.width(catText) + 8
                val catX = cardRight - catW - 6
                graphics.fill(catX, cursorY + 4, catX + catW, cursorY + 15, 0xFF1F2733.toInt())
                graphics.renderOutline(catX, cursorY + 4, catW, 11, baseAccent)
                graphics.drawString(font, catText, catX + 4, cursorY + 6, baseAccent, false)

                val guildPrefix = if (mission.isGuildMission) "[Gremio] " else ""
                val coopSuffix = if (mission.isGuildMission && (mission.targetUsesMemberCount || mission.objectives.any { it.targetUsesMemberCount })) {
                    " [xMiembro]"
                } else ""
                val titleMaxW = (catX - (cardLeft + 10) - 6).coerceAtLeast(40)
                val titleHex = if (mission.isGuildMission) snapshot.uiTheme.guildAccentHex else mission.color.ifBlank { snapshot.uiTheme.permanentAccentHex }
                val titleComp = ClientRichTextHelper.format("$guildPrefix${mission.title}$coopSuffix", titleHex, boldDefault = true)
                val clippedTitle = font.split(titleComp, titleMaxW).firstOrNull()
                if (clippedTitle != null) {
                    graphics.drawString(font, clippedTitle, cardLeft + 10, cursorY + 6, theme.textColor, false)
                }

                var rowY = cursorY + 18
                if (hasBody) {
                    val clippedBody = font.plainSubstrByWidth(mission.body.replace('\n', ' '), cardW - 20)
                    graphics.drawString(font, clippedBody, cardLeft + 10, rowY, theme.mutedText, false)
                    rowY += 11
                }

                val objProgMap = snapshot.state.objectiveProgress[mission.id].orEmpty()
                val fallbackProg = snapshot.state.missionProgress[mission.id] ?: 0
                val summary = CleanMissionDisplay.simpleMissionSummary(mission, objProgMap, fallbackProg, memberCount)
                val summaryCol = if (completed) 0xFFFBBF24.toInt() else theme.textColor
                graphics.drawString(font, font.plainSubstrByWidth(summary, cardW - 20), cardLeft + 10, rowY, summaryCol, false)

                val rewardRowY = cursorY + cardH - 17
                val statusLabel = if (completed) "LISTA PARA RECLAMAR" else "ACTIVA"
                val statusCol = if (completed) 0xFFFBBF24.toInt() else baseAccent
                val statusComp = Component.literal(statusLabel).withStyle { it.withBold(completed) }
                val statusW = font.width(statusComp)
                graphics.drawString(font, statusComp, cardRight - statusW - 8, rewardRowY + 2, statusCol, false)

                drawInlineRewardsRow(
                    graphics,
                    mission,
                    cardLeft + 10,
                    rewardRowY,
                    cardRight - statusW - 14,
                    mouseInsideView,
                    mouseX,
                    mouseY,
                    theme
                )

                clickRegions.add(ClickRegion(cardLeft, cursorY, cardRight, cursorY + cardH) {
                    selectedId = mission.id
                    detailScroll = 0
                    ClientPlayNetworking.send(BulletinActionPayload("missionRead", mission.id))
                })
                cursorY += cardH + 5
            }
        }

        // ==================== SECTION 3: MISIONES COMPLETADAS O EXPIRADAS ====================
        if (!hideFinishedMissions) {
            cursorY += 6
            val finishedMissions = snapshot.document.missions.filter { mis ->
                mis.dailySlot == 0 && (mis.id in snapshot.state.claimedMissions || mis.isExpired())
            }
            drawScaledString(
                graphics,
                "MISIONES COMPLETADAS O EXPIRADAS (${finishedMissions.size})",
                cardLeft + 2,
                cursorY,
                1.05f,
                theme.completedAccent,
                bold = true
            )
            cursorY += 12
            graphics.fill(cardLeft, cursorY, cardRight, cursorY + 1, theme.border)
            cursorY += 5

            if (finishedMissions.isEmpty()) {
                graphics.drawString(font, "Ninguna mision completada o expirada.", cardLeft + 4, cursorY + 2, theme.mutedText, false)
                cursorY += 18
            } else {
                for (mission in finishedMissions) {
                    val cardH = 44
                    val hovered = mouseInsideView && mouseX in cardLeft..cardRight && mouseY in cursorY..cursorY + cardH
                    val claimed = mission.id in snapshot.state.claimedMissions
                    val expired = mission.isExpired()
                    val leftBar = if (claimed) 0xFF34D399.toInt() else if (expired) 0xFFEF4444.toInt() else theme.completedAccent

                    graphics.fill(cardLeft, cursorY, cardRight, cursorY + cardH, if (hovered) 0xFF171C24.toInt() else 0xFF11151B.toInt())
                    graphics.renderOutline(cardLeft, cursorY, cardW, cardH, if (hovered) leftBar else theme.border)
                    graphics.fill(cardLeft, cursorY, cardLeft + 3, cursorY + cardH, leftBar)

                    val catText = "(${mission.resolvedCategoryTag()})"
                    val catW = font.width(catText) + 8
                    val catX = cardRight - catW - 6
                    graphics.fill(catX, cursorY + 4, catX + catW, cursorY + 15, 0xFF1A1F27.toInt())
                    graphics.renderOutline(catX, cursorY + 4, catW, 11, theme.border)
                    graphics.drawString(font, catText, catX + 4, cursorY + 6, theme.mutedText, false)

                    val guildPrefix = if (mission.isGuildMission) "[Gremio] " else ""
                    val titleMaxW = (catX - (cardLeft + 10) - 6).coerceAtLeast(40)
                    val titleComp = ClientRichTextHelper.format("$guildPrefix${mission.title}", snapshot.uiTheme.mutedTextColorHex, boldDefault = false)
                    val clippedTitle = font.split(titleComp, titleMaxW).firstOrNull()
                    if (clippedTitle != null) {
                        graphics.drawString(font, clippedTitle, cardLeft + 10, cursorY + 6, theme.mutedText, false)
                    }

                    val statusLabel = if (claimed) "COMPLETADA" else "EXPIRADA"
                    val statusCol = if (claimed) 0xFF34D399.toInt() else 0xFFEF4444.toInt()
                    val statusW = font.width(statusLabel)
                    graphics.drawString(font, statusLabel, cardRight - statusW - 8, cursorY + 26, statusCol, false)

                    val objProgMap = snapshot.state.objectiveProgress[mission.id].orEmpty()
                    val fallbackProg = snapshot.state.missionProgress[mission.id] ?: 0
                    val summary = CleanMissionDisplay.simpleMissionSummary(mission, objProgMap, fallbackProg, memberCount)
                    val maxSummaryW = (cardRight - statusW - (cardLeft + 18)).coerceAtLeast(40)
                    graphics.drawString(font, font.plainSubstrByWidth(summary, maxSummaryW), cardLeft + 10, cursorY + 26, theme.completedAccent, false)

                    clickRegions.add(ClickRegion(cardLeft, cursorY, cardRight, cursorY + cardH) {
                        selectedId = mission.id
                        detailScroll = 0
                        ClientPlayNetworking.send(BulletinActionPayload("missionRead", mission.id))
                    })
                    cursorY += cardH + 5
                }
            }
        }

        missionsContentHeight = (cursorY - (viewTop + 4 - missionsScroll)).coerceAtLeast(0)
        graphics.disableScissor()

        // Clamp scroll in case content shrunk
        val maxScroll = (missionsContentHeight - viewHeight).coerceAtLeast(0)
        if (missionsScroll > maxScroll) missionsScroll = maxScroll

        // Scrollbar
        if (missionsContentHeight > viewHeight) {
            val trackX = viewRight - 5
            graphics.fill(trackX, viewTop + 3, trackX + 3, viewBottom - 3, 0xFF161B22.toInt())
            val trackH = (viewBottom - viewTop - 6).coerceAtLeast(10)
            val thumbH = maxOf(14, (trackH * (viewHeight.toFloat() / missionsContentHeight.toFloat())).toInt())
            val thumbY = viewTop + 3 + ((trackH - thumbH) * (missionsScroll.toFloat() / maxScroll.coerceAtLeast(1).toFloat())).toInt()
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, theme.dailyAccent)
        }
    }

    private fun drawInlineRewardsRow(
        graphics: GuiGraphics,
        mission: Mission,
        startX: Int,
        y: Int,
        maxRight: Int,
        mouseInsideView: Boolean,
        mouseX: Int,
        mouseY: Int,
        theme: ResolvedTheme
    ) {
        var rx = startX
        if (mission.moneyReward > 0L) {
            val moneyComp = Component.literal("$ ${formatMoney(mission.moneyReward)}").withStyle { it.withColor(0xFFFBBF24.toInt()).withBold(true) }
            if (rx + font.width(moneyComp) <= maxRight) {
                graphics.drawString(font, moneyComp, rx, y + 3, 0xFFFBBF24.toInt(), false)
                rx += font.width(moneyComp) + 8
            }
        }
        for (reward in mission.itemRewards) {
            if (rx + 32 > maxRight) break
            val stack = itemStack(reward.itemId)
            if (stack != null && !stack.isEmpty) {
                graphics.renderItem(stack, rx, y - 2)
                if (mouseInsideView && mouseX in rx..rx + 16 && mouseY in (y - 2)..(y + 14)) {
                    hoveredTooltipStack = stack
                }
                rx += 17
            }
            val qty = "x${reward.count}"
            graphics.drawString(font, qty, rx, y + 3, theme.textColor, false)
            rx += font.width(qty) + 7
        }
        val pokemonRewards = mission.pokemonRewards.filter { it.speciesId.isNotBlank() }
            .ifEmpty { listOfNotNull(mission.pokemonReward?.takeIf { it.speciesId.isNotBlank() }) }
        if (pokemonRewards.isNotEmpty() && rx + 40 <= maxRight) {
            val pName = CleanMissionDisplay.pokemonName(pokemonRewards.first().speciesId)
            val pText = font.plainSubstrByWidth("+$pName", maxRight - rx)
            graphics.drawString(font, pText, rx, y + 3, theme.dailyAccent, false)
        }
    }

    private fun renderMissionDetail(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        left: Int,
        top: Int,
        panelWidth: Int,
        panelHeight: Int,
        snapshot: ClientBulletinSnapshot,
        theme: ResolvedTheme,
        selected: Mission,
        partialTick: Float
    ) {
        val pokemonRewards = selected.pokemonRewards.filter { it.speciesId.isNotBlank() }
            .ifEmpty { listOfNotNull(selected.pokemonReward?.takeIf { it.speciesId.isNotBlank() }) }
        val featuredPokemonReward = pokemonRewards.firstOrNull()
        val hasPokemonPreview = featuredPokemonReward != null

        // Top tags (Category + Scope)
        val catTag = "(${selected.resolvedCategoryTag()})"
        val scopeTag = when {
            selected.dailySlot in 1..3 -> "[Diaria]"
            selected.isGuildMission -> "[Gremio]"
            else -> "[Individual]"
        }
        val catW = font.width(catTag) + 8
        val scopeW = font.width(scopeTag) + 8
        val catX = left + panelWidth - catW - 16
        val scopeX = catX - scopeW - 4
        val tagAccent = when {
            selected.dailySlot in 1..3 -> theme.dailyAccent
            selected.isGuildMission -> theme.guildAccent
            else -> theme.permanentAccent
        }

        graphics.fill(catX, top + 62, catX + catW, top + 74, theme.bgCard)
        graphics.renderOutline(catX, top + 62, catW, 12, tagAccent)
        graphics.drawString(font, catTag, catX + 4, top + 64, tagAccent, false)

        graphics.fill(scopeX, top + 62, scopeX + scopeW, top + 74, theme.bgCard)
        graphics.renderOutline(scopeX, top + 62, scopeW, 12, theme.border)
        graphics.drawString(font, scopeTag, scopeX + 4, top + 64, if (selected.isGuildMission) theme.guildAccent else theme.textColor, false)

        // Larger Bold Scaled Mission Title
        val titleMaxW = ((scopeX - (left + 18) - 8) / 1.3f).toInt().coerceAtLeast(60)
        val titleComp = ClientRichTextHelper.format(selected.title, selected.color.ifBlank { "#38BDF8" }, boldDefault = true)
        val clippedTitle = font.split(titleComp, titleMaxW).firstOrNull() ?: titleComp.visualOrderText
        drawScaledComponent(
            graphics,
            clippedTitle,
            left + 18,
            top + 62,
            1.3f,
            parseThemeColor(selected.color, theme.dailyAccent)
        )

        graphics.fill(left + 16, top + 82, left + panelWidth - 16, top + 83, theme.border)

        val previewW = 140
        val previewH = 176
        val previewX = left + panelWidth - previewW - 14
        val previewY = top + 88

        val contentTop = top + 88
        val contentBottom = top + panelHeight - 40
        val colRight = if (hasPokemonPreview) previewX - 8 else left + panelWidth - 16
        val leftColW = (colRight - (left + 18)).coerceAtLeast(120)
        val mouseInsideDetail = mouseX in (left + 14)..colRight && mouseY in contentTop..contentBottom

        enableVirtualScissor(graphics, left + 14, contentTop, colRight, contentBottom)
        var cursorY = contentTop + 2 - detailScroll

        if (selected.body.isNotBlank()) {
            val bodyScale = 1.08f
            val bodyComp = ClientRichTextHelper.format(selected.body, selected.bodyColor.ifBlank { "#E6EDF3" }, boldDefault = true)
            font.split(bodyComp, (leftColW / bodyScale).toInt()).forEach { line ->
                drawScaledComponent(graphics, line, left + 18, cursorY, bodyScale, parseThemeColor(selected.bodyColor, theme.textColor))
                cursorY += (font.lineHeight * bodyScale).toInt() + 4
            }
            cursorY += 8
        }

        val objectives = missionObjectives(selected)
        drawScaledString(graphics, "OBJETIVOS (${objectives.size})", left + 18, cursorY, 1.2f, theme.dailyAccent, bold = true)
        cursorY += (font.lineHeight * 1.2f).toInt() + 6

        val objectiveProgress = snapshot.state.objectiveProgress[selected.id].orEmpty()
        val memberCount = snapshot.state.guildMemberCount.coerceAtLeast(1)
        objectives.forEach { objective ->
            val progress = objectiveProgress[objective.id]
                ?: if (objective.id == "legacy") snapshot.state.missionProgress[selected.id] ?: 0 else 0
            val targetAmt = if (objective.targetUsesMemberCount && selected.isGuildMission) memberCount else objective.amount
            val clamped = progress.coerceAtMost(targetAmt)
            val done = progress >= targetAmt

            val prefix = if (done) "[OK] " else "- "
            val lineColor = if (done) 0xFF4ADE80.toInt() else theme.textColor
            val desc = CleanMissionDisplay.describeObjective(objective, selected.isGuildMission, memberCount)
            val objScale = 1.1f
            val objComp = Component.literal("$prefix$desc ($clamped/$targetAmt)").withStyle { it.withBold(true) }
            font.split(objComp, (leftColW / objScale).toInt()).forEach { line ->
                drawScaledComponent(graphics, line, left + 18, cursorY, objScale, lineColor)
                cursorY += (font.lineHeight * objScale).toInt() + 4
            }

            if (objective.type == ObjectiveType.GET_ITEM && objective.consumeOnClaim) {
                val modeStr = "Entrega los objetos requeridos al completar"
                graphics.drawString(font, modeStr, left + 26, cursorY, theme.mutedText, false)
                cursorY += font.lineHeight + 3
            }
            if (selected.isGuildMission && (selected.targetUsesMemberCount || objective.targetUsesMemberCount)) {
                val coopStr = "Cooperativa de gremio: escala xMiembro ($memberCount)"
                graphics.drawString(font, coopStr, left + 26, cursorY, theme.guildAccent, false)
                cursorY += font.lineHeight + 3
            }
            cursorY += 4
        }

        cursorY += 8
        cursorY = drawMissionRewardsDetail(
            graphics,
            selected,
            pokemonRewards,
            left + 18,
            cursorY,
            leftColW,
            mouseInsideDetail,
            mouseX,
            mouseY,
            theme
        )

        detailContentHeight = (cursorY - (contentTop + 2 - detailScroll)).coerceAtLeast(0)
        graphics.disableScissor()

        // Detail scrollbar if overflowing
        val visibleDetailH = (contentBottom - contentTop).coerceAtLeast(1)
        if (detailContentHeight > visibleDetailH) {
            val trackX = colRight - 4
            val maxDetScroll = (detailContentHeight - visibleDetailH).coerceAtLeast(1)
            graphics.fill(trackX, contentTop + 2, trackX + 3, contentBottom - 2, theme.bgCard)
            val trackH = (contentBottom - contentTop - 4).coerceAtLeast(10)
            val thumbH = maxOf(14, (trackH * (visibleDetailH.toFloat() / detailContentHeight.toFloat())).toInt())
            val thumbY = contentTop + 2 + ((trackH - thumbH) * (detailScroll.toFloat() / maxDetScroll.toFloat())).toInt()
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, theme.dailyAccent)
        }

        // Spinning 3D Pokemon Model ONLY when a Pokemon reward is configured
        if (hasPokemonPreview && featuredPokemonReward != null) {
            renderPokemonRewardPreview3D(
                graphics,
                featuredPokemonReward,
                previewX,
                previewY,
                previewW,
                previewH,
                theme,
                partialTick
            )
        }

        // Bottom Claim Bar
        val completed = selected.id in snapshot.state.completedMissions
        val claimed = selected.id in snapshot.state.claimedMissions
        val isExp = selected.isExpired()
        val isPausedDaily = selected.dailySlot in 1..3 && snapshot.dailyCycle.paused

        val barLeft = left + 14
        val barRight = left + panelWidth - 14
        val barTop = top + panelHeight - 33
        val barBottom = top + panelHeight - 9
        val barW = barRight - barLeft
        val barH = barBottom - barTop

        if (completed && !claimed && !isExp && !isPausedDaily) {
            val btnHovered = mouseX in barLeft..barRight && mouseY in barTop..barBottom
            graphics.fill(barLeft, barTop, barRight, barBottom, if (btnHovered) 0xFF16A34A.toInt() else 0xFF15803D.toInt())
            graphics.renderOutline(barLeft, barTop, barW, barH, 0xFF4ADE80.toInt())
            val claimComp = Component.literal(TextCatalog.string("mission.claim")).withStyle { it.withBold(true) }
            val textW = (font.width(claimComp) * 1.15f).toInt()
            drawScaledComponent(graphics, claimComp, barLeft + (barW - textW) / 2, barTop + 6, 1.15f, 0xFFFFFFFF.toInt())

            clickRegions.add(ClickRegion(barLeft, barTop, barRight, barBottom, requiresViewportBounds = false) {
                ClientPlayNetworking.send(BulletinActionPayload("claim", selected.id))
            })
        } else if (claimed) {
            graphics.fill(barLeft, barTop, barRight, barBottom, 0xFF11221A.toInt())
            graphics.renderOutline(barLeft, barTop, barW, barH, 0xFF22C55E.toInt())
            val lbl = Component.literal(TextCatalog.string("mission.claimed")).withStyle { it.withBold(true) }
            val textW = (font.width(lbl) * 1.1f).toInt()
            drawScaledComponent(graphics, lbl, barLeft + (barW - textW) / 2, barTop + 7, 1.1f, 0xFF4ADE80.toInt())
        } else if (isExp) {
            graphics.fill(barLeft, barTop, barRight, barBottom, 0xFF241316.toInt())
            graphics.renderOutline(barLeft, barTop, barW, barH, 0xFFEF4444.toInt())
            val expComp = Component.literal("MISION EXPIRADA (${selected.expirationDate})").withStyle { it.withBold(true) }
            val textW = (font.width(expComp) * 1.05f).toInt()
            drawScaledComponent(graphics, expComp, barLeft + (barW - textW) / 2, barTop + 7, 1.05f, 0xFFFCA5A5.toInt())
        } else {
            val statusText = buildString {
                append(
                    when {
                        selected.dailySlot in 1..3 -> TextCatalog.string("mission.daily")
                        selected.isGuildMission -> "Misión de gremio"
                        selected.repeatable -> TextCatalog.string("mission.repeatable")
                        selected.daily -> TextCatalog.string("mission.daily")
                        else -> TextCatalog.string("mission.permanent")
                    }
                )
                if (selected.expirationDate.isNotBlank()) {
                    append(" | Vence: ").append(selected.expirationDate)
                }
            }
            graphics.drawString(font, Component.literal(statusText).withStyle { it.withBold(true) }, barLeft + 2, barTop + 8, theme.mutedText, false)
        }
    }

    private fun drawMissionRewardsDetail(
        graphics: GuiGraphics,
        mission: Mission,
        pokemonRewards: List<PokemonMissionReward>,
        x: Int,
        y: Int,
        maxWidth: Int,
        mouseInsideDetail: Boolean,
        mouseX: Int,
        mouseY: Int,
        theme: ResolvedTheme
    ): Int {
        drawScaledString(graphics, "RECOMPENSAS", x, y, 1.2f, 0xFFFBBF24.toInt(), bold = true)
        var cursorY = y + (font.lineHeight * 1.2f).toInt() + 6

        if (mission.itemRewards.isEmpty() && pokemonRewards.isEmpty() && mission.moneyReward <= 0L) {
            drawScaledString(graphics, TextCatalog.string("reward.none"), x, cursorY, 1.05f, theme.mutedText, bold = true)
            return cursorY + 18
        }

        if (mission.moneyReward > 0L) {
            val moneyLabel = "$ ${formatMoney(mission.moneyReward)} CobbleDollars"
            drawScaledString(graphics, moneyLabel, x, cursorY, 1.12f, 0xFFFBBF24.toInt(), bold = true)
            cursorY += (font.lineHeight * 1.12f).toInt() + 6
        }

        mission.itemRewards.forEach { reward ->
            val stack = itemStack(reward.itemId)
            if (stack != null && !stack.isEmpty) {
                graphics.renderItem(stack, x, cursorY - 2)
                if (mouseInsideDetail && mouseX in x..x + 16 && mouseY in (cursorY - 2)..(cursorY + 14)) {
                    hoveredTooltipStack = stack
                }
            }
            val label = CleanMissionDisplay.itemDisplayName(reward.itemId)
            val rewardText = "x${reward.count} $label"
            val textX = x + if (stack == null || stack.isEmpty) 0 else 20
            val rewScale = 1.08f
            val availW = ((maxWidth - (textX - x)) / rewScale).toInt().coerceAtLeast(40)
            val comp = Component.literal(rewardText).withStyle { it.withBold(true) }
            font.split(comp, availW).forEach { line ->
                drawScaledComponent(graphics, line, textX, cursorY + 1, rewScale, theme.textColor)
                cursorY += (font.lineHeight * rewScale).toInt() + 4
            }
            cursorY += 4
        }

        pokemonRewards.forEach { reward ->
            val cleanDesc = CleanMissionDisplay.describePokemonReward(reward)
            val pScale = 1.08f
            val pComp = Component.literal("Pokémon: $cleanDesc").withStyle { it.withBold(true) }
            font.split(pComp, (maxWidth / pScale).toInt().coerceAtLeast(40)).forEach { line ->
                drawScaledComponent(graphics, line, x, cursorY, pScale, theme.dailyAccent)
                cursorY += (font.lineHeight * pScale).toInt() + 4
            }
            cursorY += 4
        }

        return cursorY
    }

    private fun renderPokemonRewardPreview3D(
        graphics: GuiGraphics,
        reward: PokemonMissionReward,
        previewX: Int,
        previewY: Int,
        previewW: Int,
        previewH: Int,
        theme: ResolvedTheme,
        partialTick: Float
    ) {
        graphics.fill(previewX, previewY, previewX + previewW, previewY + previewH, theme.bgCard)
        graphics.renderOutline(previewX, previewY, previewW, previewH, theme.dailyAccent)
        graphics.fill(previewX, previewY, previewX + previewW, previewY + 2, theme.dailyAccent)

        val headerComp = Component.literal("RECOMPENSA POKEMON").withStyle { it.withBold(true) }
        graphics.drawCenteredString(font, headerComp, previewX + previewW / 2, previewY + 6, 0xFFFBBF24.toInt())

        val modelAreaTop = previewY + 20
        val modelAreaBottom = previewY + previewH - 28
        val modelAreaH = (modelAreaBottom - modelAreaTop).coerceAtLeast(40)
        val modelCenterX = previewX + previewW / 2f
        val modelCenterY = modelAreaTop + modelAreaH / 2f + 12f

        val cleanId = reward.speciesId.trim().lowercase()
        val rawRes = ResourceLocation.tryParse(if (':' in cleanId) cleanId else "cobblemon:$cleanId")
            ?: ResourceLocation.fromNamespaceAndPath("cobblemon", cleanId.substringAfter(':'))

        val speciesObj = PokemonSpecies.getByIdentifier(rawRes) ?: run {
            val strippedPath = rawRes.path
                .replace("_galarian", "").replace("-galarian", "").replace("_galar", "").replace("-galar", "")
                .replace("_alolan", "").replace("-alolan", "").replace("_alola", "").replace("-alola", "")
                .replace("_hisuian", "").replace("-hisuian", "").replace("_hisui", "").replace("-hisui", "")
                .replace("_paldean", "").replace("-paldean", "").replace("_paldea", "").replace("-paldea", "")
            PokemonSpecies.getByIdentifier(ResourceLocation.fromNamespaceAndPath(rawRes.namespace, strippedPath))
        }

        val aspects = mutableSetOf<String>()
        val propsText = reward.properties.trim()
        if (propsText.isNotBlank()) {
            runCatching {
                val parsed = PokemonProperties.parse("species=$cleanId $propsText")
                aspects.addAll(parsed.aspects.map { it.lowercase() })
                if (parsed.shiny == true) aspects.add("shiny")
                parsed.form?.takeIf { it.isNotBlank() }?.let { formToken ->
                    aspects.add(formToken.lowercase())
                }
            }
            val lowerProps = "$cleanId $propsText".lowercase()
            if (lowerProps.contains("shiny=yes") || lowerProps.contains("shiny=true") ||
                lowerProps.split(Regex("\\s+")).any { it == "shiny" }
            ) {
                aspects.add("shiny")
            }
            if (lowerProps.contains("alola")) {
                aspects.add("alolan")
                aspects.add("alola")
            }
            if (lowerProps.contains("galar")) {
                aspects.add("galarian")
                aspects.add("galar")
            }
            if (lowerProps.contains("hisui")) {
                aspects.add("hisuian")
                aspects.add("hisui")
            }
            if (lowerProps.contains("paldea")) {
                aspects.add("paldean")
                aspects.add("paldea")
            }
            if (lowerProps.contains("valencia")) {
                aspects.add("valencian")
            }
        }

        if (speciesObj != null) {
            val matchedForm = speciesObj.forms.firstOrNull { form ->
                val fn = form.name.lowercase()
                aspects.any { asp ->
                    asp == fn ||
                        (asp == "alolan" && fn == "alola") ||
                        (asp == "galarian" && fn == "galar") ||
                        (asp == "hisuian" && fn == "hisui") ||
                        (asp == "paldean" && fn == "paldea")
                }
            }
            if (matchedForm != null) {
                aspects.addAll(matchedForm.aspects.map { it.lowercase() })
            }
        }

        val stateKey = "${reward.speciesId}#${aspects.sorted().joinToString(",")}"
        val pState = previewModelStates.getOrPut(stateKey) { FloatingState() }
        pState.currentAspects = aspects

        enableVirtualScissor(graphics, previewX + 2, modelAreaTop, previewX + previewW - 2, modelAreaBottom)
        try {
            if (speciesObj != null) {
                val speciesIdentifier = speciesObj.resourceIdentifier
                val poser = runCatching {
                    VaryingModelRepository.getPoser(speciesIdentifier, pState)
                }.getOrNull()
                val pScale = poser?.profileScale?.takeIf { it > 0.01f } ?: 0.40f
                val targetPixelSize = modelAreaH * 0.55f
                val baseScale = ((targetPixelSize * 0.40f) / pScale).coerceIn(10.0f, 34.0f)

                val animAngle = ((System.currentTimeMillis() / 22L) % 360L).toFloat()
                val cardRotation = Quaternionf().rotationXYZ(
                    Math.toRadians(12.0).toFloat(),
                    Math.toRadians(animAngle.toDouble()).toFloat(),
                    0f
                )

                var pushed = false
                try {
                    graphics.pose().pushPose()
                    pushed = true
                    graphics.pose().translate(modelCenterX, modelCenterY, 200.0f)
                    drawProfilePokemon(
                        renderablePokemon = RenderablePokemon(speciesObj, aspects),
                        matrixStack = graphics.pose(),
                        rotation = cardRotation,
                        poseType = PoseType.PROFILE,
                        state = pState,
                        partialTicks = partialTick,
                        scale = baseScale,
                        profileTransformType = ProfileTransformType.PROFILE
                    )
                } catch (_: Throwable) {
                    // Graceful fallback if species model cannot render
                } finally {
                    if (pushed) {
                        graphics.pose().popPose()
                    }
                    runCatching { graphics.flush() }
                }
            } else {
                val fallbackLabel = font.plainSubstrByWidth(
                    CleanMissionDisplay.pokemonName(reward.speciesId),
                    previewW - 12
                )
                graphics.drawCenteredString(
                    font,
                    fallbackLabel,
                    previewX + previewW / 2,
                    (modelAreaTop + modelAreaBottom) / 2 - 4,
                    theme.mutedText
                )
            }
        } finally {
            graphics.disableScissor()
        }

        val pName = CleanMissionDisplay.pokemonName(reward.speciesId)
        val nameComp = Component.literal(font.plainSubstrByWidth(pName, previewW - 10)).withStyle { it.withBold(true) }
        graphics.drawCenteredString(font, nameComp, previewX + previewW / 2, previewY + previewH - 22, theme.textColor)
        if ("shiny" in aspects) {
            graphics.drawCenteredString(font, Component.literal("Shiny").withStyle { it.withBold(true) }, previewX + previewW / 2, previewY + previewH - 11, 0xFFFBBF24.toInt())
        }
    }

    private fun enableVirtualScissor(graphics: GuiGraphics, x1: Int, y1: Int, x2: Int, y2: Int) {
        val sx1 = (renderX + x1 * uiScale).toInt()
        val sy1 = (renderY + y1 * uiScale).toInt()
        val sx2 = (renderX + x2 * uiScale).toInt()
        val sy2 = (renderY + y2 * uiScale).toInt()
        graphics.enableScissor(sx1, sy1, sx2.coerceAtLeast(sx1 + 1), sy2.coerceAtLeast(sy1 + 1))
    }

    private fun drawScaledString(
        graphics: GuiGraphics,
        text: String,
        x: Int,
        y: Int,
        scale: Float,
        color: Int,
        bold: Boolean = false
    ) {
        val comp = if (bold) Component.literal(text).withStyle { it.withBold(true) } else Component.literal(text)
        drawScaledComponent(graphics, comp, x, y, scale, color)
    }

    private fun drawScaledComponent(
        graphics: GuiGraphics,
        component: Component,
        x: Int,
        y: Int,
        scale: Float,
        color: Int
    ) {
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 0f)
        pose.scale(scale, scale, 1f)
        graphics.drawString(font, component, 0, 0, color, false)
        pose.popPose()
    }

    private fun drawScaledComponent(
        graphics: GuiGraphics,
        charSeq: net.minecraft.util.FormattedCharSequence,
        x: Int,
        y: Int,
        scale: Float,
        color: Int
    ) {
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 0f)
        pose.scale(scale, scale, 1f)
        graphics.drawString(font, charSeq, 0, 0, color, false)
        pose.popPose()
    }

    private fun formatHms(totalSeconds: Long): String {
        val clamped = totalSeconds.coerceAtLeast(0L)
        val hours = clamped / 3600L
        val minutes = (clamped % 3600L) / 60L
        val seconds = clamped % 60L
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatMoney(amount: Long): String =
        NumberFormat.getIntegerInstance(Locale.US).format(amount)

    private fun itemStack(id: String): ItemStack? = runCatching {
        val clean = id.trim()
        val resource = if (':' in clean) ResourceLocation.parse(clean) else ResourceLocation.withDefaultNamespace(clean)
        val item = BuiltInRegistries.ITEM.getOptional(resource).orElse(null) ?: return null
        ItemStack(item)
    }.getOrNull()

    private fun missionObjectives(mission: Mission): List<MissionObjective> = mission.objectives
        .filter { it.amount > 0 }
        .ifEmpty {
            listOf(
                MissionObjective(
                    id = "legacy",
                    type = mission.objective,
                    amount = mission.amount,
                    targetUsesMemberCount = mission.targetUsesMemberCount,
                    itemId = mission.itemId,
                    pokemonId = mission.pokemonId,
                    pokemonType = mission.pokemonType,
                    badgeId = mission.badgeId
                )
            )
        }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (openingProgress() < 1.0F) return true
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)

        val localX = ((mouseX - renderX) / uiScale).toInt()
        val localY = ((mouseY - renderY) / uiScale).toInt()
        val panelWidth = BASE_W.toInt()
        val panelHeight = BASE_H.toInt()
        val left = 0
        val top = 0
        val snapshot = BulletinClientData.snapshot
        val isAdmin = snapshot.state.adminMode

        // Top bar Back / Admin
        if (selectedId != null && localY in top + 4..top + 22 && localX in left + 8..left + 84) {
            selectedId = null
            detailScroll = 0
            return true
        }
        if (isAdmin && localY in top + 4..top + 22 && localX in left + panelWidth - 78..left + panelWidth - 8) {
            ClientPlayNetworking.send(BulletinAdminActionPayload("requestAdminScreen", if (showingMissions) "missions" else "announcements"))
            return true
        }

        // Main 2 Tabs: Misiones & Anuncios
        if (localY in top + 31..top + 51) {
            if (localX in left + 10..left + 138) {
                showingMissions = true
                selectedId = null
                missionsScroll = 0
                detailScroll = 0
                markAllMissionsRead()
                return true
            }
            if (localX in left + 144..left + 272) {
                showingMissions = false
                selectedId = null
                announcementsScroll = 0
                detailScroll = 0
                markAllAnnouncementsRead()
                return true
            }
        }

        val viewTop = top + 57
        val viewBottom = top + panelHeight - 17
        for (region in clickRegions.asReversed()) {
            if (region.requiresViewportBounds && localY !in viewTop..viewBottom) continue
            if (localX in region.x1..region.x2 && localY in region.y1..region.y2) {
                region.onClick()
                return true
            }
        }

        return super.mouseClicked(localX.toDouble(), localY.toDouble(), button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val panelHeight = BASE_H.toInt()
        if (selectedId != null) {
            val visibleHeight = panelHeight - 128
            val maxDetailScroll = (detailContentHeight - visibleHeight).coerceAtLeast(0)
            detailScroll = (detailScroll - (verticalAmount * 18).toInt()).coerceIn(0, maxDetailScroll)
            return true
        }
        if (showingMissions) {
            val visibleHeight = (panelHeight - 74).coerceAtLeast(1)
            val maxScroll = (missionsContentHeight - visibleHeight).coerceAtLeast(0)
            missionsScroll = (missionsScroll - (verticalAmount * 22).toInt()).coerceIn(0, maxScroll)
            return true
        }
        val listSize = BulletinClientData.snapshot.document.announcements.size
        val listH = panelHeight - 82
        val maxRows = (listH / 27).coerceAtLeast(1)
        val maxScroll = (listSize - maxRows).coerceAtLeast(0)
        announcementsScroll = (announcementsScroll - verticalAmount.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && selectedId != null) {
            selectedId = null
            detailScroll = 0
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun isPauseScreen(): Boolean = false
}

private data class BulletinTexture(val id: ResourceLocation, val width: Int, val height: Int)

private object BulletinImages {
    private val cache = mutableMapOf<String, BulletinTexture>()
    private val sequence = AtomicInteger()

    fun texture(dataUrl: String): BulletinTexture? {
        if (!dataUrl.startsWith("data:image/")) return null
        return runCatching {
            cache.getOrPut(dataUrl) {
                require(dataUrl.length <= 180_000)
                val separator = dataUrl.indexOf(',')
                require(separator > 0)
                val bytes = Base64.getDecoder().decode(dataUrl.substring(separator + 1))
                val image = NativeImage.read(ByteArrayInputStream(bytes))
                require(image.width <= 512 && image.height <= 512)
                val id = ResourceLocation.fromNamespaceAndPath("desgraciamon4_announcements", "announcement_${sequence.incrementAndGet()}")
                Minecraft.getInstance().textureManager.register(id, DynamicTexture(image))
                BulletinTexture(id, image.width, image.height)
            }
        }.getOrNull()
    }
}
