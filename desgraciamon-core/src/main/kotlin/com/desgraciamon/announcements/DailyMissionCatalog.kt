package com.desgraciamon.announcements

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object DailyMissionCatalog {
    const val CLASSPATH_RESOURCE: String = "/data/desgraciamon4_announcements/daily-missions.json"
    const val FILE_NAME: String = "daily-missions.json"

    private val defaultLogger: Logger = LoggerFactory.getLogger("desgraciamon4-announcements")

    @Volatile
    var current: DailyMissionsFile = DailyMissionsFile()
        private set

    private val FORBIDDEN_ITEMS: Set<String> = setOf(
        "cobblemon:charcoal",
        "cobblemon:master_ball",
        "cobblemon:cherish_ball",
        "cobblemon:park_ball",
        "cobblemon:ancient_origin_ball"
    )

    val EXCLUDED_SPECIES: Set<String> = setOf(
        "arceus", "articuno", "azelf", "blacephalon", "brutebonnet", "buzzwole", "calyrex",
        "celebi", "celesteela", "chienpao", "chiyu", "cobalion", "cosmoem", "cosmog",
        "cresselia", "darkrai", "deoxys", "dialga", "diancie", "enamorus", "entei",
        "eternatus", "fezandipiti", "fluttermane", "genesect", "giratina", "glastrier",
        "gougingfire", "greattusk", "groudon", "guzzlord", "heatran", "hooh", "hoopa",
        "ironboulder", "ironbundle", "ironcrown", "ironhands", "ironjugulis", "ironleaves",
        "ironmoth", "ironthorns", "irontreads", "ironvaliant", "jirachi", "kartana", "keldeo",
        "koraidon", "kubfu", "kyogre", "kyurem", "landorus", "latias", "latios", "lugia",
        "lunala", "magearna", "manaphy", "marshadow", "melmetal", "meloetta", "meltan",
        "mesprit", "mew", "mewtwo", "miraidon", "moltres", "munkidori", "naganadel",
        "necrozma", "nihilego", "ogerpon", "okidogi", "palkia", "pecharunt", "pheromosa",
        "phione", "poipole", "ragingbolt", "raikou", "rayquaza", "regice", "regidrago",
        "regieleki", "regigigas", "regirock", "registeel", "reshiram", "roaringmoon",
        "sandyshocks", "screamtail", "shaymin", "silvally", "slitherwing", "solgaleo",
        "spectrier", "stakataka", "suicune", "tapubulu", "tapufini", "tapukoko", "tapulele",
        "terapagos", "terrakion", "thundurus", "tinglu", "tornadus", "typenull", "urshifu",
        "uxie", "victini", "virizion", "volcanion", "walkingwake", "wochien", "xerneas",
        "xurkitree", "yveltal", "zacian", "zamazenta", "zapdos", "zarude", "zekrom",
        "zeraora", "zygarde"
    )

    fun load(configFolder: Path, logger: Logger = defaultLogger): DailyMissionsFile {
        Files.createDirectories(configFolder)
        val targetFile = configFolder.resolve(FILE_NAME)
        if (Files.notExists(targetFile)) {
            seedFromClasspath(targetFile, logger)
        }

        val loaded = runCatching {
            Files.newBufferedReader(targetFile, StandardCharsets.UTF_8).use { reader ->
                loadFromReader(reader)
            }
        }.getOrElse { ex ->
            logger.error("Failed to read $targetFile; falling back to classpath seed", ex)
            loadFromClasspath(logger) ?: DailyMissionsFile()
        }

        val normalized = normalizeAndFilter(loaded, logger)
        current = normalized
        return normalized
    }

    fun loadFromReader(reader: Reader): DailyMissionsFile {
        return BulletinJson.gson.fromJson(reader, DailyMissionsFile::class.java) ?: DailyMissionsFile()
    }

    fun loadFromClasspath(logger: Logger = defaultLogger): DailyMissionsFile? {
        val stream = DailyMissionCatalog::class.java.getResourceAsStream(CLASSPATH_RESOURCE)
        if (stream == null) {
            logger.error("Missing classpath resource $CLASSPATH_RESOURCE")
            return null
        }
        return stream.use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                loadFromReader(reader)
            }
        }
    }

    fun poolForSlot(slotIndex: Int): List<Mission> = when (slotIndex) {
        1 -> current.itemsMissions
        2 -> current.captureMissions
        3 -> current.combatMissions
        else -> emptyList()
    }

    fun findMissionById(id: String): Mission? {
        if (id.isBlank()) return null
        return current.itemsMissions.firstOrNull { it.id == id }
            ?: current.captureMissions.firstOrNull { it.id == id }
            ?: current.combatMissions.firstOrNull { it.id == id }
    }

    fun allDailyMissionIds(): Set<String> = current.allMissions().mapTo(mutableSetOf()) { it.id }
    fun cycleConfig(): DailyCycleConfig = current.cycle
    fun uiTheme(): DailyUiThemeConfig = current.uiTheme
    fun dailyBonus(): DailyBonusConfig = current.dailyBonus

    fun validateStrict(file: DailyMissionsFile): List<String> {
        val errors = mutableListOf<String>()
        if (file.schemaVersion != 1) {
            errors.add("Unsupported schemaVersion: ${file.schemaVersion}")
        }
        if (file.itemsMissions.isEmpty()) errors.add("itemsMissions is empty")
        if (file.captureMissions.isEmpty()) errors.add("captureMissions is empty")
        if (file.combatMissions.isEmpty()) errors.add("combatMissions is empty")

        if (containsEmoji(file.dailyBonus.title)) {
            errors.add("dailyBonus.title contains emoji")
        }

        val seenIds = mutableSetOf<String>()
        fun validatePool(list: List<Mission>, expectedSlot: Int, expectedTag: String) {
            for (mission in list) {
                if (mission.id.isBlank()) {
                    errors.add("Blank mission id in slot $expectedSlot")
                    continue
                }
                if (!seenIds.add(mission.id)) {
                    errors.add("Duplicate mission id: ${mission.id}")
                }
                if (!mission.daily) errors.add("${mission.id}: daily must be true")
                if (mission.repeatable) errors.add("${mission.id}: repeatable must be false")
                if (mission.isGuildMission) errors.add("${mission.id}: isGuildMission must be false")
                if (mission.dailySlot != expectedSlot) errors.add("${mission.id}: dailySlot=${mission.dailySlot}, expected $expectedSlot")
                if (mission.resolvedCategoryTag() != expectedTag) errors.add("${mission.id}: categoryTag=${mission.categoryTag}, expected $expectedTag")
                if (mission.pokemonReward != null || mission.pokemonRewards.isNotEmpty()) {
                    errors.add("${mission.id}: daily catalog missions must not grant pokemon rewards")
                }
                if (containsEmoji(mission.title) || containsEmoji(mission.body) || containsEmoji(mission.id)) {
                    errors.add("${mission.id}: contains emoji")
                }
                val objs = mission.objectives
                if (objs.isEmpty()) {
                    errors.add("${mission.id}: objectives is empty")
                }
                for (obj in objs) {
                    if (obj.amount <= 0) errors.add("${mission.id}: objective ${obj.id} amount <= 0")
                    val normalizedItem = obj.itemId.trim().lowercase()
                    if (normalizedItem in FORBIDDEN_ITEMS) {
                        errors.add("${mission.id}: forbidden itemId $normalizedItem")
                    }
                    val speciesShort = obj.pokemonId.trim().lowercase().substringAfter(':')
                    if (speciesShort.isNotEmpty() && speciesShort in EXCLUDED_SPECIES) {
                        errors.add("${mission.id}: excluded species $speciesShort")
                    }
                }
                for (reward in mission.itemRewards) {
                    val rewardItem = reward.itemId.trim().lowercase()
                    if (rewardItem in FORBIDDEN_ITEMS) {
                        errors.add("${mission.id}: forbidden reward itemId $rewardItem")
                    }
                }
            }
        }

        validatePool(file.itemsMissions, 1, "ITEMS")
        validatePool(file.captureMissions, 2, "CAPTURA")
        validatePool(file.combatMissions, 3, "COMBATE")
        return errors
    }

    fun containsEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in 0x1F000..0x1FAFF ||
                cp in 0x2600..0x27BF ||
                cp in 0x2300..0x23FF ||
                cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D ||
                cp == 0x2B50 || cp == 0x2B55 || cp == 0x2934 || cp == 0x2935
            ) {
                return true
            }
            i += Character.charCount(cp)
        }
        return false
    }

    private fun seedFromClasspath(targetFile: Path, logger: Logger) {
        val stream = DailyMissionCatalog::class.java.getResourceAsStream(CLASSPATH_RESOURCE)
        if (stream == null) {
            logger.warn("Could not find $CLASSPATH_RESOURCE to seed $targetFile; writing empty catalog.")
            val fallback = BulletinJson.encodeDailyFile(DailyMissionsFile())
            val tmp = Files.createTempFile(targetFile.parent, "daily_seed_", ".tmp")
            Files.writeString(tmp, fallback, StandardCharsets.UTF_8)
            Files.move(tmp, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return
        }
        stream.use { input ->
            val tmp = Files.createTempFile(targetFile.parent, "daily_seed_", ".tmp")
            Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            logger.info("Seeded default daily missions catalog to $targetFile")
        }
    }

    private fun normalizeAndFilter(file: DailyMissionsFile, logger: Logger): DailyMissionsFile {
        fun normalizeList(raw: List<Mission>?, slot: Int, tag: String): MutableList<Mission> {
            val out = mutableListOf<Mission>()
            raw.orEmpty().forEach { mission ->
                if (mission.id.isBlank()) return@forEach
                val hasExcluded = mission.objectives.any {
                    it.pokemonId.trim().lowercase().substringAfter(':') in EXCLUDED_SPECIES
                } || mission.pokemonId.trim().lowercase().substringAfter(':') in EXCLUDED_SPECIES
                if (hasExcluded) {
                    logger.warn("Skipping daily mission ${mission.id} referencing excluded species")
                    return@forEach
                }
                mission.daily = true
                mission.repeatable = false
                mission.isGuildMission = false
                mission.targetUsesMemberCount = false
                mission.dailySlot = slot
                if (mission.categoryTag.isBlank()) mission.categoryTag = tag
                if (mission.color.isBlank()) mission.color = file.uiTheme.dailyAccentHex.ifBlank { "#38BDF8" }
                if (mission.bodyColor.isBlank()) mission.bodyColor = file.uiTheme.textColorHex.ifBlank { "#E6EDF3" }
                out.add(mission)
            }
            return out
        }

        return DailyMissionsFile(
            schemaVersion = if (file.schemaVersion <= 0) 1 else file.schemaVersion,
            cycle = file.cycle,
            uiTheme = file.uiTheme,
            dailyBonus = file.dailyBonus,
            itemsMissions = normalizeList(file.itemsMissions, 1, "ITEMS"),
            captureMissions = normalizeList(file.captureMissions, 2, "CAPTURA"),
            combatMissions = normalizeList(file.combatMissions, 3, "COMBATE")
        )
    }
}
