package com.desgraciamon.announcements

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class ObjectiveType {
    @SerializedName("GET_ITEM") GET_ITEM,
    @SerializedName("DEFEAT_POKEMON") DEFEAT_POKEMON,
    @SerializedName("CATCH_TYPE") CATCH_TYPE,
    @SerializedName("CATCH_SPECIES") CATCH_SPECIES,
    @SerializedName("OBTAIN_BADGE") OBTAIN_BADGE
}

data class Announcement(
    var id: String = "",
    var title: String = "",
    var body: String = "",
    var color: String = "#F5C842",
    var bodyColor: String = "#FFFFFF",
    var imageUrl: String = "",
    var showInChat: Boolean = true,
    var showInHud: Boolean = true,
    var hudDurationSeconds: Int = 15
)

data class Mission(
    var id: String = "",
    var title: String = "",
    var body: String = "",
    var color: String = "#68D7A2",
    var bodyColor: String = "#E5ECE8",
    var isGuildMission: Boolean = false,
    var targetUsesMemberCount: Boolean = false,
    var objective: ObjectiveType = ObjectiveType.GET_ITEM,
    var itemId: String = "minecraft:diamond",
    var pokemonId: String = "",
    var pokemonType: String = "",
    var badgeId: String = "",
    var amount: Int = 1,
    var daily: Boolean = true,
    var repeatable: Boolean = false,
    var expirationDate: String = "", // Formato "YYYY-MM-DD" o "YYYY-MM-DD HH:mm"
    var objectives: MutableList<MissionObjective> = mutableListOf(),
    var itemRewards: MutableList<ItemMissionReward> = mutableListOf(),
    var pokemonReward: PokemonMissionReward? = null,
    var pokemonRewards: MutableList<PokemonMissionReward> = mutableListOf(),
    var moneyReward: Long = 0L,
    var categoryTag: String = "",
    var dailySlot: Int = 0
) {
    @JvmOverloads
    fun isExpired(
        zoneId: ZoneId = DEFAULT_ZONE_ID,
        now: ZonedDateTime = ZonedDateTime.now(zoneId)
    ): Boolean {
        if (expirationDate.isBlank()) return false
        return runCatching {
            val localNow = now.withZoneSameInstant(zoneId)
            val trimmed = expirationDate.trim()
            if (trimmed.length == 10) {
                val date = LocalDate.parse(trimmed)
                localNow.toLocalDate().isAfter(date)
            } else {
                val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd['T'][ ]HH[:mm][:ss]")
                val parsed = LocalDateTime.parse(trimmed, dtf)
                localNow.toLocalDateTime().isAfter(parsed)
            }
        }.getOrDefault(false)
    }

    fun resolvedCategoryTag(): String {
        val explicit = categoryTag.trim().uppercase()
        if (explicit in VALID_CATEGORY_TAGS) return explicit
        when (dailySlot) {
            1 -> return "ITEMS"
            2 -> return "CAPTURA"
            3 -> return "COMBATE"
        }
        val activeObjectives = objectives.filter { it.amount > 0 }
        val types = if (activeObjectives.isNotEmpty()) {
            activeObjectives.map { it.type }.distinct()
        } else {
            listOf(objective)
        }
        val categories = types.map { type ->
            when (type) {
                ObjectiveType.GET_ITEM -> "ITEMS"
                ObjectiveType.CATCH_TYPE, ObjectiveType.CATCH_SPECIES -> "CAPTURA"
                ObjectiveType.DEFEAT_POKEMON -> "COMBATE"
                ObjectiveType.OBTAIN_BADGE -> "ESPECIAL"
            }
        }.distinct()
        return if (categories.size == 1) categories.first() else "ESPECIAL"
    }

    companion object {
        val DEFAULT_ZONE_ID: ZoneId = ZoneId.of("America/Caracas")
        val VALID_CATEGORY_TAGS: Set<String> = setOf("CAPTURA", "ITEMS", "COMBATE", "ESPECIAL")
    }
}

data class MissionObjective(
    var id: String = java.util.UUID.randomUUID().toString(),
    var type: ObjectiveType = ObjectiveType.GET_ITEM,
    var amount: Int = 1,
    var targetUsesMemberCount: Boolean = false,
    var itemId: String = "",
    var pokemonId: String = "",
    var pokemonType: String = "",
    var pokemonProperties: String = "",
    var badgeId: String = "",
    var consumeOnClaim: Boolean = false,
    var secondaryPokemonType: String = "",
    var minLevel: Int = 0,
    var minEvolutionStage: Int = 0
)

data class ItemMissionReward(
    var itemId: String = "minecraft:diamond",
    var count: Int = 1
)

data class PokemonMissionReward(
    var speciesId: String = "",
    var properties: String = ""
)

data class BulletinDocument(
    var schemaVersion: Int = 1,
    var announcements: MutableList<Announcement> = mutableListOf(),
    var missions: MutableList<Mission> = mutableListOf()
)

data class PlayerBulletinState(
    val readIds: MutableSet<String> = mutableSetOf(),
    val readMissionIds: MutableSet<String> = mutableSetOf(),
    val knownAnnouncementIds: MutableSet<String> = mutableSetOf(),
    val knownMissionIds: MutableSet<String> = mutableSetOf(),
    val missionProgress: MutableMap<String, Int> = mutableMapOf(),
    val objectiveProgress: MutableMap<String, MutableMap<String, Int>> = mutableMapOf(),
    val completedMissions: MutableSet<String> = mutableSetOf(),
    val claimedMissions: MutableSet<String> = mutableSetOf(),
    var dailyStamp: String = "",
    var playerGuildId: String = "",
    var guildMemberCount: Int = 1,
    var adminMode: Boolean = false,
    var dailyBonusClaimedCycle: String = ""
)

data class DailyCycleConfig(
    var timezone: String = "America/Caracas",
    var resetTime: String = "08:00",
    var slot1UnlockTime: String = "08:00",
    var slot2UnlockTime: String = "13:30",
    var slot3UnlockTime: String = "19:00",
    var seed: Long = 20261118L
)

data class DailyUiThemeConfig(
    var bgBaseHex: String = "#0E1117",
    var bgCardHex: String = "#161B22",
    var borderHex: String = "#2D333B",
    var dailyAccentHex: String = "#38BDF8",
    var textColorHex: String = "#E6EDF3",
    var mutedTextColorHex: String = "#8B949E",
    var missionAccentHex: String = "#38BDF8",
    var guildAccentHex: String = "#A78BFA",
    var permanentAccentHex: String = "#34D399",
    var completedAccentHex: String = "#6E7681",
    var notificationColorHex: String = "#38BDF8"
)

data class DailyBonusConfig(
    var title: String = "Recompensa del Dia",
    var moneyReward: Long = 2500L,
    var itemRewards: MutableList<ItemMissionReward> = mutableListOf(
        ItemMissionReward("cobblemon:rare_candy", 2),
        ItemMissionReward("cobblemon:ultra_ball", 5)
    )
)

data class DailyMissionsFile(
    var schemaVersion: Int = 1,
    var cycle: DailyCycleConfig = DailyCycleConfig(),
    var uiTheme: DailyUiThemeConfig = DailyUiThemeConfig(),
    var dailyBonus: DailyBonusConfig = DailyBonusConfig(),
    var itemsMissions: MutableList<Mission> = mutableListOf(),
    var captureMissions: MutableList<Mission> = mutableListOf(),
    var combatMissions: MutableList<Mission> = mutableListOf()
) {
    fun allMissions(): List<Mission> = itemsMissions + captureMissions + combatMissions
}

data class DailySlotSnapshot(
    val slotIndex: Int = 1,
    val slotType: String = "ITEMS",
    val unlocked: Boolean = false,
    val unlockRemainingSeconds: Long = 0L,
    val mission: Mission? = null,
    val completed: Boolean = false,
    val claimed: Boolean = false
)

data class DailyBonusSnapshot(
    val completedCount: Int = 0,
    val totalSlots: Int = 3,
    val claimed: Boolean = false,
    val moneyReward: Long = 0L,
    val itemRewards: List<ItemMissionReward> = emptyList()
)

data class DailyCycleSnapshot(
    val paused: Boolean = false,
    val cycleId: String = "",
    val resetRemainingSeconds: Long = 0L,
    val serverTimeEpochMs: Long = 0L,
    val slots: List<DailySlotSnapshot> = emptyList(),
    val bonus: DailyBonusSnapshot = DailyBonusSnapshot()
)

data class DailyPersistedState(
    var schemaVersion: Int = 1,
    var activeCycleId: String = "",
    var paused: Boolean = false,
    var shuffleSeed: Long = 20261118L,
    var slotEpochs: MutableMap<Int, Long> = mutableMapOf(1 to 0L, 2 to 0L, 3 to 0L),
    var slotCursors: MutableMap<Int, Int> = mutableMapOf(1 to 0, 2 to 0, 3 to 0),
    var slotMissionIds: MutableMap<Int, String> = mutableMapOf(),
    var slotEpochAvoidIds: MutableMap<Int, String> = mutableMapOf(),
    var forceOpenedSlots: MutableSet<Int> = mutableSetOf(),
    var announcedSlotsInCycle: MutableSet<Int> = mutableSetOf()
)

data class ClientBulletinSnapshot(
    val document: BulletinDocument = BulletinDocument(),
    val state: PlayerBulletinState = PlayerBulletinState(),
    val newAnnouncementCount: Int = 0,
    val newMissionCount: Int = 0,
    val newAnnouncementIds: List<String> = emptyList(),
    val dailyCycle: DailyCycleSnapshot = DailyCycleSnapshot(),
    val uiTheme: DailyUiThemeConfig = DailyUiThemeConfig()
)

object BulletinJson {
    val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun decode(json: String): BulletinDocument = gson.fromJson(json, BulletinDocument::class.java)
        ?: BulletinDocument()

    fun encode(document: BulletinDocument): String = gson.toJson(document)

    fun decodeDailyFile(json: String): DailyMissionsFile = gson.fromJson(json, DailyMissionsFile::class.java)
        ?: DailyMissionsFile()

    fun encodeDailyFile(file: DailyMissionsFile): String = gson.toJson(file)
}
