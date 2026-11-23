package com.desgraciamon.badges

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class HitosTimelineScreen(
    private val parent: Screen?,
    private val animateOpening: Boolean = true
) : Screen(Component.literal("Cronograma de Hitos")) {

    private data class RegionPalette(
        val panelBg: Int,
        val headerBg: Int,
        val footerBg: Int,
        val border: Int,
        val accent: Int,
        val accentLight: Int,
        val barFill: Int,
        val barHighlight: Int,
        val tabActiveBg: Int,
        val tabIdleBg: Int,
        val cardDefaultBg: Int,
        val cardHoverBg: Int,
        val bandBg: Int
    )

    private data class ClickRegion(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val inWorldSpace: Boolean,
        val onClick: () -> Unit
    ) {
        fun contains(mx: Double, my: Double): Boolean =
            mx >= x && mx <= x + w && my >= y && my <= y + h
    }

    private data class RewardIconEntry(
        val stack: ItemStack,
        val count: Int
    )

    private data class PhaseGroup(
        val label: String,
        val startIdx: Int,
        val endIdx: Int,
        val dayStart: Int,
        val dayEnd: Int
    )

    private val openedAtNanos: Long = System.nanoTime()
    private var uiScale: Float = 1.0f
    private var renderX: Float = 0.0f
    private var renderY: Float = 0.0f

    private var panX: Double = 0.0
    private var panY: Double = 0.0
    private var zoom: Double = 1.0
    private var isDragging: Boolean = false
    private var dragStartX: Double = 0.0
    private var dragStartY: Double = 0.0
    private var dragStartPanX: Double = 0.0
    private var dragStartPanY: Double = 0.0
    private var hasInitializedCamera: Boolean = false
    private var selectedRegion: String = ""

    private var hoveredMilestone: HitoMilestone? = null
    private val clickRegions = mutableListOf<ClickRegion>()

    private val BASE_W = 580
    private val BASE_H = 330
    private val NODE_W = 156
    private val NODE_H = 70
    private val FEATURED_W = 204
    private val FEATURED_H = 78
    private val GAP_X = 28
    private val START_X = 60

    private val MIN_ZOOM = 0.55
    private val MAX_ZOOM = 1.45

    private val config get() = HitosManager.config
    private val milestones get() = ArrayList(config.milestonesForRegion(currentSelectedRegion()))

    private fun paletteFor(region: String): RegionPalette = when (region.lowercase().trim()) {
        "johto" -> RegionPalette(
            panelBg = 0xF2111420.toInt(),
            headerBg = 0xFF171B2B.toInt(),
            footerBg = 0xFF131724.toInt(),
            border = 0xFF2D3554.toInt(),
            accent = 0xFFEAB308.toInt(),
            accentLight = 0xFFFDE047.toInt(),
            barFill = 0xFFCA8A04.toInt(),
            barHighlight = 0xFFFACC15.toInt(),
            tabActiveBg = 0xFF2B2848.toInt(),
            tabIdleBg = 0xFF161A29.toInt(),
            cardDefaultBg = 0xEB171B2A.toInt(),
            cardHoverBg = 0xF5232940.toInt(),
            bandBg = 0x26EAB308
        )
        "hoenn" -> RegionPalette(
            panelBg = 0xF20E1919.toInt(),
            headerBg = 0xFF132424.toInt(),
            footerBg = 0xFF101D1D.toInt(),
            border = 0xFF224240.toInt(),
            accent = 0xFF10B981.toInt(),
            accentLight = 0xFF5EEAD4.toInt(),
            barFill = 0xFF059669.toInt(),
            barHighlight = 0xFF34D399.toInt(),
            tabActiveBg = 0xFF163836.toInt(),
            tabIdleBg = 0xFF122121.toInt(),
            cardDefaultBg = 0xEB132122.toInt(),
            cardHoverBg = 0xF51B3133.toInt(),
            bandBg = 0x2610B981
        )
        "sinnoh" -> RegionPalette(
            panelBg = 0xF2151220.toInt(),
            headerBg = 0xFF1D182E.toInt(),
            footerBg = 0xFF171324.toInt(),
            border = 0xFF3A2D59.toInt(),
            accent = 0xFFA855F7.toInt(),
            accentLight = 0xFF7DD3FC.toInt(),
            barFill = 0xFF7E22CE.toInt(),
            barHighlight = 0xFFC084FC.toInt(),
            tabActiveBg = 0xFF2E224A.toInt(),
            tabIdleBg = 0xFF1A1629.toInt(),
            cardDefaultBg = 0xEB1B172B.toInt(),
            cardHoverBg = 0xF5292242.toInt(),
            bandBg = 0x26A855F7
        )
        else -> RegionPalette(
            panelBg = 0xF2171216.toInt(),
            headerBg = 0xFF21181E.toInt(),
            footerBg = 0xFF1A1419.toInt(),
            border = 0xFF442B33.toInt(),
            accent = 0xFFE11D48.toInt(),
            accentLight = 0xFFFBBF24.toInt(),
            barFill = 0xFFBE123C.toInt(),
            barHighlight = 0xFFFB7185.toInt(),
            tabActiveBg = 0xFF3A1D28.toInt(),
            tabIdleBg = 0xFF1E161B.toInt(),
            cardDefaultBg = 0xEB1D161C.toInt(),
            cardHoverBg = 0xF52D2129.toInt(),
            bandBg = 0x26E11D48
        )
    }

    private fun openingProgress(): Float {
        if (!animateOpening) return 1.0f
        return ((System.nanoTime() - openedAtNanos) / 2.8e8f).coerceIn(0.0f, 1.0f)
    }

    private fun isFeaturedCard(m: HitoMilestone): Boolean =
        m.id.equals("primeros_dias", ignoreCase = true) || (!m.isProgressMilestone() && m.dayOffset == 1 && m.durationDays >= 3)

    private fun cardWidth(m: HitoMilestone): Int = if (isFeaturedCard(m)) FEATURED_W else NODE_W
    private fun cardHeight(m: HitoMilestone): Int = if (isFeaturedCard(m)) FEATURED_H else NODE_H

    private fun computeNodeCenters(list: List<HitoMilestone>): IntArray {
        val centers = IntArray(list.size)
        var cursor = START_X
        for (i in list.indices) {
            val w = cardWidth(list[i])
            if (i > 0 && list[i].dayOffset != list[i - 1].dayOffset) {
                cursor += 18
            }
            centers[i] = cursor + w / 2
            cursor += w + GAP_X
        }
        return centers
    }

    private fun computePhaseGroups(list: List<HitoMilestone>): List<PhaseGroup> {
        val groups = mutableListOf<PhaseGroup>()
        var i = 0
        var tramoNum = 1
        while (i < list.size) {
            val m = list[i]
            if (!m.isProgressMilestone()) {
                i++
                continue
            }
            val startD = m.dayOffset
            val endD = config.endDayOf(m)
            var j = i
            while (j + 1 < list.size &&
                list[j + 1].isProgressMilestone() &&
                list[j + 1].dayOffset == startD &&
                config.endDayOf(list[j + 1]) == endD
            ) {
                j++
            }
            val count = j - i + 1
            val label = if (count == 1 && (m.id.contains("league", true) || m.title.contains("Liga", true))) {
                "LIGA (Dias $startD-$endD)"
            } else {
                val t = tramoNum++
                "TRAMO $t (Dias $startD-$endD)"
            }
            groups.add(PhaseGroup(label, i, j, startD, endD))
            i = j + 1
        }
        return groups
    }

    private fun availableRegionTabs(): List<String> {
        val isAdminEditor = com.desgraciamon.announcements.BulletinClientData.snapshot.state.adminMode &&
            BadgesClientState.hasAdminBadge()
        if (isAdminEditor) return HitosConfig.REGION_ORDER
        val unlocked = LinkedHashSet(config.unlockedRegions())
        if (BadgesClientState.rctAvailable) {
            if (BadgesClientState.rctActiveRegion in HitosConfig.REGION_ORDER) {
                unlocked.add(BadgesClientState.rctActiveRegion)
            }
            for (comp in BadgesClientState.rctCompletedRegions) {
                if (comp in HitosConfig.REGION_ORDER) unlocked.add(comp)
            }
        }
        return HitosConfig.REGION_ORDER.filter { it in unlocked }.ifEmpty { listOf("kanto") }
    }

    private fun currentSelectedRegion(): String {
        val visible = availableRegionTabs()
        if (selectedRegion.isBlank() || selectedRegion !in visible) {
            val preferred = if (BadgesClientState.rctAvailable && BadgesClientState.rctActiveRegion in visible) {
                BadgesClientState.rctActiveRegion
            } else {
                config.activeRegion()
            }
            selectedRegion = if (preferred in visible) preferred else (visible.firstOrNull() ?: "kanto")
        }
        return selectedRegion
    }

    private fun isRegionCompleted(region: String): Boolean {
        val clean = region.lowercase().trim()
        if (BadgesClientState.rctAvailable && clean in BadgesClientState.rctCompletedRegions) {
            return true
        }
        val prog = config.progressionMilestonesForRegion(clean)
        if (prog.isEmpty()) return false
        val unlocked = BadgesClientState.effectiveHitosBadges()
        return prog.all { HitosManager.hasUnlockedMilestone(unlocked, it) }
    }

    private fun shouldShowTodayMarker(region: String): Boolean {
        val clean = region.lowercase().trim()
        if (isRegionCompleted(clean)) return false
        if (BadgesClientState.rctAvailable && BadgesClientState.rctActiveRegion.isNotBlank()) {
            return clean.equals(BadgesClientState.rctActiveRegion, ignoreCase = true)
        }
        return clean.equals(config.activeRegion(), ignoreCase = true)
    }

    private fun centerCamera() {
        zoom = 1.0
        panY = 0.0
        val list = milestones
        if (list.isEmpty()) {
            panX = 0.0
            return
        }
        val centers = computeNodeCenters(list)
        val currentDay = config.currentServerDay()
        val targetIdx = list.indexOfFirst { config.endDayOf(it) >= currentDay }
            .let { if (it < 0) list.lastIndex else it }
        panX = -centers[targetIdx].toDouble()
        clampCamera(list, centers)
    }

    private fun clampCamera(list: List<HitoMilestone>, centers: IntArray) {
        if (list.isEmpty()) {
            panX = 0.0
            panY = 0.0
            return
        }
        val minWorldX = (centers.first() - cardWidth(list.first()) / 2 - 40).toDouble()
        val maxWorldX = (centers.last() + cardWidth(list.last()) / 2 + 40).toDouble()
        panX = panX.coerceIn(-maxWorldX, -minWorldX)
        panY = panY.coerceIn(-95.0, 95.0)
    }

    override fun init() {
        super.init()
        if (Minecraft.getInstance().hasSingleplayerServer()) {
            HitosManager.load()
        }
        if (ClientPlayNetworking.canSend(BadgeTogglePayload.TYPE)) {
            ClientPlayNetworking.send(BadgeTogglePayload("request", ""))
        }

        val maxW = max(240f, width - 24f)
        val maxH = max(180f, height - 24f)
        uiScale = minOf(1.0f, maxW / BASE_W.toFloat(), maxH / BASE_H.toFloat()).coerceAtLeast(0.45f)
        renderX = (width - BASE_W * uiScale) / 2.0f
        renderY = (height - BASE_H * uiScale) / 2.0f

        if (!hasInitializedCamera) {
            hasInitializedCamera = true
            centerCamera()
        }
    }

    fun refresh() {
        val visible = availableRegionTabs()
        if (selectedRegion.isNotBlank() && selectedRegion !in visible) {
            selectedRegion = ""
        }
    }

    override fun renderBlurredBackground(delta: Float) {}
    override fun renderMenuBackground(context: GuiGraphics) {}
    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

    private fun computeServerProgressWorldX(list: List<HitoMilestone>, centers: IntArray): Int {
        if (list.isEmpty()) return START_X
        val currentDay = config.currentServerDayExact()

        // Group milestones by their day window so shared-window gyms interpolate smoothly across the whole block
        val windows = mutableListOf<Triple<Double, Double, Pair<Double, Double>>>()
        var i = 0
        while (i < list.size) {
            val startD = list[i].dayOffset.toDouble()
            val endD = (config.endDayOf(list[i]) + 1).toDouble()
            var j = i
            while (j + 1 < list.size &&
                list[j + 1].dayOffset.toDouble() == startD &&
                (config.endDayOf(list[j + 1]) + 1).toDouble() == endD
            ) {
                j++
            }
            val leftX = centers[i] - cardWidth(list[i]) / 2.0
            val rightX = centers[j] + cardWidth(list[j]) / 2.0
            windows.add(Triple(startD, endD, leftX to rightX))
            i = j + 1
        }

        val firstWin = windows.first()
        if (currentDay < firstWin.first) {
            return (firstWin.third.first - 12.0).roundToInt()
        }

        for (wIdx in windows.indices) {
            val (startD, endD, bounds) = windows[wIdx]
            if (currentDay < endD) {
                val t = ((currentDay - startD) / max(1.0, endD - startD)).coerceIn(0.0, 1.0)
                return (bounds.first + (bounds.second - bounds.first) * t).roundToInt()
            }
            if (wIdx + 1 < windows.size) {
                val nextWin = windows[wIdx + 1]
                if (currentDay < nextWin.first) {
                    val t = ((currentDay - endD) / max(1.0, nextWin.first - endD)).coerceIn(0.0, 1.0)
                    return (bounds.second + (nextWin.third.first - bounds.second) * t).roundToInt()
                }
            }
        }

        return (windows.last().third.second + 14.0).roundToInt()
    }

    private fun parseRewardIcons(m: HitoMilestone): List<RewardIconEntry> {
        val out = mutableListOf<RewardIconEntry>()
        val cmds = m.rewardCommands ?: return out
        for (raw in cmds) {
            val parts = raw.trim().removePrefix("/").split(Regex("\\s+"))
            if (parts.size >= 3 && parts[0].equals("give", ignoreCase = true)) {
                val itemStr = parts[2]
                val count = parts.getOrNull(3)?.toIntOrNull() ?: 1
                val res = ResourceLocation.tryParse(itemStr) ?: continue
                val item = BuiltInRegistries.ITEM.get(res)
                if (item != Items.AIR) {
                    out.add(RewardIconEntry(ItemStack(item), count))
                }
            }
        }
        return out
    }

    private fun formatShortDollars(dollars: Long): String {
        if (dollars >= 1000L) {
            val k = dollars / 1000.0
            return if (dollars % 1000L == 0L) "$${dollars / 1000L}k" else String.format(java.util.Locale.US, "$%.1fk", k)
        }
        return "$$dollars"
    }

    private fun viewportBounds(): IntArray {
        val tabs = availableRegionTabs()
        val headerH = if (tabs.size > 1) 56 else 38
        val footerH = 22
        return intArrayOf(6, headerH, BASE_W - 6, BASE_H - footerH)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        hoveredMilestone = null
        clickRegions.clear()

        graphics.fill(0, 0, width, height, 0x8A000000.toInt())

        val selReg = currentSelectedRegion()
        val pal = paletteFor(selReg)
        val list = milestones
        val centers = computeNodeCenters(list)
        clampCamera(list, centers)

        val localMouseX = ((mouseX - renderX) / uiScale).roundToInt()
        val localMouseY = ((mouseY - renderY) / uiScale).roundToInt()

        val progress = openingProgress()
        val eased = 1.0f - (1.0f - progress).toDouble().pow(3.0).toFloat()
        val animScale = if (animateOpening) 0.08f + eased * 0.92f else 1.0f

        graphics.pose().pushPose()
        graphics.pose().translate(renderX, renderY, 0.0f)
        graphics.pose().scale(uiScale, uiScale, 1.0f)
        if (animateOpening && animScale < 0.999f) {
            val cx = BASE_W / 2.0f
            val cy = BASE_H / 2.0f
            graphics.pose().translate(cx, cy, 0.0f)
            graphics.pose().scale(animScale, animScale, 1.0f)
            graphics.pose().translate(-cx, -cy, 0.0f)
        }

        renderPanelFrame(graphics, pal)
        renderPanelHeader(graphics, selReg, pal, localMouseX, localMouseY)

        val vp = viewportBounds()
        val viewLeft = vp[0]
        val viewTop = vp[1]
        val viewRight = vp[2]
        val viewBottom = vp[3]
        val viewCenterX = (viewLeft + viewRight) / 2.0f
        val viewCenterY = (viewTop + viewBottom) / 2.0f

        val mouseInViewport = localMouseX in viewLeft..viewRight && localMouseY in viewTop..viewBottom
        val worldMouseX = ((localMouseX - viewCenterX) / zoom - panX).roundToInt()
        val worldMouseY = ((localMouseY - viewCenterY) / zoom - panY).roundToInt()

        val scLeft = screenCoordX(viewLeft, animScale)
        val scTop = screenCoordY(viewTop, animScale)
        val scRight = screenCoordX(viewRight, animScale)
        val scBottom = screenCoordY(viewBottom, animScale)
        if (scRight > scLeft && scBottom > scTop) {
            graphics.enableScissor(scLeft, scTop, scRight, scBottom)
        }

        // Camera transform (pan + zoom) inside timeline viewport
        graphics.pose().pushPose()
        graphics.pose().translate(viewCenterX, viewCenterY, 0.0f)
        graphics.pose().scale(zoom.toFloat(), zoom.toFloat(), 1.0f)
        graphics.pose().translate(panX.toFloat(), panY.toFloat(), 0.0f)

        val timelineY = 0
        val lineStartX = if (centers.isNotEmpty()) centers.first() - cardWidth(list.first()) / 2 - 22 else -200
        val lineEndX = if (centers.isNotEmpty()) centers.last() + cardWidth(list.last()) / 2 + 26 else 200

        val showToday = shouldShowTodayMarker(selReg)
        val regionDone = isRegionCompleted(selReg)
        val currentDay = config.currentServerDay()

        // 1. Subtle phase/tramo grouping bands behind the timeline (Option A)
        val groups = computePhaseGroups(list)
        for (g in groups) {
            val gx1 = centers[g.startIdx] - cardWidth(list[g.startIdx]) / 2 - 8
            val gx2 = centers[g.endIdx] + cardWidth(list[g.endIdx]) / 2 + 8
            val gy1 = -NODE_H - 36
            val gy2 = NODE_H + 34
            graphics.fill(gx1, gy1, gx2, gy2, pal.bandBg)
            graphics.renderOutline(gx1, gy1, gx2 - gx1, gy2 - gy1, 0x44FFFFFF)
            val lblW = font.width(g.label) + 10
            val lblX = (gx1 + gx2 - lblW) / 2
            graphics.fill(lblX, gy1 - 5, lblX + lblW, gy1 + 6, pal.headerBg)
            graphics.renderOutline(lblX, gy1 - 5, lblW, 11, pal.border)
            graphics.drawCenteredString(font, g.label, lblX + lblW / 2, gy1 - 3, pal.accentLight)
        }

        // 2. Central horizontal timeline bar
        val progressWorldX = computeServerProgressWorldX(list, centers).coerceIn(lineStartX, lineEndX)
        graphics.fill(lineStartX, timelineY - 4, lineEndX, timelineY + 4, 0xFF0C0F14.toInt())
        graphics.renderOutline(lineStartX, timelineY - 4, max(4, lineEndX - lineStartX), 8, pal.border)
        if (regionDone) {
            graphics.fill(lineStartX + 1, timelineY - 3, lineEndX - 1, timelineY + 3, 0xFF15803D.toInt())
            graphics.fill(lineStartX + 1, timelineY - 2, lineEndX - 1, timelineY + 1, 0xFF4ADE80.toInt())
        } else if (showToday && progressWorldX > lineStartX) {
            graphics.fill(lineStartX + 1, timelineY - 3, progressWorldX, timelineY + 3, pal.barFill)
            graphics.fill(lineStartX + 1, timelineY - 2, progressWorldX, timelineY + 1, pal.barHighlight)
        }

        // 3. Stems, dots, and milestone cards
        val effectiveBadges = BadgesClientState.effectiveHitosBadges()
        val nextPending = HitosManager.nextPendingProgression(effectiveBadges)
        var progOrder = 0

        for ((idx, milestone) in list.withIndex()) {
            val isProgression = milestone.isProgressMilestone()
            val featured = isFeaturedCard(milestone)
            val orderTag = when {
                featured -> "INICIO"
                isProgression -> {
                    progOrder++
                    "#$progOrder"
                }
                else -> "EVENTO"
            }

            val cw = cardWidth(milestone)
            val ch = cardHeight(milestone)
            val nodeCenterX = centers[idx]
            val isAbove = idx % 2 == 0
            val nodeY = if (isAbove) timelineY - ch - 20 else timelineY + 20
            val nodeX = nodeCenterX - cw / 2

            val isUnlocked = isProgression && HitosManager.hasUnlockedMilestone(effectiveBadges, milestone)
            val endDay = config.endDayOf(milestone)
            val isOverdue = isProgression && !isUnlocked && showToday && currentDay > endDay
            val isInWindow = showToday && currentDay in milestone.dayOffset..endDay
            val isNextPending = nextPending != null && nextPending.id.equals(milestone.id, true)

            val stemColor = when {
                featured -> pal.accentLight
                !isProgression -> 0xFF38BDF8.toInt()
                isUnlocked -> 0xFF22C55E.toInt()
                isOverdue -> 0xFFEF4444.toInt()
                isInWindow -> pal.accent
                else -> pal.border
            }
            if (isAbove) {
                graphics.fill(nodeCenterX - 1, nodeY + ch, nodeCenterX + 1, timelineY - 4, stemColor)
            } else {
                graphics.fill(nodeCenterX - 1, timelineY + 4, nodeCenterX + 1, nodeY, stemColor)
            }

            graphics.fill(nodeCenterX - 5, timelineY - 5, nodeCenterX + 5, timelineY + 5, 0xFF0C0F14.toInt())
            graphics.fill(nodeCenterX - 3, timelineY - 3, nodeCenterX + 3, timelineY + 3, stemColor)

            renderMilestoneNode(
                graphics = graphics,
                milestone = milestone,
                pal = pal,
                orderTag = orderTag,
                x = nodeX,
                y = nodeY,
                w = cw,
                h = ch,
                featured = featured,
                worldMouseX = worldMouseX,
                worldMouseY = worldMouseY,
                mouseInViewport = mouseInViewport,
                isProgression = isProgression,
                isUnlocked = isUnlocked,
                isOverdue = isOverdue,
                isInWindow = isInWindow,
                isNextPending = isNextPending
            )
        }

        // 4. HOY: Dia X marker ALWAYS rendered ON TOP of all lines, stems, and cards!
        if (showToday) {
            graphics.pose().pushPose()
            graphics.pose().translate(0.0f, 0.0f, 250.0f)
            val dayLabel = "HOY: Dia $currentDay"
            val labelW = font.width(dayLabel) + 12
            val labelX = progressWorldX - labelW / 2
            val labelY = timelineY - 21
            graphics.fill(progressWorldX - 1, timelineY - 12, progressWorldX + 1, timelineY + 12, 0xFFFFFFFF.toInt())
            graphics.fill(labelX, labelY, labelX + labelW, labelY + 13, 0xFA0E121A.toInt())
            graphics.renderOutline(labelX, labelY, labelW, 13, pal.accentLight)
            graphics.drawCenteredString(font, dayLabel, labelX + labelW / 2, labelY + 3, pal.accentLight)
            graphics.pose().popPose()
        }

        graphics.pose().popPose()

        if (scRight > scLeft && scBottom > scTop) {
            graphics.disableScissor()
        }

        renderTimelineOverlays(graphics, pal, currentDay, effectiveBadges, viewLeft, viewBottom, localMouseX, localMouseY)
        renderPanelFooter(graphics, pal, currentDay, effectiveBadges)
        graphics.pose().popPose()

        if (animScale >= 0.98f) {
            hoveredMilestone?.let { m ->
                renderMilestoneTooltip(graphics, m, mouseX, mouseY)
            }
        }
    }

    private fun screenCoordX(localX: Int, animScale: Float): Int {
        val cx = BASE_W / 2.0f
        val transformed = cx + (localX - cx) * animScale
        return (renderX + transformed * uiScale).roundToInt()
    }

    private fun screenCoordY(localY: Int, animScale: Float): Int {
        val cy = BASE_H / 2.0f
        val transformed = cy + (localY - cy) * animScale
        return (renderY + transformed * uiScale).roundToInt()
    }

    private fun renderPanelFrame(graphics: GuiGraphics, pal: RegionPalette) {
        graphics.fill(0, 0, BASE_W, BASE_H, pal.panelBg)
        graphics.renderOutline(0, 0, BASE_W, BASE_H, pal.border)
        graphics.fill(0, 0, BASE_W, 2, pal.accent)
        graphics.fill(1, 2, BASE_W - 1, 37, pal.headerBg)
        graphics.fill(1, 37, BASE_W - 1, 38, pal.border)
    }

    private fun drawFlatButton(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        label: String,
        mouseX: Int,
        mouseY: Int,
        bg: Int,
        hoverBg: Int,
        border: Int,
        hoverBorder: Int,
        textColor: Int,
        onClick: () -> Unit
    ) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + h)
        graphics.fill(x, y, x + w, y + h, if (hovered) hoverBg else bg)
        graphics.renderOutline(x, y, w, h, if (hovered) hoverBorder else border)
        graphics.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, textColor)
        clickRegions.add(ClickRegion(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), inWorldSpace = false, onClick = onClick))
    }

    private fun renderPanelHeader(
        graphics: GuiGraphics,
        selReg: String,
        pal: RegionPalette,
        mouseX: Int,
        mouseY: Int
    ) {
        val btnY = 10
        val canEdit = com.desgraciamon.announcements.BulletinClientData.snapshot.state.adminMode &&
            BadgesClientState.hasAdminBadge()
        if (canEdit) {
            drawFlatButton(
                graphics, 10, btnY, 46, 18, "Editar", mouseX, mouseY,
                pal.tabIdleBg, pal.tabActiveBg, pal.border, pal.accent, 0xFFFDE68A.toInt()
            ) {
                Minecraft.getInstance().setScreen(
                    com.desgraciamon.announcements.AnnouncementsAdminScreen("hitos", this)
                )
            }
        }

        val selRegName = HitosConfig.regionDisplayName(selReg).uppercase()
        graphics.drawCenteredString(font, "HITOS: $selRegName", BASE_W / 2, 10, pal.accentLight)

        val subtitleText = if (isRegionCompleted(selReg)) {
            "Region completada"
        } else {
            val dayNow = config.currentServerDay()
            if (BadgesClientState.rctAvailable && BadgesClientState.rctSummary.contains("Tope")) {
                val cap = BadgesClientState.rctSummary.substringAfter("Tope ").trim()
                "Dia $dayNow  |  Tope $cap"
            } else {
                "Dia $dayNow"
            }
        }
        graphics.drawCenteredString(font, subtitleText, BASE_W / 2, 22, 0xFF94A3B8.toInt())

        val progMilestones = config.progressionMilestonesForRegion(selReg)
        val total = progMilestones.size
        val unlocked = progMilestones.count { HitosManager.hasUnlockedMilestone(BadgesClientState.effectiveHitosBadges(), it) }

        val barW = 76
        val barH = 12
        val barX = BASE_W - 10 - 18 - 8 - barW
        val barY = 13
        graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF0C0F14.toInt())
        graphics.renderOutline(barX, barY, barW, barH, pal.border)
        val fillW = if (total > 0) ((barW - 2) * unlocked) / total else 0
        if (fillW > 0) {
            graphics.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + barH - 1, if (unlocked >= total) 0xFF22C55E.toInt() else pal.accent)
        }
        graphics.drawCenteredString(font, "$unlocked/$total", barX + barW / 2, barY + 2, 0xFFFFFFFF.toInt())

        drawFlatButton(
            graphics, BASE_W - 28, btnY, 18, 18, "X", mouseX, mouseY,
            0xFF2A161B.toInt(), 0xFF7F1D1D.toInt(), 0xFF5C232B.toInt(), 0xFFEF4444.toInt(), 0xFFFCA5A5.toInt()
        ) {
            Minecraft.getInstance().setScreen(parent)
        }

        val tabs = availableRegionTabs()
        if (tabs.size > 1) {
            val tabW = 64
            val tabGap = 6
            val totalW = tabs.size * tabW + (tabs.size - 1) * tabGap
            var tx = (BASE_W - totalW) / 2
            val ty = 40
            for (reg in tabs) {
                val regPal = paletteFor(reg)
                val selected = reg.equals(selReg, ignoreCase = true)
                val label = HitosConfig.regionDisplayName(reg)
                val hovered = mouseX in tx..(tx + tabW) && mouseY in ty..(ty + 14)
                val bg = if (selected) regPal.tabActiveBg else if (hovered) pal.cardHoverBg else pal.tabIdleBg
                val borderCol = if (selected) regPal.accent else if (hovered) regPal.accentLight else pal.border
                val txtCol = if (selected) regPal.accentLight else 0xFF94A3B8.toInt()
                graphics.fill(tx, ty, tx + tabW, ty + 14, bg)
                graphics.renderOutline(tx, ty, tabW, 14, borderCol)
                if (selected) {
                    graphics.fill(tx + 1, ty + 12, tx + tabW - 1, ty + 13, regPal.accent)
                }
                graphics.drawCenteredString(font, label, tx + tabW / 2, ty + 3, txtCol)
                clickRegions.add(ClickRegion(tx.toFloat(), ty.toFloat(), tabW.toFloat(), 14f, inWorldSpace = false) {
                    selectedRegion = reg
                    centerCamera()
                })
                tx += tabW + tabGap
            }
        }
    }

    private fun renderTimelineOverlays(
        graphics: GuiGraphics,
        pal: RegionPalette,
        currentDay: Int,
        effectiveBadges: Collection<String>,
        viewLeft: Int,
        viewBottom: Int,
        mouseX: Int,
        mouseY: Int
    ) {
        val btnH = 16
        val btnY = viewBottom - btnH - 5

        val centerW = 58
        val centerX = (BASE_W - centerW) / 2
        drawFlatButton(
            graphics, centerX, btnY, centerW, btnH, "Centrar", mouseX, mouseY,
            pal.tabIdleBg, pal.tabActiveBg, pal.border, pal.accentLight, pal.accentLight
        ) {
            centerCamera()
        }

        val earliestOverdue = HitosManager.earliestOverdueCatchupMilestone(effectiveBadges, currentDay)
        if (earliestOverdue != null) {
            val isBoostActive = BadgesClientState.activeCatchupHitoId.isNotBlank()
            val boostLabel = earliestOverdue.formatXpBoostLabel()
            val btnText = if (isBoostActive) "Bono $boostLabel: ACTIVO" else "Activar Bono $boostLabel"
            val btnW = font.width(btnText) + 14
            val btnX = viewLeft + 6
            val bg = if (isBoostActive) 0xE614532D.toInt() else 0xE69A3412.toInt()
            val hoverBg = if (isBoostActive) 0xF516A34A.toInt() else 0xF5C2410C.toInt()
            val border = if (isBoostActive) 0xFF4ADE80.toInt() else 0xFFFB923C.toInt()
            val hoverBorder = if (isBoostActive) 0xFF86EFAC.toInt() else 0xFFFDBA74.toInt()
            drawFlatButton(
                graphics, btnX, btnY, btnW, btnH, btnText, mouseX, mouseY,
                bg, hoverBg, border, hoverBorder, 0xFFFFFFFF.toInt()
            ) {
                sendAction("toggle_catchup_xp", earliestOverdue.id)
            }
        }
    }

    private fun renderPanelFooter(
        graphics: GuiGraphics,
        pal: RegionPalette,
        currentDay: Int,
        effectiveBadges: Collection<String>
    ) {
        val footerTop = BASE_H - 22
        graphics.fill(1, footerTop, BASE_W - 1, BASE_H - 1, pal.footerBg)
        graphics.fill(1, footerTop, BASE_W - 1, footerTop + 1, pal.border)

        var lx = 10
        val ly = footerTop + 7
        val legend = listOf(
            0xFF22C55E.toInt() to "Completado",
            pal.accent to "En curso",
            0xFFEF4444.toInt() to "Atrasado"
        )
        for ((col, label) in legend) {
            graphics.fill(lx, ly, lx + 7, ly + 7, col)
            graphics.drawString(font, label, lx + 10, ly, 0xFF94A3B8.toInt(), false)
            lx += font.width(label) + 18
        }

        val activeMilestone = if (BadgesClientState.activeCatchupHitoId.isNotBlank()) {
            HitosManager.earliestOverdueCatchupMilestone(effectiveBadges, currentDay)
        } else null

        val rightNote = if (activeMilestone != null) {
            "Bono ${activeMilestone.formatXpBoostLabel()} activo: ${activeMilestone.title}"
        } else {
            "Arrastra para mover  |  Rueda para zoom (${(zoom * 100).roundToInt()}%)"
        }
        val rightColor = if (activeMilestone != null) 0xFF4ADE80.toInt() else 0xFF64748B.toInt()
        graphics.drawString(font, rightNote, BASE_W - font.width(rightNote) - 10, ly, rightColor, false)
    }

    private fun renderMilestoneNode(
        graphics: GuiGraphics,
        milestone: HitoMilestone,
        pal: RegionPalette,
        orderTag: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        featured: Boolean,
        worldMouseX: Int,
        worldMouseY: Int,
        mouseInViewport: Boolean,
        isProgression: Boolean,
        isUnlocked: Boolean,
        isOverdue: Boolean,
        isInWindow: Boolean,
        isNextPending: Boolean
    ) {
        val isHovered = mouseInViewport && worldMouseX in x..(x + w) && worldMouseY in y..(y + h)
        if (isHovered) {
            hoveredMilestone = milestone
        }

        val cleanId = milestone.id.lowercase()
        val isOnTime = cleanId in BadgesClientState.completedOnTimeHitos
        val isClaimed = cleanId in BadgesClientState.claimedHitoRewards
        val isCatchupActive = BadgesClientState.activeCatchupHitoId.equals(cleanId, true)

        val bgColor = when {
            isHovered -> pal.cardHoverBg
            featured -> 0xF2241B14.toInt()
            !isProgression -> 0xED13202B.toInt()
            isUnlocked -> 0xF014261E.toInt()
            isOverdue -> 0xED2A161A.toInt()
            isInWindow -> pal.tabActiveBg
            else -> pal.cardDefaultBg
        }
        val accentColor = when {
            featured -> 0xFFF59E0B.toInt()
            !isProgression -> 0xFF38BDF8.toInt()
            isUnlocked -> 0xFF22C55E.toInt()
            isCatchupActive -> 0xFF4ADE80.toInt()
            isOverdue -> 0xFFEF4444.toInt()
            isInWindow -> pal.accent
            else -> pal.border
        }
        val borderColor = if (isHovered) 0xFFFFFFFF.toInt() else accentColor

        graphics.fill(x, y, x + w, y + h, bgColor)
        graphics.renderOutline(x, y, w, h, borderColor)
        graphics.fill(x + 1, y + 1, x + w - 1, y + 3, accentColor)

        val slotX = x + 6
        val slotY = y + 7
        graphics.fill(slotX, slotY, slotX + 20, slotY + 20, 0xFF0C0F14.toInt())
        graphics.renderOutline(slotX, slotY, 20, 20, pal.border)
        renderBadgeItem(graphics, milestone, slotX + 2, slotY + 2)

        val tagW = font.width(orderTag) + 6
        val tagX = x + w - tagW - 5
        val tagY = y + 6
        val tagBg = if (featured) 0xFF451A03.toInt() else 0xFF0F141D.toInt()
        graphics.fill(tagX, tagY, tagX + tagW, tagY + 10, tagBg)
        graphics.drawString(font, orderTag, tagX + 3, tagY + 1, if (featured) 0xFFFDE68A.toInt() else 0xFFCBD5E1.toInt(), false)

        val leaderText = milestone.leaderOrBoss.ifBlank { milestone.subtitle }
        graphics.drawString(font, font.plainSubstrByWidth(leaderText, w - 36 - tagW), x + 30, y + 7, pal.accentLight, false)

        val titleColor = if (isUnlocked || featured) 0xFFFFFFFF.toInt() else 0xFFE2E8F0.toInt()
        graphics.drawString(font, font.plainSubstrByWidth(milestone.title, w - 36), x + 30, y + 18, titleColor, false)

        if (featured) {
            graphics.fill(x + 6, y + 31, x + w - 6, y + 32, 0x44F59E0B.toInt())
            graphics.drawString(font, font.plainSubstrByWidth("Elijan sus bases y armen sus equipos.", w - 12), x + 6, y + 36, 0xFFFDE68A.toInt(), false)
            graphics.drawString(font, font.plainSubstrByWidth("Los gimnasios inician el Dia 4.", w - 12), x + 6, y + 48, 0xFFCBD5E1.toInt(), false)
            val pillY = y + h - 16
            graphics.fill(x + 6, pillY, x + w - 6, pillY + 12, 0x66451A03.toInt())
            graphics.drawCenteredString(font, "Fase de Preparacion (Dias 1-3)", x + w / 2, pillY + 2, 0xFFFBBF24.toInt())
            return
        }

        // Compact reward icons row ($ + xN [itemIcon])
        var rx = x + 6
        val ry = y + 32
        if (milestone.rewardDollars > 0L) {
            val moneyStr = formatShortDollars(milestone.rewardDollars)
            graphics.drawString(font, moneyStr, rx, ry + 2, 0xFFFDE047.toInt(), false)
            rx += font.width(moneyStr) + 6
        }
        val icons = parseRewardIcons(milestone)
        for (entry in icons.take(3)) {
            val countStr = "x${entry.count}"
            if (rx + font.width(countStr) + 14 > x + w - 4) break
            graphics.drawString(font, countStr, rx, ry + 2, 0xFFCBD5E1.toInt(), false)
            rx += font.width(countStr) + 1
            graphics.pose().pushPose()
            graphics.pose().translate(rx.toFloat(), ry.toFloat(), 0.0f)
            graphics.pose().scale(0.65f, 0.65f, 1.0f)
            graphics.renderFakeItem(entry.stack, 0, 0)
            graphics.pose().popPose()
            rx += 13
        }

        val btnX = x + 6
        val btnY = y + h - 19
        val btnW = w - 12
        val btnH = 14

        val currentDay = config.currentServerDay()
        if (isProgression && isUnlocked && isOnTime && !isClaimed) {
            if (currentDay >= milestone.dayOffset) {
                val btnHover = mouseInViewport && worldMouseX in btnX..(btnX + btnW) && worldMouseY in btnY..(btnY + btnH)
                graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, if (btnHover) 0xFF16A34A.toInt() else 0xFF15803D.toInt())
                graphics.renderOutline(btnX, btnY, btnW, btnH, 0xFF4ADE80.toInt())
                graphics.drawCenteredString(font, "Reclamar", btnX + btnW / 2, btnY + 3, 0xFFFFFFFF.toInt())
                clickRegions.add(ClickRegion(btnX.toFloat(), btnY.toFloat(), btnW.toFloat(), btnH.toFloat(), inWorldSpace = true) {
                    sendAction("claim_hito", milestone.id)
                })
            } else {
                graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0xCC1E293B.toInt())
                graphics.renderOutline(btnX, btnY, btnW, btnH, 0xFFF59E0B.toInt())
                graphics.drawCenteredString(font, "Disponible Dia ${milestone.dayOffset}", btnX + btnW / 2, btnY + 3, 0xFFFDE68A.toInt())
            }
            return
        }

        val (statusText, statusColor) = when {
            !isProgression -> Pair("Evento", 0xFF7DD3FC.toInt())
            isUnlocked && isClaimed -> Pair("Reclamado", 0xFF4ADE80.toInt())
            isUnlocked -> Pair("Completado", 0xFF86EFAC.toInt())
            isOverdue -> Pair("Atrasado", 0xFFFCA5A5.toInt())
            isInWindow -> Pair("En plazo", pal.accentLight)
            else -> Pair("Dias ${milestone.dayOffset}-${config.endDayOf(milestone)}", 0xFF94A3B8.toInt())
        }
        graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0x660C0F14.toInt())
        graphics.drawCenteredString(font, font.plainSubstrByWidth(statusText, btnW - 4), btnX + btnW / 2, btnY + 3, statusColor)
    }

    private fun sendAction(action: String, hitoId: String) {
        if (ClientPlayNetworking.canSend(BadgeTogglePayload.TYPE)) {
            ClientPlayNetworking.send(BadgeTogglePayload(action, hitoId))
        }
    }

    private fun playClick() {
        minecraft?.soundManager?.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    private fun renderBadgeItem(graphics: GuiGraphics, milestone: HitoMilestone, x: Int, y: Int) {
        val itemId = milestone.effectiveIconItemId()
        if (itemId.isNotBlank()) {
            val res = ResourceLocation.tryParse(itemId)
            if (res != null) {
                val item = BuiltInRegistries.ITEM.get(res)
                if (item != Items.AIR) {
                    graphics.renderFakeItem(ItemStack(item), x, y)
                    return
                }
            }
        }
        graphics.drawString(font, "*", x + 5, y + 4, 0xFFFFD700.toInt(), false)
    }

    private fun renderMilestoneTooltip(graphics: GuiGraphics, m: HitoMilestone, mouseX: Int, mouseY: Int) {
        val lines = mutableListOf<Component>()
        lines.add(Component.literal("§6§l${m.title}"))
        val rolePrefix = if (m.isProgressMilestone()) "Lider" else "Fase"
        lines.add(Component.literal("§e$rolePrefix: §f${m.leaderOrBoss}"))
        lines.add(Component.literal("§aPlazo: §f${m.dateRange}"))
        if (m.description.isNotBlank()) {
            lines.add(Component.empty())
            lines.add(Component.literal("§7${m.description}"))
        }
        if (m.isProgressMilestone() && m.rewardSummary.isNotBlank()) {
            lines.add(Component.empty())
            lines.add(Component.literal("§bRecompensa a tiempo: §f${m.rewardSummary}"))
        }

        val currentDay = config.currentServerDay()
        val endDay = config.endDayOf(m)
        val cleanId = m.id.lowercase()
        val isUnlocked = m.isProgressMilestone() && HitosManager.hasUnlockedMilestone(BadgesClientState.effectiveHitosBadges(), m)
        val status = when {
            isFeaturedCard(m) -> "§eEstado: Preparacion inicial (Dias 1-3)"
            !m.isProgressMilestone() -> "§bEstado: Evento opcional"
            isUnlocked && cleanId in BadgesClientState.claimedHitoRewards -> "§aEstado: Completado (Recompensa reclamada)"
            isUnlocked && cleanId in BadgesClientState.completedOnTimeHitos && currentDay < m.dayOffset ->
                "§eEstado: Completado (Recompensa disponible Dia ${m.dayOffset})"
            isUnlocked && cleanId in BadgesClientState.completedOnTimeHitos -> "§aEstado: Completado a tiempo (Clic en Reclamar)"
            isUnlocked -> "§aEstado: Completado"
            currentDay > endDay && m.hasCatchupXpBoost() && BadgesClientState.activeCatchupHitoId.equals(cleanId, true) -> "§aEstado: Atrasado - Bono ${m.formatXpBoostLabel()} ACTIVO"
            currentDay > endDay && m.hasCatchupXpBoost() -> "§cEstado: Atrasado (Usa el boton de Bono XP en la esquina)"
            currentDay > endDay -> "§cEstado: Atrasado"
            else -> "§7Estado: En plazo / Pendiente"
        }
        lines.add(Component.empty())
        lines.add(Component.literal(status))

        graphics.renderComponentTooltip(font, lines, mouseX, mouseY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (animateOpening && openingProgress() < 1.0f) return true
        val lx = (mouseX - renderX) / uiScale
        val ly = (mouseY - renderY) / uiScale
        val vp = viewportBounds()
        val viewCenterX = (vp[0] + vp[2]) / 2.0
        val viewCenterY = (vp[1] + vp[3]) / 2.0
        val wx = (lx - viewCenterX) / zoom - panX
        val wy = (ly - viewCenterY) / zoom - panY
        val inViewport = lx >= vp[0] && lx <= vp[2] && ly >= vp[1] && ly <= vp[3]

        if (button == 0) {
            for (region in clickRegions) {
                if (!region.inWorldSpace && region.contains(lx, ly)) {
                    playClick()
                    region.onClick()
                    return true
                }
            }
            if (inViewport) {
                for (region in clickRegions) {
                    if (region.inWorldSpace && region.contains(wx, wy)) {
                        playClick()
                        region.onClick()
                        return true
                    }
                }
                isDragging = true
                dragStartX = lx
                dragStartY = ly
                dragStartPanX = panX
                dragStartPanY = panY
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        isDragging = false
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (isDragging) {
            val lx = (mouseX - renderX) / uiScale
            val ly = (mouseY - renderY) / uiScale
            panX = dragStartPanX + (lx - dragStartX) / zoom
            panY = dragStartPanY + (ly - dragStartY) / zoom
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        zoom = (zoom + scrollY * 0.09).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            Minecraft.getInstance().setScreen(parent)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun isPauseScreen(): Boolean = false
}
