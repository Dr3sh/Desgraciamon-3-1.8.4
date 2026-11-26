package com.desgraciamon.announcements

import com.desgraciamon.badges.GymBadges
import com.desgraciamon.badges.HitoMilestone
import com.desgraciamon.badges.HitosManager
import com.desgraciamon.badges.HitosTimelineScreen
import com.google.gson.JsonObject
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class AnnouncementsAdminScreen(
    initialTab: String = "announcements",
    private val parentScreen: Screen? = null
) : Screen(Component.literal("Panel de Administración de Anuncios, Misiones e Hitos")) {

    override fun renderBlurredBackground(delta: Float) {}
    override fun renderMenuBackground(context: GuiGraphics) {}
    override fun renderBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {}
    override fun renderTransparentBackground(graphics: GuiGraphics) {}
    override fun isPauseScreen(): Boolean = false

    private var activeTab: String = when {
        initialTab.equals("missions", ignoreCase = true) -> "missions"
        initialTab.equals("hitos", ignoreCase = true) -> "hitos"
        else -> "announcements"
    }

    // Edit state
    private var editingAnnouncement: Announcement? = null
    private var editingMission: Mission? = null
    private var editingHito: HitoMilestone? = null
    private var editingHitoOriginalId: String = ""
    private var isCreatingNew = false

    // Form inputs for Announcement
    private var annIdBox: EditBox? = null
    private var annTitleBox: EditBox? = null
    private var annBodyBox: EditBox? = null
    private var annColor: String = "#F5C842"
    private var annBodyColor: String = "#FFFFFF"
    private var annShowChat: Boolean = true
    private var annShowHud: Boolean = true
    private var annHudDuration: Int = 15

    // Form inputs for Mission
    private var misIdBox: EditBox? = null
    private var misTitleBox: EditBox? = null
    private var misBodyBox: EditBox? = null
    private var misPublishAtBox: EditBox? = null
    private var misExpirationBox: EditBox? = null
    private var misColor: String = "#38BDF8"
    private var misBodyColor: String = "#E6EDF3"
    private var misIsGuild: Boolean = false
    private var misTargetUsesMemberCount: Boolean = false
    private var misFrequency: Int = 0 // 0 = Permanent, 1 = Daily, 2 = Daily Repeatable
    private var misCategoryTag: String = ""
    private var misDailySlot: Int = 0
    private var misMoneyReward: Long = 0L
    private val misObjectives = mutableListOf<MissionObjective>()
    private val misItemRewards = mutableListOf<ItemMissionReward>()
    private val misPokemonRewards = mutableListOf<PokemonMissionReward>()

    // EditBoxes for dynamic rows in Mission
    private val objectiveTargetBoxes = mutableListOf<EditBox>()
    private val rewardItemBoxes = mutableListOf<EditBox>()
    private val rewardPokemonBoxes = mutableListOf<EditBox>()

    // Global settings inputs for Hitos tab
    private var hitosHeaderBox: EditBox? = null
    private var hitosStartDateBox: EditBox? = null
    private var hitosHeaderDraft: String = HitosManager.config.headerTitle
    private var hitosStartDateDraft: String = HitosManager.config.serverStartDate
    private var hitosManualDayDraft: Int = HitosManager.config.manualCurrentDay
    private var hitosGlobalStatusMsg: String = ""
    private var hitosGlobalStatusColor: Int = 0xFF78DCA9.toInt()

    // Form inputs for Hito Milestone
    private var hitoIdBox: EditBox? = null
    private var hitoTitleBox: EditBox? = null
    private var hitoLeaderBox: EditBox? = null
    private var hitoSubtitleBox: EditBox? = null
    private var hitoDayBox: EditBox? = null
    private var hitoIconBox: EditBox? = null
    private var hitoDescBox: EditBox? = null
    private var hitoRewardSummaryBox: EditBox? = null
    private var hitoDollarsBox: EditBox? = null
    private var hitoDurationDays: Int = 2
    private var hitoProgression: Boolean = true
    private var hitoBadgeDefId: String = ""
    private var hitoBadgeItemId: String = ""
    private var hitoAllowCatchupXp: Boolean = true
    private var hitoCatchupMultiplier: Double = 1.5
    private val hitoRewardCommands = mutableListOf<String>()
    private val hitoCmdBoxes = mutableListOf<EditBox>()
    private var hitoCmdScroll: Int = 0
    private var hitoPreviewState: Int = 0 // 0=En curso, 1=Completado, 2=Atrasado (Boost), 3=Proximo
    private var hitoStatusMsg: String = ""

    private var scrollRow = 0
    private var maxScrollRow = 0

    // --- Dropdown Management ---
    private data class DropdownOption<T>(val label: String, val value: T)
    private class ActiveDropdown<T>(
        val x: Int,
        val y: Int,
        val w: Int,
        val options: List<DropdownOption<T>>,
        val onSelect: (T) -> Unit
    ) {
        var scrollOffset: Int = 0
        val optH = 18

        fun maxVisible(screenHeight: Int): Int {
            val maxByScreen = maxOf(4, (screenHeight - 24) / optH)
            return minOf(options.size, minOf(9, maxByScreen))
        }

        fun effectiveTop(screenHeight: Int): Int {
            val visCount = maxVisible(screenHeight)
            val totalH = visCount * optH
            return y.coerceIn(6, maxOf(6, screenHeight - totalH - 6))
        }
    }
    private var openDropdown: ActiveDropdown<*>? = null

    companion object {
        val POKEMON_TYPES = listOf(
            "fire" to "Fuego", "water" to "Agua", "grass" to "Planta", "electric" to "Eléctrico",
            "normal" to "Normal", "ice" to "Hielo", "fighting" to "Lucha", "poison" to "Veneno",
            "ground" to "Tierra", "flying" to "Volador", "psychic" to "Psíquico", "bug" to "Bicho",
            "rock" to "Roca", "ghost" to "Fantasma", "dragon" to "Dragón", "dark" to "Siniestro",
            "steel" to "Acero", "fairy" to "Hada"
        )

        val CURATED_HITO_ICONS = listOf(
            "minecraft:nether_star" to "Estrella del Nether (Progreso)",
            "minecraft:clock" to "Reloj (Evento)",
            "cobblemon:rare_candy" to "Caramelo Raro",
            "cobblemon:master_ball" to "Master Ball",
            "cobblemon:poke_ball" to "Poke Ball",
            "cobblemon:ultra_ball" to "Ultra Ball",
            "minecraft:dragon_egg" to "Huevo de Dragon",
            "minecraft:totem_of_undying" to "Totem de Inmortalidad",
            "minecraft:beacon" to "Faro",
            "minecraft:diamond" to "Diamante",
            "minecraft:emerald" to "Esmeralda",
            "minecraft:gold_ingot" to "Lingote de Oro",
            "minecraft:enchanted_golden_apple" to "Manzana Dorada Encantada",
            "minecraft:trophy" to "Trofeo"
        )
    }

    override fun init() {
        super.init()
        if (activeTab == "hitos" && editingHito == null) {
            if (Minecraft.getInstance().hasSingleplayerServer() || HitosManager.config.milestones.isEmpty()) {
                HitosManager.load()
            }
            refreshGlobalHitosDraftFromConfig()
        }
        rebuildUi()
    }

    fun refreshHitosFromSync() {
        if (activeTab == "hitos" && editingHito == null) {
            refreshGlobalHitosDraftFromConfig()
            rebuildUi()
        }
    }

    private fun refreshGlobalHitosDraftFromConfig() {
        hitosHeaderDraft = HitosManager.config.headerTitle
        hitosStartDateDraft = HitosManager.config.serverStartDate
        hitosManualDayDraft = HitosManager.config.manualCurrentDay
    }

    private fun panelGeometry(): IntArray {
        val isHitoEditor = editingHito != null
        val panelW = if (isHitoEditor) minOf(width - 14, 576) else minOf(width - 20, 560)
        val panelH = if (isHitoEditor) minOf(height - 14, 392) else minOf(height - 20, 396)
        val left = (width - panelW) / 2
        val top = (height - panelH) / 2
        return intArrayOf(left, top, panelW, panelH)
    }

    private fun rebuildUi() {
        clearWidgets()
        openDropdown = null
        objectiveTargetBoxes.clear()
        rewardItemBoxes.clear()
        rewardPokemonBoxes.clear()
        hitoCmdBoxes.clear()
        annIdBox = null
        annTitleBox = null
        annBodyBox = null
        misIdBox = null
        misTitleBox = null
        misBodyBox = null
        misPublishAtBox = null
        misExpirationBox = null
        hitosHeaderBox = null
        hitosStartDateBox = null
        hitoIdBox = null
        hitoTitleBox = null
        hitoLeaderBox = null
        hitoSubtitleBox = null
        hitoDayBox = null
        hitoIconBox = null
        hitoDescBox = null
        hitoRewardSummaryBox = null
        hitoDollarsBox = null

        val geom = panelGeometry()
        val left = geom[0]
        val top = geom[1]
        val panelW = geom[2]
        val panelH = geom[3]

        if (editingAnnouncement != null) {
            buildAnnouncementEditor(left, top, panelW, panelH)
            return
        }

        if (editingMission != null) {
            buildMissionEditor(left, top, panelW, panelH)
            return
        }

        if (editingHito != null) {
            buildHitoEditor(left, top, panelW, panelH)
            return
        }

        // --- Main Tabs & Actions ---
        val compactTop = panelW < 500
        val tabAnnX = if (compactTop) left + 8 else left + 10
        val tabAnnW = if (compactTop) 72 else 86
        val tabMisX = tabAnnX + tabAnnW + if (compactTop) 3 else 4
        val tabMisW = if (compactTop) 72 else 86
        val tabHitX = tabMisX + tabMisW + if (compactTop) 3 else 4
        val tabHitW = if (compactTop) 58 else 72

        addRenderableWidget(
            Button.builder(Component.literal(if (activeTab == "announcements") "[Anuncios]" else "Anuncios")) {
                activeTab = "announcements"
                scrollRow = 0
                hitosGlobalStatusMsg = ""
                rebuildUi()
            }.bounds(tabAnnX, top + 4, tabAnnW, 18).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal(if (activeTab == "missions") "[Misiones]" else "Misiones")) {
                activeTab = "missions"
                scrollRow = 0
                hitosGlobalStatusMsg = ""
                rebuildUi()
            }.bounds(tabMisX, top + 4, tabMisW, 18).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal(if (activeTab == "hitos") "[Hitos]" else "Hitos")) {
                activeTab = "hitos"
                scrollRow = 0
                refreshGlobalHitosDraftFromConfig()
                hitosGlobalStatusMsg = ""
                rebuildUi()
            }.bounds(tabHitX, top + 4, tabHitW, 18).build()
        )

        val closeW = if (compactTop) 22 else 24
        val closeX = left + panelW - (if (compactTop) 8 else 10) - closeW

        if (activeTab == "hitos") {
            val newHitoW = if (compactTop) 86 else 90
            val newHitoX = closeX - 4 - newHitoW
            val cronW = if (compactTop) 88 else 92
            val cronX = newHitoX - 4 - cronW
            val cronLabel = if (compactTop) "Cronograma" else "Ver Cronograma"
            addRenderableWidget(
                Button.builder(Component.literal(cronLabel)) {
                    minecraft?.setScreen(HitosTimelineScreen(this))
                }.bounds(cronX, top + 4, cronW, 18).build()
            )
            addRenderableWidget(
                Button.builder(Component.literal("+ Nuevo Hito")) {
                    val nextDay = (HitosManager.config.milestones.maxOfOrNull {
                        HitosManager.config.endDayOf(it) + 1
                    } ?: 1).coerceAtLeast(1)
                    val idx = HitosManager.config.milestones.size + 1
                    startEditHito(
                        HitoMilestone(
                            id = "hito_$idx",
                            title = "Nuevo Hito $idx",
                            subtitle = "Hito de Progreso",
                            leaderOrBoss = "Lider",
                            dayOffset = nextDay,
                            durationDays = 2,
                            description = "Completa este desafio antes de que finalice el plazo para obtener la recompensa a tiempo.",
                            rewardSummary = "2x Caramelo Raro + 1,000 CobbleDollars",
                            rewardCommands = mutableListOf("give {player} cobblemon:rare_candy 2"),
                            rewardDollars = 1000L,
                            badgeItemId = "minecraft:nether_star",
                            badgeDefId = "hito_$idx",
                            iconItemId = "minecraft:nether_star",
                            progression = true,
                            allowCatchupXp = true,
                            catchupXpMultiplier = 1.5
                        ),
                        isNew = true
                    )
                }.bounds(newHitoX, top + 4, newHitoW, 18).build()
            )
        } else {
            val createLabel = if (activeTab == "announcements") "+ Nuevo Anuncio" else "+ Nueva Misión"
            val createW = 102
            val createX = closeX - 4 - createW
            addRenderableWidget(
                Button.builder(Component.literal(createLabel)) {
                    if (activeTab == "announcements") {
                        startEditAnnouncement(
                            Announcement(
                                id = "ann_" + UUID.randomUUID().toString().take(6),
                                title = "Nuevo Anuncio",
                                body = "Descripción del anuncio"
                            ),
                            isNew = true
                        )
                    } else {
                        startEditMission(
                            Mission(
                                id = "mis_" + UUID.randomUUID().toString().take(6),
                                title = "Nueva Misión",
                                body = "Detalles y descripción limpia de la misión",
                                objectives = mutableListOf(MissionObjective(type = ObjectiveType.GET_ITEM, itemId = "minecraft:diamond", amount = 1))
                            ),
                            isNew = true
                        )
                    }
                }.bounds(createX, top + 4, createW, 18).build()
            )
        }

        // Volver / Cerrar
        addRenderableWidget(
            Button.builder(Component.literal("X")) {
                closeToParent()
            }.bounds(closeX, top + 4, closeW, 18).build()
        )

        if (activeTab == "hitos") {
            buildHitosListTab(left, top, panelW, panelH)
            return
        }

        // Listado de Anuncios o Misiones
        val listTop = top + 36
        val itemH = 34
        val visibleRows = maxOf(1, (panelH - 46) / itemH)
        val snapshot = BulletinClientData.snapshot

        if (activeTab == "announcements") {
            val list = snapshot.document.announcements
            maxScrollRow = maxOf(0, list.size - visibleRows)
            scrollRow = scrollRow.coerceIn(0, maxScrollRow)
            list.drop(scrollRow).take(visibleRows).forEachIndexed { idx, ann ->
                val itemY = listTop + idx * itemH
                addRenderableWidget(
                    Button.builder(Component.literal("Ed")) {
                        startEditAnnouncement(ann.copy(), isNew = false)
                    }.bounds(left + panelW - 68, itemY + 6, 26, 20).build()
                )
                addRenderableWidget(
                    Button.builder(Component.literal("X")) {
                        ClientPlayNetworking.send(BulletinAdminActionPayload("deleteAnnouncement", ann.id))
                        snapshot.document.announcements.removeIf { it.id == ann.id }
                        rebuildUi()
                    }.bounds(left + panelW - 38, itemY + 6, 26, 20).build()
                )
            }
        } else {
            val list = snapshot.document.missions
            maxScrollRow = maxOf(0, list.size - visibleRows)
            scrollRow = scrollRow.coerceIn(0, maxScrollRow)
            list.drop(scrollRow).take(visibleRows).forEachIndexed { idx, mis ->
                val itemY = listTop + idx * itemH
                addRenderableWidget(
                    Button.builder(Component.literal("Ed")) {
                        startEditMission(mis.copy(), isNew = false)
                    }.bounds(left + panelW - 68, itemY + 6, 26, 20).build()
                )
                addRenderableWidget(
                    Button.builder(Component.literal("X")) {
                        ClientPlayNetworking.send(BulletinAdminActionPayload("deleteMission", mis.id))
                        snapshot.document.missions.removeIf { it.id == mis.id }
                        rebuildUi()
                    }.bounds(left + panelW - 38, itemY + 6, 26, 20).build()
                )
            }
        }
    }

    private fun closeToParent() {
        when {
            parentScreen != null -> minecraft?.setScreen(parentScreen)
            activeTab == "hitos" -> minecraft?.setScreen(HitosTimelineScreen(null))
            else -> minecraft?.setScreen(AnnouncementsScreen(activeTab == "missions"))
        }
    }

    // ==================== TAB: HITOS LIST & GLOBAL CONFIG ====================

    private fun syncHitosGlobalFromBoxes() {
        hitosHeaderBox?.value?.let { hitosHeaderDraft = it }
        hitosStartDateBox?.value?.let { hitosStartDateDraft = it }
    }

    private data class HitosHeaderGeom(
        val titleX: Int,
        val titleW: Int,
        val d1LblX: Int,
        val d1X: Int,
        val d1W: Int,
        val hoyBtnX: Int,
        val hoyW: Int,
        val manX: Int,
        val dayBtnX: Int,
        val dayBtnW: Int,
        val plusX: Int,
        val saveX: Int,
        val saveW: Int,
        val saveLabel: String
    )

    private fun hitosHeaderGeometry(left: Int, panelW: Int): HitosHeaderGeom {
        val compact = panelW < 500
        val saveW = if (compact) 60 else 86
        val saveLabel = if (compact) "Guardar" else "Guardar Global"
        val saveX = left + panelW - 10 - saveW
        val plusX = saveX - 4 - 14
        val dayBtnW = if (compact) 48 else 56
        val dayBtnX = plusX - 2 - dayBtnW
        val manX = dayBtnX - 2 - 14
        val hoyW = 26
        val hoyBtnX = manX - 4 - hoyW
        val d1W = if (compact) 64 else 72
        val d1X = hoyBtnX - 3 - d1W
        val d1LblX = d1X - 30
        val titleX = left + 46
        val titleW = (d1LblX - 4 - titleX).coerceAtLeast(40)
        return HitosHeaderGeom(
            titleX = titleX,
            titleW = titleW,
            d1LblX = d1LblX,
            d1X = d1X,
            d1W = d1W,
            hoyBtnX = hoyBtnX,
            hoyW = hoyW,
            manX = manX,
            dayBtnX = dayBtnX,
            dayBtnW = dayBtnW,
            plusX = plusX,
            saveX = saveX,
            saveW = saveW,
            saveLabel = saveLabel
        )
    }

    private fun buildHitosListTab(left: Int, top: Int, panelW: Int, panelH: Int) {
        val barTop = top + 30
        val g = hitosHeaderGeometry(left, panelW)

        // Row 1 of Global bar: Title, Day 1, Manual Day, Save Global
        hitosHeaderBox = EditBox(font, g.titleX, barTop + 3, g.titleW, 16, Component.literal("Titulo Cronograma")).apply {
            setMaxLength(80)
            value = hitosHeaderDraft
        }
        addRenderableWidget(hitosHeaderBox!!)

        hitosStartDateBox = EditBox(font, g.d1X, barTop + 3, g.d1W, 16, Component.literal("Dia 1")).apply {
            setMaxLength(16)
            value = hitosStartDateDraft
        }
        addRenderableWidget(hitosStartDateBox!!)

        addRenderableWidget(
            Button.builder(Component.literal("Hoy")) {
                syncHitosGlobalFromBoxes()
                hitosStartDateDraft = LocalDate.now().toString()
                rebuildUi()
            }.bounds(g.hoyBtnX, barTop + 3, g.hoyW, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("-")) {
                syncHitosGlobalFromBoxes()
                hitosManualDayDraft = maxOf(0, hitosManualDayDraft - 1)
                rebuildUi()
            }.bounds(g.manX, barTop + 3, 14, 16).build()
        )

        val dayLabel = if (hitosManualDayDraft == 0) {
            "Dia: Auto"
        } else {
            "Dia: $hitosManualDayDraft"
        }
        addRenderableWidget(
            Button.builder(Component.literal(dayLabel)) {
                syncHitosGlobalFromBoxes()
                hitosManualDayDraft = if (hitosManualDayDraft == 0) HitosManager.config.currentServerDay() else 0
                rebuildUi()
            }.bounds(g.dayBtnX, barTop + 3, g.dayBtnW, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("+")) {
                syncHitosGlobalFromBoxes()
                hitosManualDayDraft = (hitosManualDayDraft + 1).coerceAtMost(365)
                rebuildUi()
            }.bounds(g.plusX, barTop + 3, 14, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal(g.saveLabel)) {
                syncHitosGlobalFromBoxes()
                try {
                    LocalDate.parse(hitosStartDateDraft.trim())
                } catch (_: Exception) {
                    hitosGlobalStatusMsg = "Fecha Dia 1 invalida (usa YYYY-MM-DD)"
                    hitosGlobalStatusColor = 0xFFEF4444.toInt()
                    return@builder
                }
                val payload = JsonObject().apply {
                    addProperty("headerTitle", hitosHeaderDraft.trim().ifBlank { HitosManager.config.headerTitle })
                    addProperty("serverStartDate", hitosStartDateDraft.trim())
                    addProperty("manualCurrentDay", hitosManualDayDraft.coerceAtLeast(0))
                }
                ClientPlayNetworking.send(BulletinAdminActionPayload("saveHitosGlobal", payload.toString()))
                HitosManager.config.headerTitle = hitosHeaderDraft.trim().ifBlank { HitosManager.config.headerTitle }
                HitosManager.config.serverStartDate = hitosStartDateDraft.trim()
                HitosManager.config.manualCurrentDay = hitosManualDayDraft.coerceAtLeast(0)
                HitosManager.config.recalculateDates()
                hitosGlobalStatusMsg = "Cronograma global actualizado y sincronizado."
                hitosGlobalStatusColor = 0xFF78DCA9.toInt()
                rebuildUi()
            }.bounds(g.saveX, barTop + 3, g.saveW, 16).build()
        )

        // Listado de Hitos
        val listTop = top + 72
        val itemH = 34
        val visibleRows = maxOf(1, (panelH - 82) / itemH)
        val milestones = HitosManager.config.milestones
        maxScrollRow = maxOf(0, milestones.size - visibleRows)
        scrollRow = scrollRow.coerceIn(0, maxScrollRow)

        milestones.drop(scrollRow).take(visibleRows).forEachIndexed { idx, milestone ->
            val itemY = listTop + idx * itemH
            val bx = left + panelW - 140

            // -1 dia
            addRenderableWidget(
                Button.builder(Component.literal("-1d")) {
                    val copy = copyMilestone(milestone)
                    copy.dayOffset = maxOf(1, copy.dayOffset - 1)
                    sendSaveHitoMilestone(copy.id, copy)
                    rebuildUi()
                }.bounds(bx, itemY + 5, 24, 18).build()
            )
            // +1 dia
            addRenderableWidget(
                Button.builder(Component.literal("+1d")) {
                    val copy = copyMilestone(milestone)
                    copy.dayOffset = (copy.dayOffset + 1).coerceAtMost(365)
                    sendSaveHitoMilestone(copy.id, copy)
                    rebuildUi()
                }.bounds(bx + 26, itemY + 5, 24, 18).build()
            )
            // Editar visualmente
            addRenderableWidget(
                Button.builder(Component.literal("Ed")) {
                    startEditHito(copyMilestone(milestone), isNew = false)
                }.bounds(bx + 52, itemY + 5, 24, 18).build()
            )
            // Duplicar
            addRenderableWidget(
                Button.builder(Component.literal("Dup")) {
                    val copy = copyMilestone(milestone)
                    var candidateId = "${copy.id}_copia"
                    var suffix = 2
                    while (HitosManager.config.milestones.any { it.id.equals(candidateId, true) }) {
                        candidateId = "${copy.id}_$suffix"
                        suffix++
                    }
                    val cloned = copy.copy(
                        id = candidateId,
                        title = "${copy.title} (Copia)",
                        dayOffset = copy.dayOffset + copy.durationDays,
                        rewardCommands = copy.rewardCommands?.toMutableList() ?: mutableListOf()
                    )
                    startEditHito(cloned, isNew = true)
                }.bounds(bx + 78, itemY + 5, 28, 18).build()
            )
            // Eliminar
            addRenderableWidget(
                Button.builder(Component.literal("X")) {
                    ClientPlayNetworking.send(BulletinAdminActionPayload("deleteHitoMilestone", milestone.id))
                    HitosManager.config.milestones.removeIf { it.id.equals(milestone.id, true) }
                    HitosManager.config.recalculateDates()
                    hitosGlobalStatusMsg = "Hito '${milestone.title}' eliminado."
                    hitosGlobalStatusColor = 0xFFFCA5A5.toInt()
                    rebuildUi()
                }.bounds(bx + 108, itemY + 5, 22, 18).build()
            )
        }
    }

    private fun copyMilestone(m: HitoMilestone): HitoMilestone {
        return m.copy(
            rewardCommands = m.rewardCommands?.toMutableList() ?: mutableListOf()
        ).also { it.normalize() }
    }

    private fun sendSaveHitoMilestone(originalId: String, updated: HitoMilestone) {
        val payload = JsonObject().apply {
            addProperty("originalId", originalId)
            add("milestone", BulletinJson.gson.toJsonTree(updated))
        }
        ClientPlayNetworking.send(BulletinAdminActionPayload("saveHitoMilestone", payload.toString()))

        updated.normalize()
        if (originalId.isNotBlank() && !originalId.equals(updated.id, true)) {
            HitosManager.config.milestones.removeIf { it.id.equals(originalId, true) }
        }
        val list = HitosManager.config.milestones
        val idx = list.indexOfFirst { it.id.equals(updated.id, true) }
        if (idx >= 0) list[idx] = updated else list.add(updated)
        HitosManager.config.recalculateDates()
    }

    // ==================== VISUAL HITO EDITOR ====================

    private fun startEditHito(m: HitoMilestone, isNew: Boolean) {
        val copy = copyMilestone(m)
        editingHito = copy
        editingHitoOriginalId = if (isNew) "" else m.id
        isCreatingNew = isNew
        hitoDurationDays = copy.durationDays.coerceAtLeast(1)
        hitoProgression = copy.isProgressMilestone()
        hitoBadgeDefId = copy.badgeDefId
        hitoBadgeItemId = copy.badgeItemId
        hitoAllowCatchupXp = copy.allowCatchupXp ?: true
        hitoCatchupMultiplier = (copy.catchupXpMultiplier ?: 1.5).coerceIn(1.0, 10.0)
        hitoRewardCommands.clear()
        hitoRewardCommands.addAll(copy.rewardCommands.orEmpty())
        hitoCmdScroll = 0
        hitoStatusMsg = ""
        rebuildUi()
    }

    private fun syncHitoDraftFromBoxes() {
        val current = editingHito ?: return
        hitoIdBox?.value?.let { current.id = it }
        hitoTitleBox?.value?.let { current.title = it }
        hitoLeaderBox?.value?.let { current.leaderOrBoss = it }
        hitoSubtitleBox?.value?.let { current.subtitle = it }
        hitoDayBox?.value?.toIntOrNull()?.let { current.dayOffset = it.coerceIn(1, 365) }
        hitoIconBox?.value?.let { current.iconItemId = it }
        hitoDescBox?.value?.let { current.description = it }
        hitoRewardSummaryBox?.value?.let { current.rewardSummary = it }
        hitoDollarsBox?.value?.replace(Regex("[^0-9]"), "")?.toLongOrNull()?.let { current.rewardDollars = it }
        current.durationDays = hitoDurationDays
        current.progression = hitoProgression
        current.badgeDefId = hitoBadgeDefId
        current.badgeItemId = hitoBadgeItemId
        current.allowCatchupXp = hitoAllowCatchupXp
        current.catchupXpMultiplier = hitoCatchupMultiplier
        current.rewardCommands = hitoRewardCommands.toMutableList()
    }

    private fun buildDraftHitoMilestone(): HitoMilestone {
        val base = editingHito ?: HitoMilestone(id = "hito", title = "Hito", subtitle = "", leaderOrBoss = "")
        val idVal = (hitoIdBox?.value ?: base.id).trim().ifBlank { "hito" }
        val titleVal = hitoTitleBox?.value ?: base.title
        val leaderVal = hitoLeaderBox?.value ?: base.leaderOrBoss
        val subVal = hitoSubtitleBox?.value ?: base.subtitle
        val dayVal = (hitoDayBox?.value?.toIntOrNull() ?: base.dayOffset).coerceIn(1, 365)
        val iconVal = (hitoIconBox?.value ?: base.iconItemId).trim()
        val descVal = hitoDescBox?.value ?: base.description
        val sumVal = hitoRewardSummaryBox?.value ?: base.rewardSummary
        val dollarsVal = hitoDollarsBox?.value?.replace(Regex("[^0-9]"), "")?.toLongOrNull() ?: base.rewardDollars

        return HitoMilestone(
            id = idVal,
            title = titleVal,
            subtitle = subVal,
            leaderOrBoss = leaderVal,
            dayOffset = dayVal,
            durationDays = hitoDurationDays.coerceAtLeast(1),
            dateRange = HitosManager.config.previewDateRange(dayVal, hitoDurationDays),
            startDate = base.startDate,
            endDate = base.endDate,
            description = descVal,
            rewardSummary = sumVal,
            rewardCommands = hitoRewardCommands.map { it.trim() }.filter { it.isNotBlank() }.toMutableList(),
            rewardDollars = dollarsVal.coerceAtLeast(0L),
            badgeItemId = if (hitoProgression) hitoBadgeItemId else "",
            badgeDefId = if (hitoProgression) hitoBadgeDefId else "",
            iconItemId = iconVal,
            iconEmoji = "",
            progression = hitoProgression,
            allowCatchupXp = hitoAllowCatchupXp,
            catchupXpMultiplier = hitoCatchupMultiplier.coerceIn(1.0, 10.0),
            isCompleted = base.isCompleted
        )
    }

    private fun buildHitoEditor(left: Int, top: Int, panelW: Int, panelH: Int) {
        val hito = editingHito ?: return

        val previewW = 180
        val previewX = left + panelW - previewW - 10
        val formX = left + 10
        val formW = panelW - previewW - 28
        val bodyTop = top + 30

        // Row 0: ID | Importante SI/NO | Medalla dropdown
        hitoIdBox = EditBox(font, formX + 20, bodyTop + 2, 76, 16, Component.literal("ID")).apply {
            setMaxLength(48)
            value = hito.id
        }
        addRenderableWidget(hitoIdBox!!)

        val impLabel = if (hitoProgression) "Importante: SI" else "Importante: NO"
        addRenderableWidget(
            Button.builder(Component.literal(impLabel)) {
                syncHitoDraftFromBoxes()
                hitoProgression = !hitoProgression
                rebuildUi()
            }.bounds(formX + 100, bodyTop + 2, 108, 16).build()
        )

        val currentBadgeName = if (hitoBadgeDefId.isBlank()) {
            "Sin Medalla"
        } else {
            GymBadges.BY_BADGE_ID[hitoBadgeDefId.lowercase()]?.name ?: hitoBadgeDefId
        }
        val medallaW = (formW - 212).coerceAtLeast(80)
        addRenderableWidget(
            Button.builder(Component.literal("Med: ${currentBadgeName.take(15)} v")) {
                syncHitoDraftFromBoxes()
                val opts = mutableListOf<DropdownOption<String>>(
                    DropdownOption("Ninguna (Libre / Evento)", "")
                )
                for (b in GymBadges.ALL_GYM_BADGES) {
                    opts.add(DropdownOption("${b.region}: ${b.name} (${b.gymLeaderName})", b.id))
                }
                openDropdown = ActiveDropdown(formX + 212, bodyTop + 19, maxOf(190, medallaW), opts) { chosenId ->
                    val currentDraft = editingHito ?: return@ActiveDropdown
                    if (chosenId.isBlank()) {
                        hitoBadgeDefId = ""
                        hitoBadgeItemId = ""
                    } else {
                        val b = GymBadges.BY_BADGE_ID[chosenId.lowercase()]
                        if (b != null) {
                            hitoProgression = true
                            hitoBadgeDefId = b.id
                            hitoBadgeItemId = b.itemIdentifier
                            currentDraft.iconItemId = b.itemIdentifier
                            if (currentDraft.leaderOrBoss.isBlank() || currentDraft.leaderOrBoss == "Lider") {
                                currentDraft.leaderOrBoss = b.gymLeaderName
                            }
                            if (currentDraft.subtitle.isBlank() || currentDraft.subtitle == "Hito de Progreso") {
                                currentDraft.subtitle = "Lider ${b.gymLeaderName}"
                            }
                        }
                    }
                    rebuildUi()
                }
            }.bounds(formX + 212, bodyTop + 2, medallaW, 16).build()
        )

        // Row 1: Titulo + selector rapido de formato §
        val y1 = bodyTop + 23
        hitoTitleBox = EditBox(font, formX + 40, y1, formW - 94, 16, Component.literal("Titulo")).apply {
            setMaxLength(128)
            value = hito.title
        }
        addRenderableWidget(hitoTitleBox!!)

        addRenderableWidget(
            Button.builder(Component.literal("Color v")) {
                syncHitoDraftFromBoxes()
                val options = listOf(
                    DropdownOption("Oro (§6)", "§6"),
                    DropdownOption("Amarillo (§e)", "§e"),
                    DropdownOption("Verde (§a)", "§a"),
                    DropdownOption("Celeste (§b)", "§b"),
                    DropdownOption("Rosa (§d)", "§d"),
                    DropdownOption("Rojo (§c)", "§c"),
                    DropdownOption("Negrita (§l)", "§l"),
                    DropdownOption("Limpiar colores", "CLEAR")
                )
                openDropdown = ActiveDropdown(formX + formW - 110, y1 + 17, 110, options) { code ->
                    val cur = editingHito ?: return@ActiveDropdown
                    cur.title = if (code == "CLEAR") {
                        cur.title.replace(Regex("§."), "")
                    } else {
                        code + cur.title
                    }
                    rebuildUi()
                }
            }.bounds(formX + formW - 50, y1, 50, 16).build()
        )

        // Row 2: Lider/Jefe | Subtitulo
        val y2 = bodyTop + 44
        val halfW = (formW - 68) / 2
        hitoLeaderBox = EditBox(font, formX + 34, y2, halfW, 16, Component.literal("Lider/Jefe")).apply {
            setMaxLength(64)
            value = hito.leaderOrBoss
        }
        addRenderableWidget(hitoLeaderBox!!)

        val subLblX = formX + 34 + halfW + 6
        hitoSubtitleBox = EditBox(font, subLblX + 26, y2, formW - (subLblX - formX) - 26, 16, Component.literal("Subtitulo")).apply {
            setMaxLength(64)
            value = hito.subtitle
        }
        addRenderableWidget(hitoSubtitleBox!!)

        // Row 3: Dia Inicio (- box +) | Duracion (- label +) | Boost XP (preset dropdown + -/+)
        val y3 = bodyTop + 65
        addRenderableWidget(
            Button.builder(Component.literal("-")) {
                syncHitoDraftFromBoxes()
                editingHito?.let { it.dayOffset = maxOf(1, it.dayOffset - 1) }
                rebuildUi()
            }.bounds(formX + 22, y3, 14, 16).build()
        )
        hitoDayBox = EditBox(font, formX + 38, y3, 28, 16, Component.literal("Dia")).apply {
            setMaxLength(4)
            value = hito.dayOffset.toString()
        }
        addRenderableWidget(hitoDayBox!!)
        addRenderableWidget(
            Button.builder(Component.literal("+")) {
                syncHitoDraftFromBoxes()
                editingHito?.let { it.dayOffset = (it.dayOffset + 1).coerceAtMost(365) }
                rebuildUi()
            }.bounds(formX + 68, y3, 14, 16).build()
        )

        val durX = formX + 86
        addRenderableWidget(
            Button.builder(Component.literal("-")) {
                syncHitoDraftFromBoxes()
                hitoDurationDays = maxOf(1, hitoDurationDays - 1)
                rebuildUi()
            }.bounds(durX + 24, y3, 14, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("+")) {
                syncHitoDraftFromBoxes()
                hitoDurationDays = (hitoDurationDays + 1).coerceAtMost(60)
                rebuildUi()
            }.bounds(durX + 62, y3, 14, 16).build()
        )

        val boostX = durX + 82
        val boostCtrlW = formW - (boostX - formX) - 36
        val boostText = when {
            !hitoProgression -> "Boost XP: N/A (Evento)"
            !hitoAllowCatchupXp || hitoCatchupMultiplier <= 1.001 -> "Boost XP: Desactivado v"
            else -> "Boost: x${formatMult(hitoCatchupMultiplier)} XP v"
        }
        addRenderableWidget(
            Button.builder(Component.literal(boostText)) {
                if (!hitoProgression) return@builder
                syncHitoDraftFromBoxes()
                val options = listOf(
                    DropdownOption("Desactivado (1.0x Sin Boost)", 1.0),
                    DropdownOption("x1.25 XP", 1.25),
                    DropdownOption("x1.5 XP (Estandar)", 1.5),
                    DropdownOption("x1.75 XP", 1.75),
                    DropdownOption("x2.0 XP (Doble)", 2.0),
                    DropdownOption("x2.5 XP", 2.5),
                    DropdownOption("x3.0 XP (Triple)", 3.0),
                    DropdownOption("x4.0 XP", 4.0)
                )
                openDropdown = ActiveDropdown(boostX, y3 + 17, maxOf(156, boostCtrlW + 32), options) { mult ->
                    if (mult <= 1.001) {
                        hitoAllowCatchupXp = false
                        hitoCatchupMultiplier = 1.0
                    } else {
                        hitoAllowCatchupXp = true
                        hitoCatchupMultiplier = mult
                    }
                    rebuildUi()
                }
            }.bounds(boostX, y3, boostCtrlW.coerceAtLeast(80), 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("-")) {
                if (!hitoProgression) return@builder
                syncHitoDraftFromBoxes()
                val next = ((hitoCatchupMultiplier * 10.0).toInt() - 1) / 10.0
                if (next <= 1.001) {
                    hitoAllowCatchupXp = false
                    hitoCatchupMultiplier = 1.0
                } else {
                    hitoAllowCatchupXp = true
                    hitoCatchupMultiplier = next.coerceIn(1.0, 10.0)
                }
                rebuildUi()
            }.bounds(formX + formW - 34, y3, 16, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("+")) {
                if (!hitoProgression) return@builder
                syncHitoDraftFromBoxes()
                val base = if (!hitoAllowCatchupXp || hitoCatchupMultiplier < 1.0) 1.0 else hitoCatchupMultiplier
                hitoAllowCatchupXp = true
                hitoCatchupMultiplier = (((base * 10.0).toInt() + 1) / 10.0).coerceIn(1.1, 10.0)
                rebuildUi()
            }.bounds(formX + formW - 16, y3, 16, 16).build()
        )

        // Row 4: Icono (slot live + EditBox + preset dropdown)
        val y4 = bodyTop + 86
        hitoIconBox = EditBox(font, formX + 58, y4, formW - 120, 16, Component.literal("Icono")).apply {
            setMaxLength(128)
            value = hito.iconItemId
        }
        addRenderableWidget(hitoIconBox!!)

        addRenderableWidget(
            Button.builder(Component.literal("Iconos v")) {
                syncHitoDraftFromBoxes()
                val opts = mutableListOf<DropdownOption<String>>()
                for ((itemId, label) in CURATED_HITO_ICONS) {
                    opts.add(DropdownOption(label, itemId))
                }
                for (b in GymBadges.ALL_GYM_BADGES) {
                    opts.add(DropdownOption("${b.name} (${b.region})", b.itemIdentifier))
                }
                openDropdown = ActiveDropdown(formX + formW - 180, y4 + 17, 180, opts) { chosenIcon ->
                    editingHito?.iconItemId = chosenIcon
                    rebuildUi()
                }
            }.bounds(formX + formW - 58, y4, 58, 16).build()
        )

        // Row 5: Descripcion
        val y5 = bodyTop + 108
        hitoDescBox = EditBox(font, formX + 34, y5, formW - 34, 16, Component.literal("Descripcion")).apply {
            setMaxLength(512)
            value = hito.description
        }
        addRenderableWidget(hitoDescBox!!)

        // Row 6: Resumen Recompensa + Auto button
        val y6 = bodyTop + 129
        hitoRewardSummaryBox = EditBox(font, formX + 48, y6, formW - 90, 16, Component.literal("Resumen Recompensa")).apply {
            setMaxLength(160)
            value = hito.rewardSummary
        }
        addRenderableWidget(hitoRewardSummaryBox!!)

        addRenderableWidget(
            Button.builder(Component.literal("Auto")) {
                syncHitoDraftFromBoxes()
                val generated = generateHitoRewardSummary(hitoRewardCommands, editingHito?.rewardDollars ?: 0L)
                editingHito?.rewardSummary = generated
                rebuildUi()
            }.bounds(formX + formW - 38, y6, 38, 16).build()
        )

        // Row 7: CobbleDollars + quick cash + Add Item / Add Command
        val y7 = bodyTop + 150
        hitoDollarsBox = EditBox(font, formX + 48, y7, 56, 16, Component.literal("CobbleDollars")).apply {
            setMaxLength(12)
            value = hito.rewardDollars.toString()
        }
        addRenderableWidget(hitoDollarsBox!!)

        addRenderableWidget(
            Button.builder(Component.literal("+500")) {
                syncHitoDraftFromBoxes()
                editingHito?.let { it.rewardDollars = (it.rewardDollars + 500L).coerceAtLeast(0L) }
                rebuildUi()
            }.bounds(formX + 107, y7, 30, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("+1k")) {
                syncHitoDraftFromBoxes()
                editingHito?.let { it.rewardDollars = (it.rewardDollars + 1000L).coerceAtLeast(0L) }
                rebuildUi()
            }.bounds(formX + 139, y7, 24, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("0$")) {
                syncHitoDraftFromBoxes()
                editingHito?.rewardDollars = 0L
                rebuildUi()
            }.bounds(formX + 165, y7, 20, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("+ Objeto")) {
                syncHitoDraftFromBoxes()
                hitoRewardCommands.add("give {player} cobblemon:rare_candy 1")
                hitoCmdScroll = maxOf(0, hitoRewardCommands.size - 3)
                rebuildUi()
            }.bounds(formX + formW - 126, y7, 60, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("+ Comando")) {
                syncHitoDraftFromBoxes()
                hitoRewardCommands.add("tellraw {player} {\"text\":\"Recompensa entregada\"}")
                hitoCmdScroll = maxOf(0, hitoRewardCommands.size - 3)
                rebuildUi()
            }.bounds(formX + formW - 63, y7, 63, 16).build()
        )

        // Row 8+: Reward commands list
        val cmdHeaderY = bodyTop + 170
        val cmdTop = cmdHeaderY + 13
        val footerTop = top + panelH - 26
        val availCmdH = footerTop - cmdTop - 4
        val maxVisibleCmds = (availCmdH / 20).coerceIn(1, 3)
        val maxCmdScroll = maxOf(0, hitoRewardCommands.size - maxVisibleCmds)
        hitoCmdScroll = hitoCmdScroll.coerceIn(0, maxCmdScroll)

        hitoRewardCommands.drop(hitoCmdScroll).take(maxVisibleCmds).forEachIndexed { visIdx, cmd ->
            val realIdx = hitoCmdScroll + visIdx
            val cy = cmdTop + visIdx * 20
            val isGive = cmd.trim().startsWith("give ", ignoreCase = true)
            val boxW = if (isGive) formW - 58 else formW - 22

            val cmdBox = EditBox(font, formX, cy, boxW, 16, Component.literal("Comando $realIdx")).apply {
                setMaxLength(256)
                value = cmd
                setResponder { newText ->
                    if (realIdx in hitoRewardCommands.indices) {
                        hitoRewardCommands[realIdx] = newText
                    }
                }
            }
            hitoCmdBoxes.add(cmdBox)
            addRenderableWidget(cmdBox)

            if (isGive) {
                addRenderableWidget(
                    Button.builder(Component.literal("-")) {
                        syncHitoDraftFromBoxes()
                        if (realIdx in hitoRewardCommands.indices) {
                            hitoRewardCommands[realIdx] = adjustGiveCommandAmount(hitoRewardCommands[realIdx], -1)
                            editingHito?.rewardCommands = hitoRewardCommands.toMutableList()
                        }
                        rebuildUi()
                    }.bounds(formX + boxW + 2, cy, 16, 16).build()
                )
                addRenderableWidget(
                    Button.builder(Component.literal("+")) {
                        syncHitoDraftFromBoxes()
                        if (realIdx in hitoRewardCommands.indices) {
                            hitoRewardCommands[realIdx] = adjustGiveCommandAmount(hitoRewardCommands[realIdx], 1)
                            editingHito?.rewardCommands = hitoRewardCommands.toMutableList()
                        }
                        rebuildUi()
                    }.bounds(formX + boxW + 20, cy, 16, 16).build()
                )
            }

            addRenderableWidget(
                Button.builder(Component.literal("X")) {
                    syncHitoDraftFromBoxes()
                    if (realIdx in hitoRewardCommands.indices) {
                        hitoRewardCommands.removeAt(realIdx)
                        editingHito?.rewardCommands = hitoRewardCommands.toMutableList()
                    }
                    rebuildUi()
                }.bounds(formX + formW - 18, cy, 18, 16).build()
            )
        }

        // Right Column: State Preview Cycle Button
        val stateLabel = when (hitoPreviewState) {
            1 -> "Vista: Completado"
            2 -> "Vista: Atrasado"
            3 -> "Vista: Proximo"
            else -> "Vista: En curso"
        }
        addRenderableWidget(
            Button.builder(Component.literal(stateLabel)) {
                syncHitoDraftFromBoxes()
                hitoPreviewState = (hitoPreviewState + 1) % 4
                rebuildUi()
            }.bounds(previewX + 74, bodyTop + 1, 102, 15).build()
        )

        // Bottom Footer Buttons
        addRenderableWidget(
            Button.builder(Component.literal("Guardar Hito")) {
                val draft = buildDraftHitoMilestone()
                if (draft.id.isBlank() || draft.title.isBlank()) {
                    hitoStatusMsg = "El ID y el titulo son obligatorios."
                    return@builder
                }
                if (!draft.id.matches(Regex("^[A-Za-z0-9_\\-]+$"))) {
                    hitoStatusMsg = "El ID solo puede tener letras, numeros, _ o -"
                    return@builder
                }
                if (draft.isProgressMilestone() && draft.iconItemId.isBlank()) {
                    draft.iconItemId = HitosManager.resolveDefaultIcon(draft.id, "", true)
                } else if (!draft.isProgressMilestone() && draft.iconItemId.isBlank()) {
                    draft.iconItemId = HitosManager.resolveDefaultIcon(draft.id, "", false)
                }
                if (draft.isProgressMilestone() && draft.badgeItemId.isBlank()) {
                    draft.badgeItemId = draft.iconItemId
                }
                sendSaveHitoMilestone(editingHitoOriginalId, draft)
                activeTab = "hitos"
                hitosGlobalStatusMsg = "Hito '${draft.title}' guardado y sincronizado."
                hitosGlobalStatusColor = 0xFF78DCA9.toInt()
                editingHito = null
                rebuildUi()
            }.bounds(left + panelW - 180, footerTop, 106, 18).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Cancelar")) {
                editingHito = null
                rebuildUi()
            }.bounds(left + panelW - 70, footerTop, 60, 18).build()
        )
    }

    private fun adjustGiveCommandAmount(rawCmd: String, delta: Int): String {
        val trimmed = rawCmd.trim()
        val match = Regex("^(.+\\s)(\\d+)$").find(trimmed)
        if (match != null) {
            val prefix = match.groupValues[1]
            val current = match.groupValues[2].toIntOrNull() ?: 1
            val next = (current + delta).coerceIn(1, 640)
            return "$prefix$next"
        }
        return if (delta > 0) "$trimmed 2" else "$trimmed 1"
    }

    private fun generateHitoRewardSummary(commands: List<String>, dollars: Long): String {
        val parts = mutableListOf<String>()
        for (raw in commands) {
            val tokens = raw.trim().removePrefix("/").split(Regex("\\s+"))
            if (tokens.size >= 3 && tokens[0].equals("give", true)) {
                val itemId = tokens[2]
                val count = tokens.getOrNull(3)?.toIntOrNull() ?: 1
                val itemName = CleanMissionDisplay.itemDisplayName(itemId)
                parts.add("${count}x $itemName")
            }
        }
        if (dollars > 0L) {
            val formatted = NumberFormat.getIntegerInstance(Locale.US).format(dollars)
            parts.add("$formatted CobbleDollars")
        }
        return if (parts.isEmpty()) "Recompensa por superacion" else parts.joinToString(" + ")
    }

    private fun formatMult(mult: Double): String {
        return if (mult % 1.0 == 0.0) {
            String.format(Locale.ROOT, "%.1f", mult)
        } else {
            String.format(Locale.ROOT, "%.2f", mult).trimEnd('0').trimEnd('.')
        }
    }

    // ==================== ANNOUNCEMENTS & MISSIONS EDITORS ====================

    private fun syncAnnDraftFromBoxes() {
        val ann = editingAnnouncement ?: return
        annIdBox?.value?.let { ann.id = it }
        annTitleBox?.value?.let { ann.title = it }
        annBodyBox?.value?.let { ann.body = it }
        ann.color = annColor
        ann.bodyColor = annBodyColor
        ann.showInChat = annShowChat
        ann.showInHud = annShowHud
        ann.hudDurationSeconds = annHudDuration
    }

    private fun syncMisDraftFromBoxes() {
        val mis = editingMission ?: return
        misIdBox?.value?.let { mis.id = it }
        misTitleBox?.value?.let { mis.title = it }
        misBodyBox?.value?.let { mis.body = it }
        misPublishAtBox?.value?.let { mis.publishAt = it }
        misExpirationBox?.value?.let { mis.expirationDate = it }
        mis.color = misColor
        mis.bodyColor = misBodyColor
        mis.isGuildMission = misIsGuild
        mis.targetUsesMemberCount = misTargetUsesMemberCount
        mis.daily = misFrequency >= 1
        mis.repeatable = misFrequency == 2
        mis.categoryTag = misCategoryTag
        mis.dailySlot = misDailySlot
        mis.moneyReward = misMoneyReward
    }

    private fun startEditAnnouncement(ann: Announcement, isNew: Boolean) {
        editingAnnouncement = ann
        isCreatingNew = isNew
        annColor = ann.color.ifBlank { "#F5C842" }
        annBodyColor = ann.bodyColor.ifBlank { "#FFFFFF" }
        annShowChat = ann.showInChat
        annShowHud = ann.showInHud
        annHudDuration = if (ann.hudDurationSeconds <= 0) 15 else ann.hudDurationSeconds
        rebuildUi()
    }

    private fun buildAnnouncementEditor(left: Int, top: Int, panelW: Int, panelH: Int) {
        val ann = editingAnnouncement ?: return

        annIdBox = EditBox(font, left + 40, top + 34, 140, 18, Component.literal("ID")).apply {
            value = ann.id
            setMaxLength(64)
        }
        addRenderableWidget(annIdBox!!)

        var btnX = left + 188
        val btnY = top + 34
        addRenderableWidget(Button.builder(Component.literal("Fuego")) {
            insertTagToTitle("<prism=#FF512F,#F09819,#FFD700>", "</prism>")
        }.bounds(btnX, btnY, 56, 18).build())
        btnX += 60
        addRenderableWidget(Button.builder(Component.literal("Cyber")) {
            insertTagToTitle("<prism=#00F2FE,#4FACFE,#B721FF>", "</prism>")
        }.bounds(btnX, btnY, 56, 18).build())
        btnX += 60
        addRenderableWidget(Button.builder(Component.literal("Oro")) {
            insertTagToTitle("<prism=#FFF7AD,#FFA9F9,#FFD700>", "</prism>")
        }.bounds(btnX, btnY, 50, 18).build())
        btnX += 54
        addRenderableWidget(Button.builder(Component.literal("<b>B</b>")) {
            insertTagToTitle("<bold>", "</bold>")
        }.bounds(btnX, btnY, 26, 18).build())
        btnX += 30
        addRenderableWidget(Button.builder(Component.literal("<i>I</i>")) {
            insertTagToTitle("<italic>", "</italic>")
        }.bounds(btnX, btnY, 26, 18).build())

        annTitleBox = EditBox(font, left + 46, top + 60, panelW - 58, 18, Component.literal("Título")).apply {
            value = ann.title
            setMaxLength(256)
        }
        addRenderableWidget(annTitleBox!!)

        val colorPresets = listOf("#F5C842", "#FF6B4A", "#55D69E", "#57C7F7", "#B721FF", "#FFFFFF")
        colorPresets.forEachIndexed { i, col ->
            addRenderableWidget(Button.builder(Component.literal(if (annColor.equals(col, true)) "[*]" else "[ ]")) {
                syncAnnDraftFromBoxes()
                annColor = col
                rebuildUi()
            }.bounds(left + 80 + i * 28, top + 84, 24, 16).build())
        }

        annBodyBox = EditBox(font, left + 14, top + 116, panelW - 28, 48, Component.literal("Detalles")).apply {
            value = ann.body
            setMaxLength(1024)
        }
        addRenderableWidget(annBodyBox!!)

        addRenderableWidget(Button.builder(Component.literal(if (annShowChat) "Chat: SI" else "Chat: NO")) { btn ->
            annShowChat = !annShowChat
            btn.message = Component.literal(if (annShowChat) "Chat: SI" else "Chat: NO")
        }.bounds(left + 14, top + 172, 100, 20).build())

        addRenderableWidget(Button.builder(Component.literal(if (annShowHud) "HUD: SI" else "HUD: NO")) { btn ->
            annShowHud = !annShowHud
            btn.message = Component.literal(if (annShowHud) "HUD: SI" else "HUD: NO")
        }.bounds(left + 120, top + 172, 100, 20).build())

        addRenderableWidget(Button.builder(Component.literal("-5s")) {
            syncAnnDraftFromBoxes()
            annHudDuration = maxOf(3, annHudDuration - 5)
            rebuildUi()
        }.bounds(left + 228, top + 172, 34, 20).build())

        addRenderableWidget(Button.builder(Component.literal("+5s")) {
            syncAnnDraftFromBoxes()
            annHudDuration = minOf(300, annHudDuration + 5)
            rebuildUi()
        }.bounds(left + 322, top + 172, 34, 20).build())

        addRenderableWidget(Button.builder(Component.literal("Guardar y Publicar")) {
            val updated = Announcement(
                id = annIdBox?.value?.trim()?.ifBlank { "ann_" + UUID.randomUUID().toString().take(6) } ?: ann.id,
                title = annTitleBox?.value?.trim()?.ifBlank { "Sin Título" } ?: ann.title,
                body = annBodyBox?.value ?: ann.body,
                color = annColor,
                bodyColor = annBodyColor,
                showInChat = annShowChat,
                showInHud = annShowHud,
                hudDurationSeconds = annHudDuration
            )
            val json = BulletinJson.gson.toJson(updated)
            ClientPlayNetworking.send(BulletinAdminActionPayload("saveAnnouncement", json))
            val list = BulletinClientData.snapshot.document.announcements
            val idx = list.indexOfFirst { it.id == updated.id }
            if (idx >= 0) list[idx] = updated else list.add(0, updated)
            editingAnnouncement = null
            rebuildUi()
        }.bounds(left + panelW - 200, top + panelH - 32, 120, 22).build())

        addRenderableWidget(Button.builder(Component.literal("Cancelar")) {
            editingAnnouncement = null
            rebuildUi()
        }.bounds(left + panelW - 74, top + panelH - 32, 64, 22).build())
    }

    private fun insertTagToTitle(prefix: String, suffix: String) {
        val box = if (editingAnnouncement != null) annTitleBox else misTitleBox
        if (box != null) {
            box.value = "$prefix${box.value}$suffix"
        }
    }

    private fun startEditMission(mis: Mission, isNew: Boolean) {
        editingMission = mis
        isCreatingNew = isNew
        misColor = mis.color.ifBlank { "#38BDF8" }
        misBodyColor = mis.bodyColor.ifBlank { "#E6EDF3" }
        misIsGuild = mis.isGuildMission
        misTargetUsesMemberCount = mis.targetUsesMemberCount
        misCategoryTag = mis.categoryTag
        misDailySlot = mis.dailySlot
        misFrequency = when {
            mis.daily && mis.repeatable -> 2
            mis.daily -> 1
            else -> 0
        }
        misMoneyReward = mis.moneyReward
        misObjectives.clear()
        if (mis.objectives.isNotEmpty()) {
            misObjectives.addAll(mis.objectives.map { it.copy() })
        } else {
            misObjectives.add(MissionObjective(type = mis.objective, itemId = mis.itemId, amount = mis.amount))
        }
        misItemRewards.clear()
        misItemRewards.addAll(mis.itemRewards.map { it.copy() })
        misPokemonRewards.clear()
        if (mis.pokemonReward != null) misPokemonRewards.add(mis.pokemonReward!!.copy())
        misPokemonRewards.addAll(mis.pokemonRewards.map { it.copy() })
        rebuildUi()
    }

    private fun buildMissionEditor(left: Int, top: Int, panelW: Int, panelH: Int) {
        val mis = editingMission ?: return

        misIdBox = EditBox(font, left + 40, top + 34, 112, 18, Component.literal("ID")).apply {
            value = mis.id
            setMaxLength(64)
        }
        addRenderableWidget(misIdBox!!)

        val catLabel = "Cat: ${misCategoryTag.ifBlank { "Auto" }}"
        addRenderableWidget(Button.builder(Component.literal(catLabel)) {
            syncMisDraftFromBoxes()
            val options = listOf(
                DropdownOption("Auto (Según objetivo)", ""),
                DropdownOption("CAPTURA", "CAPTURA"),
                DropdownOption("ITEMS", "ITEMS"),
                DropdownOption("COMBATE", "COMBATE"),
                DropdownOption("ESPECIAL", "ESPECIAL")
            )
            openDropdown = ActiveDropdown(left + 158, top + 53, 145, options) { chosen ->
                misCategoryTag = chosen
                rebuildUi()
            }
        }.bounds(left + 158, top + 34, 108, 18).build())

        val scopeLabel = if (misIsGuild) "Gremio" else "Individual"
        addRenderableWidget(Button.builder(Component.literal(scopeLabel)) {
            syncMisDraftFromBoxes()
            val options = listOf(
                DropdownOption("Misión Individual", false),
                DropdownOption("Misión de Gremio (Colaborativa)", true)
            )
            openDropdown = ActiveDropdown(left + 270, top + 53, 170, options) { isGuild ->
                misIsGuild = isGuild
                rebuildUi()
            }
        }.bounds(left + 270, top + 34, 96, 18).build())

        val freqLabel = when (misFrequency) {
            1 -> "Diaria"
            2 -> "Diaria Rep."
            else -> "Normal"
        }
        addRenderableWidget(Button.builder(Component.literal(freqLabel)) {
            syncMisDraftFromBoxes()
            val options = listOf(
                DropdownOption("Normal / Permanente", 0),
                DropdownOption("Diaria (Reinicio 24h)", 1),
                DropdownOption("Diaria Repetible", 2)
            )
            openDropdown = ActiveDropdown(left + 370, top + 53, 145, options) { f ->
                misFrequency = f
                rebuildUi()
            }
        }.bounds(left + 370, top + 34, panelW - 384, 18).build())

        misTitleBox = EditBox(font, left + 52, top + 56, panelW - 66, 18, Component.literal("Título")).apply {
            setHint(Component.literal("Título visible al publicarse"))
            value = mis.title
            setMaxLength(256)
        }
        addRenderableWidget(misTitleBox!!)

        val rowY = top + 78
        val halfTimeW = (panelW - 118) / 2
        misPublishAtBox = EditBox(font, left + 52, rowY, halfTimeW, 18, Component.literal("Publicar (VE)")).apply {
            setHint(Component.literal("Publicar oculta: AAAA-MM-DD HH:mm (VE)"))
            value = mis.publishAt
            setMaxLength(24)
        }
        addRenderableWidget(misPublishAtBox!!)

        misExpirationBox = EditBox(font, left + 52 + halfTimeW + 46, rowY, panelW - (66 + halfTimeW + 46), 18, Component.literal("Vence (VE)")).apply {
            setHint(Component.literal("Expira: AAAA-MM-DD HH:mm (VE)"))
            value = mis.expirationDate
            setMaxLength(24)
        }
        addRenderableWidget(misExpirationBox!!)

        misBodyBox = EditBox(font, left + 52, top + 100, panelW - 66, 22, Component.literal("Descripción")).apply {
            setHint(Component.literal("Descripción clara para los jugadores"))
            value = mis.body
            setMaxLength(512)
        }
        addRenderableWidget(misBodyBox!!)

        val compactMis = panelW < 500
        val addObjW = if (compactMis) 98 else 110
        addRenderableWidget(Button.builder(Component.literal("+ Añadir Objetivo")) {
            syncMisDraftFromBoxes()
            if (misObjectives.size < 5) {
                misObjectives.add(MissionObjective(type = ObjectiveType.GET_ITEM, itemId = "minecraft:diamond", amount = 1))
                rebuildUi()
            }
        }.bounds(left + 14, top + 132, addObjW, 18).build())

        val pokeBtnW = if (compactMis) 64 else 70
        val pokeBtnX = left + panelW - 12 - pokeBtnW
        val itemBtnW = if (compactMis) 64 else 74
        val itemBtnX = pokeBtnX - 4 - itemBtnW
        val zeroBtnW = if (compactMis) 24 else 28
        val zeroBtnX = itemBtnX - 6 - zeroBtnW
        val fiveKBtnW = if (compactMis) 38 else 44
        val fiveKBtnX = zeroBtnX - 3 - fiveKBtnW
        val five00BtnW = if (compactMis) 44 else 48
        val five00BtnX = fiveKBtnX - 3 - five00BtnW

        addRenderableWidget(Button.builder(Component.literal("+500$")) {
            syncMisDraftFromBoxes()
            misMoneyReward += 500L
            rebuildUi()
        }.bounds(five00BtnX, top + 132, five00BtnW, 18).build())

        addRenderableWidget(Button.builder(Component.literal("+5k$")) {
            syncMisDraftFromBoxes()
            misMoneyReward += 5000L
            rebuildUi()
        }.bounds(fiveKBtnX, top + 132, fiveKBtnW, 18).build())

        addRenderableWidget(Button.builder(Component.literal("0$")) {
            syncMisDraftFromBoxes()
            misMoneyReward = 0L
            rebuildUi()
        }.bounds(zeroBtnX, top + 132, zeroBtnW, 18).build())

        addRenderableWidget(Button.builder(Component.literal("+ Objeto")) {
            syncMisDraftFromBoxes()
            misItemRewards.add(ItemMissionReward("minecraft:diamond", 1))
            rebuildUi()
        }.bounds(itemBtnX, top + 132, itemBtnW, 18).build())

        addRenderableWidget(Button.builder(Component.literal("+ Pokémon")) {
            syncMisDraftFromBoxes()
            misPokemonRewards.add(PokemonMissionReward("cobblemon:eevee", "shiny=yes"))
            rebuildUi()
        }.bounds(pokeBtnX, top + 132, pokeBtnW, 18).build())

        var objY = top + 156
        misObjectives.take(3).forEachIndexed { idx, obj ->
            val typeLabel = when (obj.type) {
                ObjectiveType.GET_ITEM -> "Objeto"
                ObjectiveType.DEFEAT_POKEMON -> "Derrotar"
                ObjectiveType.CATCH_TYPE -> "Tipo"
                ObjectiveType.CATCH_SPECIES -> "Especie"
                ObjectiveType.OBTAIN_BADGE -> "Medalla"
                ObjectiveType.EVOLVE_SPECIES -> "Evolución"
                ObjectiveType.WIN_PVP -> "PvP"
                ObjectiveType.WIN_SHOWDOWN -> "Showdown"
                ObjectiveType.WIN_PVP_OR_SHOWDOWN -> "PvP/Show."
                ObjectiveType.DEFEAT_RCT_NPC -> "NPC RCT"
                ObjectiveType.CRAFT_ITEM -> "Crafteo"
                ObjectiveType.EARN_MONEY -> "Ganar $"
                ObjectiveType.SPEND_MONEY -> "Gastar $"
            }
            val typeBtnW = if (compactMis) 62 else 74
            val detX = left + 14 + typeBtnW + 3
            val rightControlsLeft = left + panelW - (when {
                misIsGuild -> 166
                !obj.targetUsesMemberCount -> 114
                else -> 38
            })
            val detAvail = (rightControlsLeft - 4 - detX).coerceAtLeast(110)

            addRenderableWidget(Button.builder(Component.literal(typeLabel)) {
                syncMisDraftFromBoxes()
                val options = listOf(
                    DropdownOption("Obtener Objeto", ObjectiveType.GET_ITEM),
                    DropdownOption("Craftear / Fabricar Objeto", ObjectiveType.CRAFT_ITEM),
                    DropdownOption("Capturar Especie", ObjectiveType.CATCH_SPECIES),
                    DropdownOption("Capturar Tipo", ObjectiveType.CATCH_TYPE),
                    DropdownOption("Derrotar Pokémon", ObjectiveType.DEFEAT_POKEMON),
                    DropdownOption("Evolucionar Especie Inicial", ObjectiveType.EVOLVE_SPECIES),
                    DropdownOption("Ganar Combates PvP", ObjectiveType.WIN_PVP),
                    DropdownOption("Ganar en Showdown", ObjectiveType.WIN_SHOWDOWN),
                    DropdownOption("Ganar PvP o Showdown", ObjectiveType.WIN_PVP_OR_SHOWDOWN),
                    DropdownOption("Vencer Entrenador NPC (rctmod)", ObjectiveType.DEFEAT_RCT_NPC),
                    DropdownOption("Ganar Dinero (Temporal)", ObjectiveType.EARN_MONEY),
                    DropdownOption("Gastar Dinero (Temporal)", ObjectiveType.SPEND_MONEY),
                    DropdownOption("Ganar Medalla", ObjectiveType.OBTAIN_BADGE)
                )
                openDropdown = ActiveDropdown(left + 14, objY + 19, 195, options) { selectedType ->
                    obj.type = selectedType
                    if (selectedType == ObjectiveType.CATCH_TYPE && obj.pokemonType.isBlank()) obj.pokemonType = "fire"
                    if (selectedType == ObjectiveType.OBTAIN_BADGE && obj.badgeId.isBlank()) obj.badgeId = GymBadges.ALL_GYM_BADGES.firstOrNull()?.id ?: "kanto_boulder_badge"
                    if ((selectedType == ObjectiveType.EARN_MONEY || selectedType == ObjectiveType.SPEND_MONEY) && obj.amount < 100) obj.amount = 5000
                    rebuildUi()
                }
            }.bounds(left + 14, objY, typeBtnW, 18).build())

            when (obj.type) {
                ObjectiveType.CATCH_TYPE -> {
                    val lvlW = if (detAvail < 260) 44 else 52
                    val rem = detAvail - lvlW - 6
                    val t1W = minOf(88, rem / 2)
                    val t2W = minOf(86, rem - t1W)
                    val t2X = detX + t1W + 3
                    val lvlX = t2X + t2W + 3

                    val currentTypeName = CleanMissionDisplay.typeName(obj.pokemonType)
                    addRenderableWidget(Button.builder(Component.literal("1o: $currentTypeName")) {
                        syncMisDraftFromBoxes()
                        val options = POKEMON_TYPES.map { DropdownOption(it.second, it.first) }
                        openDropdown = ActiveDropdown(detX, objY + 19, 110, options) { chosenType ->
                            obj.pokemonType = chosenType
                            rebuildUi()
                        }
                    }.bounds(detX, objY, t1W, 18).build())

                    val secLabel = if (obj.secondaryPokemonType.isBlank()) "2o: Ninguno" else "2o: ${CleanMissionDisplay.typeName(obj.secondaryPokemonType)}"
                    addRenderableWidget(Button.builder(Component.literal(secLabel)) {
                        syncMisDraftFromBoxes()
                        val options = listOf(DropdownOption("Ninguno", "")) + POKEMON_TYPES.map { DropdownOption(it.second, it.first) }
                        openDropdown = ActiveDropdown(t2X, objY + 19, 110, options) { chosenType ->
                            obj.secondaryPokemonType = chosenType
                            rebuildUi()
                        }
                    }.bounds(t2X, objY, t2W, 18).build())

                    val lvlLabel = if (obj.minLevel > 0) "Nv.${obj.minLevel}+" else "Nv:0"
                    addRenderableWidget(Button.builder(Component.literal(lvlLabel)) {
                        syncMisDraftFromBoxes()
                        val options = listOf(0, 10, 15, 20, 25, 30, 40, 50).map {
                            DropdownOption(if (it == 0) "Sin nivel min." else "Nivel min. $it", it)
                        }
                        openDropdown = ActiveDropdown(lvlX, objY + 19, 100, options) { lvl ->
                            obj.minLevel = lvl
                            rebuildUi()
                        }
                    }.bounds(lvlX, objY, lvlW, 18).build())
                }
                ObjectiveType.OBTAIN_BADGE -> {
                    val badgeW = minOf(150, detAvail)
                    val currentBadgeName = CleanMissionDisplay.badgeName(obj.badgeId)
                    addRenderableWidget(Button.builder(Component.literal("Medalla: $currentBadgeName")) {
                        syncMisDraftFromBoxes()
                        val options = GymBadges.ALL_GYM_BADGES.map { DropdownOption("${it.region}: ${it.name}", it.id) }
                        openDropdown = ActiveDropdown(detX, objY + 19, 180, options) { chosenBadge ->
                            obj.badgeId = chosenBadge
                            rebuildUi()
                        }
                    }.bounds(detX, objY, badgeW, 18).build())
                }
                ObjectiveType.GET_ITEM -> {
                    val consW = if (detAvail < 240) 56 else 64
                    val boxW = minOf(120, (detAvail - consW - 3).coerceAtLeast(60))
                    val box = EditBox(font, detX, objY, boxW, 18, Component.literal("ID Objeto")).apply {
                        setHint(Component.literal("minecraft:diamond"))
                        value = obj.itemId
                        setMaxLength(64)
                        setResponder { obj.itemId = it }
                    }
                    objectiveTargetBoxes.add(box)
                    addRenderableWidget(box)

                    val consumeTxt = if (obj.consumeOnClaim) "Entrega" else "Posesion"
                    addRenderableWidget(Button.builder(Component.literal(consumeTxt)) {
                        syncMisDraftFromBoxes()
                        obj.consumeOnClaim = !obj.consumeOnClaim
                        rebuildUi()
                    }.bounds(detX + boxW + 3, objY, consW, 18).build())
                }
                ObjectiveType.CRAFT_ITEM -> {
                    val box = EditBox(font, detX, objY, minOf(165, detAvail), 18, Component.literal("ID Objeto a craftear")).apply {
                        setHint(Component.literal("Objeto a craftear (ej. minecraft:anvil)"))
                        value = obj.itemId
                        setMaxLength(64)
                        setResponder { obj.itemId = it }
                    }
                    objectiveTargetBoxes.add(box)
                    addRenderableWidget(box)
                }
                ObjectiveType.EVOLVE_SPECIES -> {
                    val box = EditBox(font, detX, objY, minOf(165, detAvail), 18, Component.literal("Especie inicial")).apply {
                        setHint(Component.literal("Especie inicial (ej. charmander)"))
                        value = obj.pokemonId
                        setMaxLength(48)
                        setResponder { obj.pokemonId = it }
                    }
                    objectiveTargetBoxes.add(box)
                    addRenderableWidget(box)
                }
                ObjectiveType.DEFEAT_RCT_NPC -> {
                    val box = EditBox(font, detX, objY, minOf(165, detAvail), 18, Component.literal("ID NPC RCT (opcional)")).apply {
                        setHint(Component.literal("ID NPC RCT (vacío = cualquiera)"))
                        value = obj.pokemonId
                        setMaxLength(64)
                        setResponder { obj.pokemonId = it }
                    }
                    objectiveTargetBoxes.add(box)
                    addRenderableWidget(box)
                }
                ObjectiveType.WIN_PVP, ObjectiveType.WIN_SHOWDOWN, ObjectiveType.WIN_PVP_OR_SHOWDOWN,
                ObjectiveType.EARN_MONEY, ObjectiveType.SPEND_MONEY -> {
                    // Sin campo adicional; la cantidad/meta va a la derecha
                }
                ObjectiveType.DEFEAT_POKEMON -> {
                    val lvlW = if (detAvail < 260) 40 else 48
                    val evoW = if (detAvail < 260) 38 else 46
                    val typeW = if (detAvail < 260) 58 else 68
                    val boxW = minOf(78, (detAvail - lvlW - evoW - typeW - 9).coerceAtLeast(48))
                    val typeX = detX + boxW + 3
                    val lvlX = typeX + typeW + 3
                    val evoX = lvlX + lvlW + 3

                    val box = EditBox(font, detX, objY, boxW, 18, Component.literal("Especie")).apply {
                        value = obj.pokemonId
                        setMaxLength(48)
                        setResponder { obj.pokemonId = it }
                    }
                    objectiveTargetBoxes.add(box)
                    addRenderableWidget(box)

                    val typeTxt = if (obj.pokemonType.isBlank()) "Tipo: Any" else CleanMissionDisplay.typeName(obj.pokemonType)
                    addRenderableWidget(Button.builder(Component.literal(typeTxt)) {
                        syncMisDraftFromBoxes()
                        val options = listOf(DropdownOption("Cualquier Tipo", "")) + POKEMON_TYPES.map { DropdownOption(it.second, it.first) }
                        openDropdown = ActiveDropdown(typeX, objY + 19, 110, options) { t ->
                            obj.pokemonType = t
                            rebuildUi()
                        }
                    }.bounds(typeX, objY, typeW, 18).build())

                    val lvlLabel = if (obj.minLevel > 0) "Nv.${obj.minLevel}+" else "Nv:0"
                    addRenderableWidget(Button.builder(Component.literal(lvlLabel)) {
                        syncMisDraftFromBoxes()
                        val options = listOf(0, 10, 15, 20, 25, 30, 40, 50).map {
                            DropdownOption(if (it == 0) "Sin nivel min." else "Nivel min. $it", it)
                        }
                        openDropdown = ActiveDropdown(lvlX, objY + 19, 100, options) { lvl ->
                            obj.minLevel = lvl
                            rebuildUi()
                        }
                    }.bounds(lvlX, objY, lvlW, 18).build())

                    val evoLabel = if (obj.minEvolutionStage > 0) "Et.${obj.minEvolutionStage}+" else "Et:0"
                    addRenderableWidget(Button.builder(Component.literal(evoLabel)) {
                        syncMisDraftFromBoxes()
                        val options = listOf(0, 2, 3).map {
                            DropdownOption(if (it == 0) "Cualquier etapa" else "Etapa min. $it", it)
                        }
                        openDropdown = ActiveDropdown(evoX, objY + 19, 110, options) { evo ->
                            obj.minEvolutionStage = evo
                            rebuildUi()
                        }
                    }.bounds(evoX, objY, evoW, 18).build())
                }
                ObjectiveType.CATCH_SPECIES -> {
                    val lvlW = if (detAvail < 260) 44 else 52
                    val shinyW = if (detAvail < 260) 54 else 64
                    val boxW = minOf(88, (detAvail - lvlW - shinyW - 6).coerceAtLeast(60))
                    val shinyX = detX + boxW + 3
                    val lvlX = shinyX + shinyW + 3

                    val box = EditBox(font, detX, objY, boxW, 18, Component.literal("Especie")).apply {
                        value = obj.pokemonId
                        setMaxLength(48)
                        setResponder { obj.pokemonId = it }
                    }
                    objectiveTargetBoxes.add(box)
                    addRenderableWidget(box)

                    val isShiny = obj.pokemonProperties.contains("shiny", ignoreCase = true) && !obj.pokemonProperties.contains("shiny=no", ignoreCase = true)
                    val shinyLabel = if (isShiny) "Shiny" else "Normal"
                    addRenderableWidget(Button.builder(Component.literal(shinyLabel)) {
                        syncMisDraftFromBoxes()
                        val options = listOf(
                            DropdownOption("Normal / Cualquiera", ""),
                            DropdownOption("Shiny", "shiny=yes")
                        )
                        openDropdown = ActiveDropdown(shinyX, objY + 19, 130, options) { sVal ->
                            obj.pokemonProperties = sVal
                            rebuildUi()
                        }
                    }.bounds(shinyX, objY, shinyW, 18).build())

                    val lvlLabel = if (obj.minLevel > 0) "Nv.${obj.minLevel}+" else "Nv:0"
                    addRenderableWidget(Button.builder(Component.literal(lvlLabel)) {
                        syncMisDraftFromBoxes()
                        val options = listOf(0, 10, 15, 20, 25, 30, 40, 50).map {
                            DropdownOption(if (it == 0) "Sin nivel min." else "Nivel min. $it", it)
                        }
                        openDropdown = ActiveDropdown(lvlX, objY + 19, 100, options) { lvl ->
                            obj.minLevel = lvl
                            rebuildUi()
                        }
                    }.bounds(lvlX, objY, lvlW, 18).build())
                }
            }

            if (misIsGuild) {
                val scaleLabel = if (obj.targetUsesMemberCount) "xMiembros" else "Fijo"
                addRenderableWidget(Button.builder(Component.literal(scaleLabel)) {
                    syncMisDraftFromBoxes()
                    obj.targetUsesMemberCount = !obj.targetUsesMemberCount
                    rebuildUi()
                }.bounds(left + panelW - 164, objY, 50, 18).build())
            }

            if (!obj.targetUsesMemberCount) {
                val amtBox = EditBox(font, left + panelW - 110, objY, 46, 18, Component.literal("Meta")).apply {
                    value = obj.amount.toString()
                    setMaxLength(10)
                    setResponder { txt ->
                        val parsed = txt.trim().toIntOrNull()
                        if (parsed != null && parsed > 0) obj.amount = parsed
                    }
                }
                objectiveTargetBoxes.add(amtBox)
                addRenderableWidget(amtBox)

                addRenderableWidget(Button.builder(Component.literal("+")) {
                    syncMisDraftFromBoxes()
                    val step = if (obj.type == ObjectiveType.EARN_MONEY || obj.type == ObjectiveType.SPEND_MONEY) 1000 else 1
                    obj.amount = (obj.amount + step).coerceAtMost(1_000_000_000)
                    rebuildUi()
                }.bounds(left + panelW - 60, objY, 20, 18).build())
            }

            addRenderableWidget(Button.builder(Component.literal("X")) {
                syncMisDraftFromBoxes()
                if (misObjectives.size > 1) {
                    misObjectives.removeAt(idx)
                    rebuildUi()
                }
            }.bounds(left + panelW - 36, objY, 22, 18).build())

            objY += 22
        }

        val rewardsStartY = objY + 4
        var rewY = rewardsStartY
        misItemRewards.take(2).forEachIndexed { rIdx, rew ->
            val box = EditBox(font, left + 14, rewY, 120, 18, Component.literal("ID Objeto")).apply {
                value = rew.itemId
                setMaxLength(64)
                setResponder { rew.itemId = it }
            }
            rewardItemBoxes.add(box)
            addRenderableWidget(box)

            addRenderableWidget(Button.builder(Component.literal("-")) {
                syncMisDraftFromBoxes()
                rew.count = maxOf(1, rew.count - 1)
                rebuildUi()
            }.bounds(left + 140, rewY, 18, 18).build())

            addRenderableWidget(Button.builder(Component.literal("+")) {
                syncMisDraftFromBoxes()
                rew.count = minOf(64, rew.count + 1)
                rebuildUi()
            }.bounds(left + 185, rewY, 18, 18).build())

            addRenderableWidget(Button.builder(Component.literal("X")) {
                syncMisDraftFromBoxes()
                misItemRewards.removeAt(rIdx)
                rebuildUi()
            }.bounds(left + 210, rewY, 20, 18).build())

            rewY += 22
        }

        misPokemonRewards.take(2).forEachIndexed { pIdx, pRew ->
            val pRowY = rewardsStartY + pIdx * 22
            val box = EditBox(font, left + 244, pRowY, 110, 18, Component.literal("Especie Recompensa")).apply {
                value = pRew.speciesId
                setMaxLength(48)
                setResponder { pRew.speciesId = it }
            }
            rewardPokemonBoxes.add(box)
            addRenderableWidget(box)

            val pShiny = pRew.properties.contains("shiny", ignoreCase = true) && !pRew.properties.contains("shiny=no", ignoreCase = true)
            addRenderableWidget(Button.builder(Component.literal(if (pShiny) "Shiny" else "Normal")) {
                syncMisDraftFromBoxes()
                pRew.properties = if (pShiny) "" else "shiny=yes"
                rebuildUi()
            }.bounds(left + 358, pRowY, 64, 18).build())

            addRenderableWidget(Button.builder(Component.literal("X")) {
                syncMisDraftFromBoxes()
                misPokemonRewards.removeAt(pIdx)
                rebuildUi()
            }.bounds(left + 426, pRowY, 20, 18).build())
        }

        addRenderableWidget(Button.builder(Component.literal("Guardar")) {
            val updated = Mission(
                id = misIdBox?.value?.trim()?.ifBlank { "mis_" + UUID.randomUUID().toString().take(6) } ?: mis.id,
                title = misTitleBox?.value?.trim()?.ifBlank { "Nueva Misión" } ?: mis.title,
                body = misBodyBox?.value ?: mis.body,
                color = misColor,
                bodyColor = misBodyColor,
                isGuildMission = misIsGuild,
                targetUsesMemberCount = misTargetUsesMemberCount || misObjectives.any { it.targetUsesMemberCount },
                daily = misFrequency >= 1,
                repeatable = misFrequency == 2,
                publishAt = misPublishAtBox?.value?.trim() ?: mis.publishAt,
                expirationDate = misExpirationBox?.value?.trim() ?: mis.expirationDate,
                objectives = misObjectives,
                itemRewards = misItemRewards,
                pokemonRewards = misPokemonRewards,
                moneyReward = misMoneyReward,
                categoryTag = misCategoryTag,
                dailySlot = misDailySlot
            )
            val json = BulletinJson.gson.toJson(updated)
            ClientPlayNetworking.send(BulletinAdminActionPayload("saveMission", json))
            val list = BulletinClientData.snapshot.document.missions
            val idx = list.indexOfFirst { it.id == updated.id }
            if (idx >= 0) list[idx] = updated else list.add(0, updated)
            editingMission = null
            rebuildUi()
        }.bounds(left + panelW - 220, top + panelH - 32, 140, 22).build())

        addRenderableWidget(Button.builder(Component.literal("Cancelar")) {
            editingMission = null
            rebuildUi()
        }.bounds(left + panelW - 74, top + panelH - 32, 64, 22).build())
    }

    // ==================== INPUT EVENTS ====================

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val activeDrop = openDropdown
        if (activeDrop != null) {
            val maxVis = activeDrop.maxVisible(height)
            val maxDropScroll = (activeDrop.options.size - maxVis).coerceAtLeast(0)
            if (maxDropScroll > 0) {
                val delta = if (verticalAmount > 0) -1 else 1
                activeDrop.scrollOffset = (activeDrop.scrollOffset + delta).coerceIn(0, maxDropScroll)
            }
            return true
        }

        if (editingHito != null) {
            val geom = panelGeometry()
            val panelH = geom[3]
            val cmdTop = geom[1] + 30 + 183
            val footerTop = geom[1] + panelH - 26
            val maxVisibleCmds = ((footerTop - cmdTop - 4) / 20).coerceIn(1, 3)
            val maxCmdScroll = maxOf(0, hitoRewardCommands.size - maxVisibleCmds)
            if (maxCmdScroll > 0) {
                syncHitoDraftFromBoxes()
                val delta = if (verticalAmount > 0) -1 else 1
                hitoCmdScroll = (hitoCmdScroll + delta).coerceIn(0, maxCmdScroll)
                rebuildUi()
            }
            return true
        }

        if (editingAnnouncement == null && editingMission == null && maxScrollRow > 0) {
            val delta = if (verticalAmount > 0) -1 else 1
            val next = (scrollRow + delta).coerceIn(0, maxScrollRow)
            if (next != scrollRow) {
                if (activeTab == "hitos") syncHitosGlobalFromBoxes()
                scrollRow = next
                rebuildUi()
            }
            return true
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val activeDrop = openDropdown
        if (activeDrop != null) {
            val visCount = activeDrop.maxVisible(height)
            val totalH = visCount * activeDrop.optH
            val dropTop = activeDrop.effectiveTop(height)
            val dropRight = activeDrop.x + activeDrop.w
            val dropBottom = dropTop + totalH

            if (mouseX >= activeDrop.x && mouseX <= dropRight && mouseY >= dropTop && mouseY <= dropBottom) {
                val visIndex = ((mouseY - dropTop) / activeDrop.optH).toInt().coerceIn(0, visCount - 1)
                val optIndex = (activeDrop.scrollOffset + visIndex).coerceIn(0, activeDrop.options.size - 1)
                val opt = activeDrop.options[optIndex]
                openDropdown = null
                @Suppress("UNCHECKED_CAST")
                (activeDrop as ActiveDropdown<Any?>).onSelect(opt.value)
                return true
            }
            openDropdown = null
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (openDropdown != null) {
                openDropdown = null
                return true
            }
            if (editingAnnouncement != null) {
                editingAnnouncement = null
                rebuildUi()
                return true
            }
            if (editingMission != null) {
                editingMission = null
                rebuildUi()
                return true
            }
            if (editingHito != null) {
                editingHito = null
                rebuildUi()
                return true
            }
            closeToParent()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    // ==================== RENDERING ====================

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val geom = panelGeometry()
        val left = geom[0]
        val top = geom[1]
        val panelW = geom[2]
        val panelH = geom[3]

        val accentColor = when {
            editingHito != null || activeTab == "hitos" -> 0xFFF59E0B.toInt()
            editingMission != null || activeTab == "missions" -> 0xFF38BDF8.toInt()
            else -> 0xFFF5C842.toInt()
        }

        // Fondo oscuro estilizado con acento
        graphics.fill(0, 0, width, height, 0xCC06080C.toInt())
        graphics.fill(left - 2, top - 2, left + panelW + 2, top + panelH + 2, 0xFF2D333B.toInt())
        graphics.fill(left, top, left + panelW, top + panelH, 0xF80E1117.toInt())
        graphics.fill(left, top, left + panelW, top + 26, 0xFF161B22.toInt())
        graphics.fill(left, top + 26, left + panelW, top + 27, accentColor)

        if (editingHito != null) {
            renderHitoEditorOverlay(graphics, left, top, panelW, panelH)
        } else if (editingMission != null) {
            val mis = editingMission!!
            val headerTitle = if (isCreatingNew) "Crear Nueva Misión (Hora Venezuela UTC-4)" else "Editando Misión: ${mis.title}"
            graphics.drawString(font, Component.literal(headerTitle), left + 14, top + 10, 0xFFF43F5E.toInt())

            val halfTimeW = (panelW - 118) / 2
            graphics.drawString(font, Component.literal("ID:"), left + 14, top + 38, 0xFF8B949E.toInt())
            graphics.drawString(font, Component.literal("Título:"), left + 14, top + 60, 0xFF8B949E.toInt())
            graphics.drawString(font, Component.literal("Pub VE:"), left + 14, top + 82, 0xFFFBBF24.toInt())
            graphics.drawString(font, Component.literal("Vence:"), left + 52 + halfTimeW + 6, top + 82, 0xFFF43F5E.toInt())
            graphics.drawString(font, Component.literal("Desc:"), left + 14, top + 106, 0xFF8B949E.toInt())
            graphics.fill(left + 12, top + 126, left + panelW - 12, top + 127, 0xFF2D333B.toInt())
            val rewardSummary = "Recompensas: ${misMoneyReward}$ | ${misItemRewards.size} Objetos | ${misPokemonRewards.size} Pokémon"
            graphics.drawString(font, Component.literal(font.plainSubstrByWidth(rewardSummary, panelW - 240)), left + 14, top + panelH - 26, 0xFFFBBF24.toInt())

            misItemRewards.take(2).forEachIndexed { rIdx, rew ->
                val rewY = top + 156 + misObjectives.take(3).size * 22 + 4 + rIdx * 22
                graphics.drawCenteredString(font, "x${rew.count}", left + 168, rewY + 5, 0xFFE6EDF3.toInt())
            }
        } else if (editingAnnouncement != null) {
            val ann = editingAnnouncement!!
            val headerTitle = if (isCreatingNew) "Crear Nuevo Anuncio" else "Editando Anuncio: ${ann.title}"
            graphics.drawString(font, Component.literal(headerTitle), left + 14, top + 10, 0xFFF5C842.toInt())
            graphics.drawString(font, Component.literal("ID:"), left + 14, top + 38, 0xFF8B949E.toInt())
            graphics.drawString(font, Component.literal("Título:"), left + 14, top + 64, 0xFF8B949E.toInt())
            graphics.drawString(font, Component.literal("Color:"), left + 40, top + 88, 0xFF8B949E.toInt())
            graphics.drawString(font, Component.literal("Duración: ${annHudDuration}s"), left + 268, top + 178, 0xFFE6EDF3.toInt())
        } else if (activeTab == "hitos") {
            renderHitosListOverlay(graphics, left, top, panelW, panelH)
        } else {
            val listTop = top + 36
            val itemH = 34
            val visibleRows = maxOf(1, (panelH - 46) / itemH)
            val snapshot = BulletinClientData.snapshot

            if (activeTab == "announcements") {
                val list = snapshot.document.announcements
                list.drop(scrollRow).take(visibleRows).forEachIndexed { idx, ann ->
                    val itemY = listTop + idx * itemH
                    val rowRight = left + panelW - 74
                    graphics.fill(left + 12, itemY, rowRight, itemY + 28, 0xFF161B22.toInt())
                    graphics.renderOutline(left + 12, itemY, rowRight - (left + 12), 28, 0xFF2D333B.toInt())
                    graphics.drawString(font, Component.literal(ann.title), left + 18, itemY + 6, 0xFFF5C842.toInt())
                    val sub = "ID: ${ann.id} | Chat: ${if (ann.showInChat) "Sí" else "No"} | HUD: ${if (ann.showInHud) "${ann.hudDurationSeconds}s" else "No"}"
                    graphics.drawString(font, Component.literal(sub), left + 18, itemY + 16, 0xFF8B949E.toInt())
                }
            } else {
                val list = snapshot.document.missions
                list.drop(scrollRow).take(visibleRows).forEachIndexed { idx, mis ->
                    val itemY = listTop + idx * itemH
                    val rowRight = left + panelW - 74
                    graphics.fill(left + 12, itemY, rowRight, itemY + 28, 0xFF161B22.toInt())
                    graphics.renderOutline(left + 12, itemY, rowRight - (left + 12), 28, 0xFF2D333B.toInt())
                    val scopeTag = if (mis.isGuildMission) "[Gremio] " else ""
                    val catTag = "[${mis.resolvedCategoryTag()}] "
                    val titleColor = if (mis.isGuildMission) 0xFFA78BFA.toInt() else 0xFF38BDF8.toInt()
                    graphics.drawString(font, Component.literal("$catTag$scopeTag${mis.title}"), left + 18, itemY + 6, titleColor)
                    val objDesc = mis.objectives.firstOrNull()?.let { CleanMissionDisplay.describeObjective(it, mis.isGuildMission) } ?: "Sin objetivos"
                    val sub = "ID: ${mis.id} | $objDesc | Recompensa: ${mis.moneyReward}$"
                    graphics.drawString(font, Component.literal(font.plainSubstrByWidth(sub, rowRight - (left + 24))), left + 18, itemY + 16, 0xFF8B949E.toInt())
                }
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick)

        // RENDERIZADO DEL MENÚ DESPLEGABLE FLOTANTE (Encima de todos los widgets)
        val activeDrop = openDropdown
        if (activeDrop != null) {
            val visCount = activeDrop.maxVisible(height)
            val totalH = visCount * activeDrop.optH
            val dropTop = activeDrop.effectiveTop(height)
            val dropRight = activeDrop.x + activeDrop.w
            val dropBottom = dropTop + totalH

            graphics.pose().pushPose()
            graphics.pose().translate(0f, 0f, 450f)
            graphics.fill(activeDrop.x - 1, dropTop - 1, dropRight + 1, dropBottom + 1, 0xFF0E1117.toInt())
            graphics.fill(activeDrop.x, dropTop, dropRight, dropBottom, 0xF8161B22.toInt())
            graphics.renderOutline(activeDrop.x - 1, dropTop - 1, activeDrop.w + 2, totalH + 2, 0xFF2D333B.toInt())

            activeDrop.options.drop(activeDrop.scrollOffset).take(visCount).forEachIndexed { i, opt ->
                val rowY = dropTop + i * activeDrop.optH
                val isHovered = mouseX >= activeDrop.x && mouseX <= dropRight && mouseY >= rowY && mouseY < rowY + activeDrop.optH
                if (isHovered) {
                    graphics.fill(activeDrop.x, rowY, dropRight, rowY + activeDrop.optH, 0xFF1F2733.toInt())
                }
                val clipped = font.plainSubstrByWidth(opt.label, activeDrop.w - 14)
                graphics.drawString(font, clipped, activeDrop.x + 6, rowY + 5, if (isHovered) 0xFF38BDF8.toInt() else 0xFFE6EDF3.toInt())
            }

            if (activeDrop.options.size > visCount) {
                val barW = 3
                val trackX = dropRight - barW - 1
                val ratio = visCount.toFloat() / activeDrop.options.size.toFloat()
                val thumbH = maxOf(10, (totalH * ratio).toInt())
                val maxDropScroll = (activeDrop.options.size - visCount).coerceAtLeast(1)
                val thumbY = dropTop + ((totalH - thumbH) * (activeDrop.scrollOffset.toFloat() / maxDropScroll.toFloat())).toInt()
                graphics.fill(trackX, dropTop + 1, trackX + barW, dropBottom - 1, 0xFF0E1117.toInt())
                graphics.fill(trackX, thumbY, trackX + barW, thumbY + thumbH, 0xFF38BDF8.toInt())
            }
            graphics.pose().popPose()
        }
    }

    private fun renderHitosListOverlay(graphics: GuiGraphics, left: Int, top: Int, panelW: Int, panelH: Int) {
        val barTop = top + 30
        val g = hitosHeaderGeometry(left, panelW)

        // Global config bar background
        graphics.fill(left + 8, barTop, left + panelW - 8, barTop + 38, 0xFF17201E.toInt())
        graphics.renderOutline(left + 8, barTop, panelW - 16, 38, 0xFF2B3835.toInt())

        graphics.drawString(font, "Titulo:", left + 12, barTop + 7, 0xFFB0BDB7.toInt(), false)
        graphics.drawString(font, "Dia 1:", g.d1LblX, barTop + 7, 0xFFB0BDB7.toInt(), false)

        val cfg = HitosManager.config
        val progCount = cfg.progressionMilestones().size
        val evCount = cfg.milestones.size - progCount
        val infoRightText = if (hitosGlobalStatusMsg.isNotBlank()) {
            hitosGlobalStatusMsg
        } else {
            "$progCount progreso | $evCount eventos (Rueda raton: scroll)"
        }
        val noticeText = "Dia actual efectivo: Dia ${cfg.currentServerDay()} (${cfg.serverStartDate})"
        graphics.drawString(font, noticeText, left + 12, barTop + 25, 0xFF94A3B8.toInt(), false)
        graphics.drawString(
            font,
            font.plainSubstrByWidth(infoRightText, panelW / 2),
            left + panelW - font.width(font.plainSubstrByWidth(infoRightText, panelW / 2)) - 12,
            barTop + 25,
            if (hitosGlobalStatusMsg.isNotBlank()) hitosGlobalStatusColor else 0xFFFCD34D.toInt(),
            false
        )

        val listTop = top + 72
        val itemH = 34
        val visibleRows = maxOf(1, (panelH - 82) / itemH)
        val rowRight = left + panelW - 144

        cfg.milestones.drop(scrollRow).take(visibleRows).forEachIndexed { idx, m ->
            val itemY = listTop + idx * itemH
            val isProg = m.isProgressMilestone()
            val accent = if (isProg) 0xFFF59E0B.toInt() else 0xFF38BDF8.toInt()

            graphics.fill(left + 10, itemY, rowRight, itemY + 29, 0xFF1B2321.toInt())
            graphics.fill(left + 10, itemY, left + 12, itemY + 29, accent)

            // Icono 16x16
            graphics.fill(left + 15, itemY + 5, left + 35, itemY + 25, 0xFF0E1316.toInt())
            renderItemIcon(graphics, m.effectiveIconItemId(), left + 17, itemY + 7)

            // Tag + Titulo + Fechas
            val tagText = if (isProg) "[PROGRESO]" else "[EVENTO]"
            val tagW = font.width(tagText) + 4
            graphics.fill(left + 39, itemY + 4, left + 39 + tagW, itemY + 13, if (isProg) 0xFF3A280A.toInt() else 0xFF0C4A6E.toInt())
            graphics.drawString(font, tagText, left + 41, itemY + 5, if (isProg) 0xFFFCD34D.toInt() else 0xFF7DD3FC.toInt(), false)

            val boostStr = when {
                !isProg -> ""
                m.hasCatchupXpBoost() -> " | ${m.formatXpBoostLabel()}"
                else -> " | Sin Boost"
            }
            val dateInfo = (m.dateRange.ifBlank { "Dia ${m.dayOffset}" }) + boostStr
            val dateW = font.width(dateInfo)
            val titleAvail = maxOf(40, (rowRight - (left + 44 + tagW) - dateW - 10))
            graphics.drawString(font, font.plainSubstrByWidth(m.title, titleAvail), left + 44 + tagW, itemY + 5, 0xFFFFFFFF.toInt(), false)
            graphics.drawString(font, dateInfo, rowRight - dateW - 6, itemY + 5, if (isProg) 0xFFFDE68A.toInt() else 0xFF7DD3FC.toInt(), false)

            // Fila 2: Lider / Recompensa
            val leader = m.leaderOrBoss.ifBlank { m.subtitle }
            val rewardTxt = if (isProg) m.rewardSummary.ifBlank { "Sin resumen" } else m.subtitle
            val row2 = "ID: ${m.id} | $leader | Rec: $rewardTxt"
            graphics.drawString(font, font.plainSubstrByWidth(row2, rowRight - (left + 40) - 6), left + 39, itemY + 17, 0xFF94A3B8.toInt(), false)
        }

        if (cfg.milestones.size > visibleRows) {
            val scrollInfo = "${scrollRow + 1}-${minOf(cfg.milestones.size, scrollRow + visibleRows)} de ${cfg.milestones.size}"
            graphics.drawCenteredString(font, scrollInfo, left + panelW / 2, top + panelH - 11, 0xFF94A3B8.toInt())
        }
    }

    private fun renderHitoEditorOverlay(graphics: GuiGraphics, left: Int, top: Int, panelW: Int, panelH: Int) {
        val draft = buildDraftHitoMilestone()
        val previewW = 180
        val previewX = left + panelW - previewW - 10
        val formX = left + 10
        val formW = panelW - previewW - 28
        val bodyTop = top + 30

        val headerTitle = if (isCreatingNew) {
            "Crear Nuevo Hito"
        } else {
            "Editando Hito: ${draft.title.ifBlank { draft.id }}"
        }
        graphics.drawString(font, Component.literal(headerTitle), left + 12, top + 9, 0xFFF59E0B.toInt())
        val origLabel = if (editingHitoOriginalId.isBlank()) "Nuevo" else "Original: $editingHitoOriginalId"
        graphics.drawString(font, Component.literal(origLabel), left + panelW - 140, top + 9, 0xFF94A3B8.toInt())

        // Etiquetas de filas izquierda
        graphics.drawString(font, "ID:", formX, bodyTop + 6, 0xFFB0BDB7.toInt(), false)
        graphics.drawString(font, "Titulo:", formX, bodyTop + 27, 0xFFB0BDB7.toInt(), false)

        val halfW = (formW - 68) / 2
        val subLblX = formX + 34 + halfW + 6
        graphics.drawString(font, "Lider:", formX, bodyTop + 48, 0xFFB0BDB7.toInt(), false)
        graphics.drawString(font, "Sub:", subLblX, bodyTop + 48, 0xFFB0BDB7.toInt(), false)

        val durX = formX + 86
        graphics.drawString(font, "Dia:", formX, bodyTop + 69, 0xFFB0BDB7.toInt(), false)
        graphics.drawString(font, "Dur:", durX, bodyTop + 69, 0xFFB0BDB7.toInt(), false)
        graphics.drawCenteredString(font, "${hitoDurationDays}d", durX + 50, bodyTop + 69, 0xFFFFFFFF.toInt())

        // Fila 4: Slot visual del icono + etiqueta Icono
        val y4 = bodyTop + 86
        graphics.fill(formX, y4 - 1, formX + 18, y4 + 17, 0xFF0B0E13.toInt())
        graphics.renderOutline(formX, y4 - 1, 18, 18, 0xFF334155.toInt())
        renderItemIcon(graphics, draft.effectiveIconItemId(), formX + 1, y4)
        graphics.drawString(font, "Icono:", formX + 22, y4 + 4, 0xFFB0BDB7.toInt(), false)

        graphics.drawString(font, "Desc:", formX, bodyTop + 112, 0xFFB0BDB7.toInt(), false)
        graphics.drawString(font, "Resum:", formX, bodyTop + 133, 0xFFB0BDB7.toInt(), false)
        graphics.drawString(font, "Cobble$:", formX, bodyTop + 154, 0xFFB0BDB7.toInt(), false)

        val cmdHeaderY = bodyTop + 170
        val cmdTop = cmdHeaderY + 13
        val footerTop = top + panelH - 26
        val maxVisCmds = (((footerTop - cmdTop - 4) / 20)).coerceIn(1, 3)
        val cmdScrollNote = if (hitoRewardCommands.size > maxVisCmds) " (Rueda: scroll ${hitoCmdScroll + 1}/${hitoRewardCommands.size})" else ""
        graphics.drawString(font, "Comandos / Items (${hitoRewardCommands.size})$cmdScrollNote", formX, cmdHeaderY + 2, 0xFFFDE68A.toInt(), false)

        // Footer error o preview fecha
        val footerInfo = hitoStatusMsg.ifBlank {
            "Rango calculado: ${draft.dateRange}"
        }
        val footerCol = if (hitoStatusMsg.isNotBlank()) 0xFFEF4444.toInt() else 0xFF94A3B8.toInt()
        graphics.drawString(font, font.plainSubstrByWidth(footerInfo, formW + 10), left + 10, footerTop + 5, footerCol, false)

        // ==================== COLUMNA DERECHA: VISTA PREVIA VISUAL 1:1 ====================
        graphics.fill(previewX - 6, bodyTop - 2, left + panelW - 6, footerTop - 6, 0xFF0E1318.toInt())
        graphics.renderOutline(previewX - 6, bodyTop - 2, previewW + 8, footerTop - bodyTop - 4, 0xFF283340.toInt())

        graphics.drawString(font, "VISTA PREVIA", previewX, bodyTop + 4, 0xFFFCD34D.toInt(), false)

        // Mini barra de cronograma + nodo arriba de la tarjeta
        val barY = bodyTop + 21
        graphics.fill(previewX + 6, barY - 2, previewX + previewW - 6, barY + 2, 0xFF2B3440.toInt())
        graphics.fill(previewX + 6, barY - 1, previewX + previewW / 2, barY + 1, 0xFFFBBF24.toInt())

        val isProgression = draft.isProgressMilestone()
        val isUnlocked = isProgression && hitoPreviewState == 1
        val isOverdue = isProgression && hitoPreviewState == 2
        val isInWindow = isProgression && hitoPreviewState == 0

        val cardAccent = when {
            !isProgression -> 0xFF38BDF8.toInt()
            isUnlocked -> 0xFF22C55E.toInt()
            isOverdue -> 0xFFEF4444.toInt()
            isInWindow -> 0xFFF59E0B.toInt()
            else -> 0xFF475569.toInt()
        }

        val centerX = previewX + previewW / 2
        graphics.fill(centerX - 1, barY + 2, centerX + 1, bodyTop + 32, cardAccent)
        graphics.fill(centerX - 4, barY - 4, centerX + 4, barY + 4, 0xFF0E1116.toInt())
        graphics.fill(centerX - 2, barY - 2, centerX + 2, barY + 2, cardAccent)

        // Tarjeta 166x74 identica a HitosTimelineScreen
        val cardW = 166
        val cardH = 74
        val cardX = previewX + (previewW - cardW) / 2
        val cardY = bodyTop + 32
        renderPreviewMilestoneCard(graphics, draft, cardX, cardY, cardW, cardH, isProgression, isUnlocked, isOverdue, isInWindow)

        // Caja de Tooltip en vivo debajo de la tarjeta
        val tipTop = cardY + cardH + 6
        val tipBottom = footerTop - 10
        val tipX = cardX
        val tipW = cardW
        if (tipBottom - tipTop >= 42) {
            graphics.fill(tipX, tipTop, tipX + tipW, tipBottom, 0xF0100816.toInt())
            graphics.renderOutline(tipX, tipTop, tipW, tipBottom - tipTop, 0xFF4C3575.toInt())

            graphics.enableScissor(tipX + 2, tipTop + 2, tipX + tipW - 2, tipBottom - 2)
            var ty = tipTop + 4
            val maxLineW = tipW - 10

            graphics.drawString(font, font.plainSubstrByWidth("§6§l${draft.title.ifBlank { "(Sin titulo)" }}", maxLineW), tipX + 5, ty, 0xFFFFFFFF.toInt(), false)
            ty += 10

            val roleLabel = if (isProgression) "Lider / Jefe" else "Tipo"
            val leaderTxt = "§e$roleLabel: §f${draft.leaderOrBoss.ifBlank { draft.subtitle }}"
            graphics.drawString(font, font.plainSubstrByWidth(leaderTxt, maxLineW), tipX + 5, ty, 0xFFFFFFFF.toInt(), false)
            ty += 9

            graphics.drawString(font, font.plainSubstrByWidth("§aCronograma: §f${draft.dateRange}", maxLineW), tipX + 5, ty, 0xFFFFFFFF.toInt(), false)
            ty += 11

            if (draft.description.isNotBlank()) {
                val descWrapped = font.split(Component.literal("§7${draft.description}"), maxLineW)
                for (line in descWrapped.take(4)) {
                    if (ty + 9 > tipBottom - 12) break
                    graphics.drawString(font, line, tipX + 5, ty, 0xFFCBD5E1.toInt(), false)
                    ty += 9
                }
                ty += 2
            }

            if (isProgression && draft.rewardSummary.isNotBlank() && ty + 9 <= tipBottom - 10) {
                val recWrapped = font.split(Component.literal("§bRec: §f${draft.rewardSummary}"), maxLineW)
                for (line in recWrapped.take(2)) {
                    if (ty + 9 > tipBottom - 10) break
                    graphics.drawString(font, line, tipX + 5, ty, 0xFFFFFFFF.toInt(), false)
                    ty += 9
                }
            }

            if (ty + 9 <= tipBottom - 3) {
                val boostStatus = when {
                    !isProgression -> "§bEvento opcional (no cuenta para %)"
                    draft.hasCatchupXpBoost() -> "§dBono retraso: §f${draft.formatXpBoostLabel()}"
                    else -> "§8Sin bono de alcance XP"
                }
                graphics.drawString(font, font.plainSubstrByWidth(boostStatus, maxLineW), tipX + 5, ty, 0xFFFFFFFF.toInt(), false)
            }
            graphics.disableScissor()
        }
    }

    private fun renderPreviewMilestoneCard(
        graphics: GuiGraphics,
        milestone: HitoMilestone,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        isProgression: Boolean,
        isUnlocked: Boolean,
        isOverdue: Boolean,
        isInWindow: Boolean
    ) {
        val bgColor = when {
            !isProgression -> 0xED15222D.toInt()
            isUnlocked -> 0xF015281F.toInt()
            isOverdue -> 0xED2A181B.toInt()
            isInWindow -> 0xF0252014.toInt()
            else -> 0xEB171B22.toInt()
        }
        val accentColor = when {
            !isProgression -> 0xFF38BDF8.toInt()
            isUnlocked -> 0xFF22C55E.toInt()
            isOverdue -> 0xFFEF4444.toInt()
            isInWindow -> 0xFFF59E0B.toInt()
            else -> 0xFF475569.toInt()
        }

        graphics.fill(x, y, x + w, y + h, bgColor)
        graphics.renderOutline(x, y, w, h, accentColor)
        graphics.fill(x + 1, y + 1, x + w - 1, y + 3, accentColor)

        val slotX = x + 6
        val slotY = y + 7
        graphics.fill(slotX, slotY, slotX + 20, slotY + 20, 0xFF0B0E13.toInt())
        graphics.renderOutline(slotX, slotY, 20, 20, 0xFF334155.toInt())
        renderItemIcon(graphics, milestone.effectiveIconItemId(), slotX + 2, slotY + 2)

        val orderTag = if (isProgression) "#1" else "EVENTO"
        val tagW = font.width(orderTag) + 6
        val tagX = x + w - tagW - 5
        val tagY = y + 6
        val tagBg = if (isProgression) 0xFF0F172A.toInt() else 0xFF0C4A6E.toInt()
        graphics.fill(tagX, tagY, tagX + tagW, tagY + 10, tagBg)
        graphics.drawString(font, orderTag, tagX + 3, tagY + 1, if (isProgression) 0xFFCBD5E1.toInt() else 0xFF7DD3FC.toInt(), false)

        val dateText = milestone.dateRange.ifBlank { "Dia ${milestone.dayOffset}" }
        val dateColor = when {
            !isProgression -> 0xFF7DD3FC.toInt()
            isUnlocked -> 0xFF86EFAC.toInt()
            isOverdue -> 0xFFFCA5A5.toInt()
            else -> 0xFFFCD34D.toInt()
        }
        val dateMaxW = w - 34 - tagW - 6
        graphics.drawString(font, font.plainSubstrByWidth(dateText, max(36, dateMaxW)), x + 30, y + 7, dateColor, false)

        val titleColor = if (isUnlocked) 0xFFFFFFFF.toInt() else 0xFFE2E8F0.toInt()
        graphics.drawString(font, font.plainSubstrByWidth(milestone.title.ifBlank { "(Sin titulo)" }, w - 36), x + 30, y + 18, titleColor, false)

        graphics.fill(x + 5, y + 30, x + w - 5, y + 31, 0x44FFFFFF.toInt())

        val leaderLine = if (isProgression) {
            "Lider: ${milestone.leaderOrBoss.ifBlank { milestone.subtitle }}"
        } else {
            milestone.leaderOrBoss.ifBlank { milestone.subtitle }
        }
        graphics.drawString(font, font.plainSubstrByWidth(leaderLine, w - 12), x + 6, y + 33, 0xFFCBD5E1.toInt(), false)

        val rewardLine = if (isProgression && milestone.rewardSummary.isNotBlank()) {
            "Rec: ${milestone.rewardSummary}"
        } else {
            milestone.subtitle
        }
        val rewardColor = if (isProgression && isUnlocked) 0xFF86EFAC.toInt() else 0xFF94A3B8.toInt()
        graphics.drawString(font, font.plainSubstrByWidth(rewardLine, w - 12), x + 6, y + 43, rewardColor, false)

        val btnX = x + 6
        val btnY = y + 54
        val btnW = w - 12
        val btnH = 15

        when {
            isProgression && isUnlocked -> {
                graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0xFF15803D.toInt())
                graphics.renderOutline(btnX, btnY, btnW, btnH, 0xFF4ADE80.toInt())
                graphics.drawCenteredString(font, "Reclamar Recompensa", btnX + btnW / 2, btnY + 4, 0xFFFFFFFF.toInt())
            }
            isProgression && isOverdue && milestone.hasCatchupXpBoost() -> {
                graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0xFF9A3412.toInt())
                graphics.renderOutline(btnX, btnY, btnW, btnH, 0xFFFB923C.toInt())
                graphics.drawCenteredString(font, "Activar ${milestone.formatXpBoostLabel()}", btnX + btnW / 2, btnY + 4, 0xFFFFFFFF.toInt())
            }
            else -> {
                val (statusText, statusColor) = when {
                    !isProgression -> Pair("Evento opcional", 0xFF7DD3FC.toInt())
                    isOverdue -> Pair("Atrasado", 0xFFFCA5A5.toInt())
                    isInWindow -> Pair("En curso (A tiempo)", 0xFFFDE68A.toInt())
                    else -> Pair("Proximo (Dia ${milestone.dayOffset})", 0xFF94A3B8.toInt())
                }
                graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0x660F172A.toInt())
                graphics.drawCenteredString(font, font.plainSubstrByWidth(statusText, btnW - 4), btnX + btnW / 2, btnY + 4, statusColor)
            }
        }
    }

    private fun renderItemIcon(graphics: GuiGraphics, rawItemId: String, x: Int, y: Int) {
        val trimmed = rawItemId.trim()
        if (trimmed.isNotBlank()) {
            val res = ResourceLocation.tryParse(trimmed)
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
}
