package com.desgraciamon.badges

import com.google.gson.GsonBuilder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class HitoMilestone(
    var id: String,
    var title: String,
    var subtitle: String,
    var leaderOrBoss: String,
    var dayOffset: Int = 1,
    var durationDays: Int = 2,
    var dateRange: String = "",
    var startDate: String = "",
    var endDate: String = "",
    var description: String = "",
    var rewardSummary: String = "",
    var rewardCommands: MutableList<String>? = mutableListOf(),
    var rewardDollars: Long = 0L,
    var badgeItemId: String = "",
    var badgeDefId: String = "",
    var iconItemId: String = "",
    var iconEmoji: String = "",
    var progression: Boolean? = true,
    var allowCatchupXp: Boolean? = true,
    var catchupXpMultiplier: Double? = 1.5,
    var isCompleted: Boolean = false,
    var region: String = "kanto"
) {
    fun effectiveIconItemId(): String =
        iconItemId.ifBlank { badgeItemId }

    fun isProgressMilestone(): Boolean {
        progression?.let { return it }
        return badgeDefId.isNotBlank() || badgeItemId.isNotBlank() ||
            id.startsWith("gym", ignoreCase = true) || id.equals("league", ignoreCase = true)
    }

    fun effectiveXpMultiplier(): Double {
        if (!isProgressMilestone() || allowCatchupXp == false) return 1.0
        return (catchupXpMultiplier ?: 1.5).coerceIn(1.0, 10.0)
    }

    fun hasCatchupXpBoost(): Boolean =
        isProgressMilestone() && (allowCatchupXp ?: true) && effectiveXpMultiplier() > 1.001

    fun formatXpBoostLabel(): String {
        val mult = effectiveXpMultiplier()
        val formatted = if (mult % 1.0 == 0.0) {
            String.format(java.util.Locale.ROOT, "%.1f", mult)
        } else {
            String.format(java.util.Locale.ROOT, "%.2f", mult).trimEnd('0').trimEnd('.')
        }
        return "x$formatted XP"
    }

    fun normalize() {
        if (rewardCommands == null) rewardCommands = mutableListOf()
        if (progression == null) {
            progression = isProgressMilestone()
        }
        if (allowCatchupXp == null) {
            allowCatchupXp = true
        }
        if (catchupXpMultiplier == null || catchupXpMultiplier!! < 1.0) {
            catchupXpMultiplier = 1.5
        }
        @Suppress("USELESS_ELVIS")
        val rawRegion = (region ?: "").lowercase().trim()
        region = when {
            rawRegion in HitosConfig.REGION_ORDER -> rawRegion
            badgeDefId.startsWith("johto_", true) || id.startsWith("johto_", true) -> "johto"
            badgeDefId.startsWith("hoenn_", true) || id.startsWith("hoenn_", true) -> "hoenn"
            badgeDefId.startsWith("sinnoh_", true) || id.startsWith("sinnoh_", true) -> "sinnoh"
            else -> "kanto"
        }
        // Limpiar aliases viejos de medalla de liga Kanto
        if (id.equals("league", ignoreCase = true) && badgeDefId.equals("kanto_champion", ignoreCase = true)) {
            badgeDefId = "kanto_league_trophy"
            badgeItemId = "cobbleversebadges:kanto_league_trophy"
            iconItemId = "cobbleversebadges:kanto_league_trophy"
        }
    }
}

data class HitosConfig(
    var schemaVersion: Int = 3,
    var headerTitle: String = "CRONOGRAMA DE HITOS: DESGRACIAMON 3",
    var serverStartDate: String = "2026-11-01",
    var manualCurrentDay: Int = 0,
    var manualActiveRegion: String = "",
    var regionStartDays: MutableMap<String, Int>? = linkedMapOf(
        "kanto" to 1,
        "johto" to 22,
        "hoenn" to 40,
        "sinnoh" to 58
    ),
    var discordAdminRoleId: String = "",
    var serverDateNotice: String = "",
    var milestones: MutableList<HitoMilestone> = defaultMilestones().toMutableList()
) {
    fun ensureRegionStartDays(): MutableMap<String, Int> {
        val current = regionStartDays ?: linkedMapOf<String, Int>().also { regionStartDays = it }
        current.putIfAbsent("kanto", 1)
        current.putIfAbsent("johto", 22)
        current.putIfAbsent("hoenn", 40)
        current.putIfAbsent("sinnoh", 58)
        return current
    }

    fun startDayOfRegion(region: String): Int {
        val clean = region.lowercase().trim()
        return ensureRegionStartDays()[clean] ?: 1
    }

    fun activeRegion(): String {
        @Suppress("USELESS_ELVIS")
        val manual = (manualActiveRegion ?: "").lowercase().trim()
        if (manual in REGION_ORDER) return manual
        val day = currentServerDay()
        var chosen = "kanto"
        for (reg in REGION_ORDER) {
            if (day >= startDayOfRegion(reg)) {
                chosen = reg
            }
        }
        return chosen
    }

    fun unlockedRegions(): List<String> {
        val active = activeRegion()
        val activeIdx = REGION_ORDER.indexOf(active).coerceAtLeast(0)
        val day = currentServerDay()
        return REGION_ORDER.filterIndexed { idx, reg ->
            idx <= activeIdx || day >= startDayOfRegion(reg)
        }
    }

    fun milestonesForRegion(region: String): List<HitoMilestone> {
        val clean = region.lowercase().trim()
        return milestones.filter { it.region.equals(clean, ignoreCase = true) }
    }

    fun progressionMilestonesForRegion(region: String): List<HitoMilestone> =
        milestonesForRegion(region).filter { it.isProgressMilestone() }

    fun currentServerDay(now: ZonedDateTime = ZonedDateTime.now(SERVER_ZONE)): Int {
        if (manualCurrentDay > 0) return manualCurrentDay
        val baseDate = try {
            LocalDate.parse(serverStartDate)
        } catch (_: Exception) {
            return 1
        }
        val effectiveDate = now.withZoneSameInstant(SERVER_ZONE)
            .minusHours(DAY_ROLLOVER_HOUR.toLong())
            .toLocalDate()
        val diff = ChronoUnit.DAYS.between(baseDate, effectiveDate).toInt() + 1
        return diff.coerceAtLeast(1)
    }

    fun currentDayFraction(now: ZonedDateTime = ZonedDateTime.now(SERVER_ZONE)): Double {
        if (manualCurrentDay > 0) return 0.5
        val baseDate = try {
            LocalDate.parse(serverStartDate)
        } catch (_: Exception) {
            return 0.0
        }
        val shifted = now.withZoneSameInstant(SERVER_ZONE).minusHours(DAY_ROLLOVER_HOUR.toLong())
        if (shifted.toLocalDate().isBefore(baseDate)) return 0.0
        return (shifted.toLocalTime().toSecondOfDay() / 86400.0).coerceIn(0.0, 1.0)
    }

    fun currentServerDayExact(now: ZonedDateTime = ZonedDateTime.now(SERVER_ZONE)): Double =
        currentServerDay(now).toDouble() + currentDayFraction(now)

    fun endDayOf(m: HitoMilestone): Int =
        m.dayOffset.coerceAtLeast(1) + m.durationDays.coerceAtLeast(1) - 1

    fun progressionMilestones(): List<HitoMilestone> =
        milestones.filter { it.isProgressMilestone() }

    fun previewDateRange(dayOffset: Int, durationDays: Int): String {
        val baseDate = try {
            LocalDate.parse(serverStartDate)
        } catch (_: Exception) {
            LocalDate.now(SERVER_ZONE)
        }
        val safeStartDay = dayOffset.coerceAtLeast(1)
        val safeDur = durationDays.coerceAtLeast(1)
        val start = baseDate.plusDays((safeStartDay - 1).toLong())
        val end = start.plusDays((safeDur - 1).toLong())
        val spanishMonths = arrayOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")
        val startDayStr = String.format("%02d %s", start.dayOfMonth, spanishMonths[start.monthValue - 1])
        val endDayStr = String.format("%02d %s", end.dayOfMonth, spanishMonths[end.monthValue - 1])
        return if (start == end) {
            "Dia $safeStartDay | $startDayStr"
        } else {
            "Dia $safeStartDay-${safeStartDay + safeDur - 1} | $startDayStr - $endDayStr"
        }
    }

    fun recalculateDates() {
        @Suppress("USELESS_ELVIS")
        discordAdminRoleId = (discordAdminRoleId ?: "").trim()
        val baseDate = try {
            LocalDate.parse(serverStartDate)
        } catch (_: Exception) {
            LocalDate.now(SERVER_ZONE)
        }
        val spanishMonths = arrayOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")

        val snapshot = ArrayList(milestones)
        for (m in snapshot) {
            m.normalize()
            if (m.dayOffset <= 0) m.dayOffset = 1
            if (m.durationDays <= 0) m.durationDays = 1

            val start = baseDate.plusDays((m.dayOffset - 1).toLong())
            val end = start.plusDays((m.durationDays - 1).toLong())
            m.startDate = start.toString()
            m.endDate = end.toString()

            val startDayStr = String.format("%02d %s", start.dayOfMonth, spanishMonths[start.monthValue - 1])
            val endDayStr = String.format("%02d %s", end.dayOfMonth, spanishMonths[end.monthValue - 1])

            m.dateRange = if (start == end) {
                "Dia ${m.dayOffset} | $startDayStr"
            } else {
                "Dia ${m.dayOffset}-${m.dayOffset + m.durationDays - 1} | $startDayStr - $endDayStr"
            }
        }

        snapshot.sortBy { it.dayOffset }
        milestones = snapshot

        val lastEndDate = snapshot.mapNotNull {
            try { LocalDate.parse(it.endDate) } catch (_: Exception) { null }
        }.maxOrNull() ?: baseDate

        val startFormatted = String.format("%d %s", baseDate.dayOfMonth, spanishMonths[baseDate.monthValue - 1])
        val endFormatted = String.format("%d %s", lastEndDate.dayOfMonth, spanishMonths[lastEndDate.monthValue - 1])
        val dayNow = currentServerDay()
        serverDateNotice = "Dia 1: $startFormatted | Conclusion: $endFormatted | Dia actual: $dayNow"
    }

    companion object {
        val SERVER_ZONE: ZoneId = ZoneId.of("America/Caracas")
        const val DAY_ROLLOVER_HOUR: Int = 8
        val REGION_ORDER = listOf("kanto", "johto", "hoenn", "sinnoh")

        fun regionDisplayName(region: String): String = when (region.lowercase().trim()) {
            "kanto" -> "Kanto"
            "johto" -> "Johto"
            "hoenn" -> "Hoenn"
            "sinnoh" -> "Sinnoh"
            else -> region.replaceFirstChar { it.uppercase() }
        }

        fun defaultMilestonesForRegion(region: String): List<HitoMilestone> = when (region.lowercase().trim()) {
            "kanto" -> kantoMilestones()
            "johto" -> johtoMilestones()
            "hoenn" -> hoennMilestones()
            "sinnoh" -> sinnohMilestones()
            else -> emptyList()
        }

        fun defaultMilestones(): List<HitoMilestone> =
            kantoMilestones() + johtoMilestones() + hoennMilestones() + sinnohMilestones()

        private fun gymMilestone(
            id: String,
            title: String,
            subtitle: String,
            leader: String,
            dayOffset: Int,
            durationDays: Int,
            description: String,
            dollars: Long,
            rareCandies: Int,
            xpCandyTier: String,
            xpCandyCount: Int,
            badgeId: String,
            region: String
        ): HitoMilestone {
            val tierLabel = when (xpCandyTier) {
                "xl" -> "XP XL"
                "l" -> "XP L"
                else -> "XP M"
            }
            val cmds = mutableListOf<String>()
            if (rareCandies > 0) cmds.add("give {player} cobblemon:rare_candy $rareCandies")
            if (xpCandyCount > 0) cmds.add("give {player} cobblemon:exp_candy_$xpCandyTier $xpCandyCount")
            val dollarsStr = String.format(java.util.Locale.US, "%,d", dollars)
            return HitoMilestone(
                id = id,
                title = title,
                subtitle = subtitle,
                leaderOrBoss = leader,
                dayOffset = dayOffset,
                durationDays = durationDays,
                description = description,
                rewardSummary = "$dollarsStr CD + ${rareCandies}x Caramelo Raro + ${xpCandyCount}x Caramelo $tierLabel",
                rewardCommands = cmds,
                rewardDollars = dollars,
                badgeItemId = "cobbleversebadges:$badgeId",
                badgeDefId = badgeId,
                iconItemId = "cobbleversebadges:$badgeId",
                progression = true,
                region = region
            )
        }

        fun kantoMilestones(startDay: Int = 4): List<HitoMilestone> = listOf(
            HitoMilestone(
                id = "primeros_dias",
                title = "Primeros Dias",
                subtitle = "Bases y Equipos (Dias 1-3)",
                leaderOrBoss = "Preparacion Inicial",
                dayOffset = 1,
                durationDays = 3,
                description = "Los primeros 3 dias son para explorar el mapa, elegir donde construir sus bases y formar sus equipos Pokemon. La carrera oficial de gimnasios comienza el Dia 4.",
                rewardSummary = "Fase de preparacion",
                rewardCommands = mutableListOf(),
                rewardDollars = 0L,
                badgeItemId = "",
                badgeDefId = "",
                iconItemId = "minecraft:campfire",
                progression = false,
                allowCatchupXp = false,
                region = "kanto"
            ),
            gymMilestone("gym1", "Gimnasio Plateada", "Lider Brock", "Brock", startDay, 8, "Derrota a Brock para ganar la Medalla Roca.", 4500L, 2, "m", 2, "kanto_boulder_badge", "kanto"),
            gymMilestone("gym2", "Gimnasio Celeste", "Lider Misty", "Misty", startDay, 8, "Supera el gimnasio acuatico de Misty por la Medalla Cascada.", 5000L, 2, "m", 2, "kanto_cascade_badge", "kanto"),
            gymMilestone("gym3", "Gimnasio Carmin", "Lider Lt. Surge", "Lt. Surge", startDay, 8, "Resuelve los interruptores electricos de Lt. Surge por la Medalla Trueno.", 5500L, 2, "m", 2, "kanto_thunder_badge", "kanto"),
            gymMilestone("gym4", "Gimnasio Azulona", "Lider Erika", "Erika", startDay, 8, "Vence a Erika y conquista la Medalla Arcoiris.", 6000L, 2, "m", 2, "kanto_rainbow_badge", "kanto"),
            gymMilestone("gym5", "Gimnasio Fucsia", "Lider Koga", "Koga", startDay + 8, 8, "Supera el dojo ninja y las toxinas de Koga por la Medalla Alma.", 7000L, 3, "l", 3, "kanto_soul_badge", "kanto"),
            gymMilestone("gym6", "Gimnasio Azafran", "Lider Sabrina", "Sabrina", startDay + 8, 8, "Desafia el poder psiquico de Sabrina por la Medalla Pantano.", 7500L, 3, "l", 3, "kanto_marsh_badge", "kanto"),
            gymMilestone("gym7", "Gimnasio Canela", "Lider Blaine", "Blaine", startDay + 8, 8, "Resiste el calor infernal de Blaine por la Medalla Volcan.", 8000L, 3, "l", 3, "kanto_volcano_badge", "kanto"),
            gymMilestone("gym8", "Gimnasio Verde", "Lider Giovanni", "Giovanni", startDay + 8, 8, "Vence a Giovanni y consigue la Medalla Tierra.", 9000L, 3, "l", 3, "kanto_earth_badge", "kanto"),
            gymMilestone("league", "Liga Pokemon Kanto", "Alto Mando y Campeon", "Azul", startDay + 16, 2, "Derrota al Alto Mando y al Campeon de Kanto.", 20000L, 5, "xl", 4, "kanto_league_trophy", "kanto")
        )

        fun johtoMilestones(startDay: Int = 22): List<HitoMilestone> = listOf(
            gymMilestone("johto_gym1", "Gimnasio Malva", "Lider Pegaso", "Pegaso", startDay, 8, "Derrota a Pegaso en Ciudad Malva por la Medalla Cefiro.", 6000L, 2, "l", 3, "johto_zephyr_badge", "johto"),
            gymMilestone("johto_gym2", "Gimnasio Azalea", "Lider Anton", "Anton", startDay, 8, "Supera a Anton y gana la Medalla Colmena.", 6500L, 2, "l", 3, "johto_hive_badge", "johto"),
            gymMilestone("johto_gym3", "Gimnasio Trigal", "Lider Blanca", "Blanca", startDay, 8, "Resiste a la Miltank de Blanca por la Medalla Planicie.", 7000L, 2, "l", 3, "johto_plain_badge", "johto"),
            gymMilestone("johto_gym4", "Gimnasio Iris", "Lider Morti", "Morti", startDay, 8, "Enfrenta los espectros de Morti por la Medalla Niebla.", 8000L, 2, "l", 3, "johto_fog_badge", "johto"),
            gymMilestone("johto_gym5", "Gimnasio Orquidea", "Lider Anibal", "Anibal", startDay + 8, 8, "Vence a Anibal y gana la Medalla Tormenta.", 9000L, 3, "l", 4, "johto_storm_badge", "johto"),
            gymMilestone("johto_gym6", "Gimnasio Olivo", "Lider Yasmina", "Yasmina", startDay + 8, 8, "Rompe la defensa de Acero de Yasmina por la Medalla Mineral.", 10000L, 3, "l", 4, "johto_mineral_badge", "johto"),
            gymMilestone("johto_gym7", "Gimnasio Caoba", "Lider Fredo", "Fredo", startDay + 8, 8, "Supera el frio de Fredo por la Medalla Glaciar.", 10500L, 3, "l", 4, "johto_glacier_badge", "johto"),
            gymMilestone("johto_gym8", "Gimnasio Endrino", "Lider Debora", "Debora", startDay + 8, 8, "Vence a los dragones de Debora por la Medalla Dragon.", 11500L, 3, "l", 4, "johto_rising_badge", "johto"),
            gymMilestone("johto_league", "Liga Pokemon Johto", "Alto Mando y Campeon", "Lance", startDay + 16, 2, "Derrota al Alto Mando de Johto y al Campeon Lance.", 28000L, 6, "xl", 5, "johto_league_trophy", "johto")
        )

        fun hoennMilestones(startDay: Int = 40): List<HitoMilestone> = listOf(
            gymMilestone("hoenn_gym1", "Gimnasio Ferrica", "Lider Petra", "Petra", startDay, 8, "Derrota a Petra en Ciudad Ferrica por la Medalla Piedra.", 8000L, 3, "l", 3, "hoenn_stone_badge", "hoenn"),
            gymMilestone("hoenn_gym2", "Gimnasio Azuliza", "Lider Marcial", "Marcial", startDay, 8, "Vence a Marcial y reclama la Medalla Puno.", 8500L, 3, "l", 3, "hoenn_knuckle_badge", "hoenn"),
            gymMilestone("hoenn_gym3", "Gimnasio Malvalona", "Lider Erico", "Erico", startDay, 8, "Supera los circuitos de Erico por la Medalla Dinamo.", 9000L, 3, "l", 3, "hoenn_dynamo_badge", "hoenn"),
            gymMilestone("hoenn_gym4", "Gimnasio Lavacalda", "Lider Candela", "Candela", startDay, 8, "Soporta las llamas de Candela por la Medalla Calor.", 10000L, 3, "l", 3, "hoenn_heat_badge", "hoenn"),
            gymMilestone("hoenn_gym5", "Gimnasio Petalia", "Lider Norman", "Norman", startDay + 8, 8, "Enfrenta el poder de Norman por la Medalla Equilibrio.", 11000L, 4, "xl", 4, "hoenn_balance_badge", "hoenn"),
            gymMilestone("hoenn_gym6", "Gimnasio Arborada", "Lider Alana", "Alana", startDay + 8, 8, "Domina los cielos ante Alana por la Medalla Pluma.", 12000L, 4, "xl", 4, "hoenn_feather_badge", "hoenn"),
            gymMilestone("hoenn_gym7", "Gimnasio Algaria", "Lideres Vito y Leti", "Vito y Leti", startDay + 8, 8, "Vence al duo psiquico por la Medalla Mente.", 13000L, 4, "xl", 4, "hoenn_mind_badge", "hoenn"),
            gymMilestone("hoenn_gym8", "Gimnasio Arrecipolis", "Lider Plubio", "Plubio", startDay + 8, 8, "Supera al equipo de Agua de Plubio por la Medalla Lluvia.", 14000L, 4, "xl", 4, "hoenn_rain_badge", "hoenn"),
            gymMilestone("hoenn_league", "Liga Pokemon Hoenn", "Alto Mando y Campeon", "Maximo", startDay + 16, 2, "Supera al Alto Mando de Hoenn y al Campeon Maximo.", 36000L, 7, "xl", 6, "hoenn_league_trophy", "hoenn")
        )

        fun sinnohMilestones(startDay: Int = 58): List<HitoMilestone> = listOf(
            gymMilestone("sinnoh_gym1", "Gimnasio Pirita", "Lider Roco", "Roco", startDay, 8, "Derrota a Roco en Ciudad Pirita por la Medalla Lignito.", 10000L, 3, "l", 4, "sinnoh_coal_badge", "sinnoh"),
            gymMilestone("sinnoh_gym2", "Gimnasio Vetusta", "Lider Gardenia", "Gardenia", startDay, 8, "Vence al equipo Planta de Gardenia por la Medalla Bosque.", 10500L, 3, "l", 4, "sinnoh_forest_badge", "sinnoh"),
            gymMilestone("sinnoh_gym3", "Gimnasio Rocavelo", "Lider Brega", "Brega", startDay, 8, "Supera el combate de Brega por la Medalla Adoquin.", 11500L, 3, "l", 4, "sinnoh_cobble_badge", "sinnoh"),
            gymMilestone("sinnoh_gym4", "Gimnasio Pradera", "Lider Mananti", "Mananti", startDay, 8, "Derrota a Mananti por la Medalla Cienaga.", 12500L, 3, "l", 4, "sinnoh_fen_badge", "sinnoh"),
            gymMilestone("sinnoh_gym5", "Gimnasio Corazon", "Lider Fantina", "Fantina", startDay + 8, 8, "Supera los espectros de Fantina por la Medalla Reliquia.", 13500L, 4, "xl", 5, "sinnoh_relic_badge", "sinnoh"),
            gymMilestone("sinnoh_gym6", "Gimnasio Canal", "Lider Aceron", "Aceron", startDay + 8, 8, "Vence la muralla de Acero de Aceron por la Medalla Mina.", 14500L, 4, "xl", 5, "sinnoh_mine_badge", "sinnoh"),
            gymMilestone("sinnoh_gym7", "Gimnasio Puntaneva", "Lider Inverna", "Inverna", startDay + 8, 8, "Desafia el frio glacial de Inverna por la Medalla Carambano.", 15500L, 4, "xl", 5, "sinnoh_icicle_badge", "sinnoh"),
            gymMilestone("sinnoh_gym8", "Gimnasio Marina", "Lider Lectro", "Lectro", startDay + 8, 8, "Vence a Lectro y conquista la Medalla Faro.", 17000L, 4, "xl", 5, "sinnoh_beacon_badge", "sinnoh"),
            gymMilestone("sinnoh_league", "Liga Pokemon Sinnoh", "Alto Mando y Campeona", "Cintia", startDay + 16, 2, "Supera al Alto Mando de Sinnoh y derrota a la Campeona Cintia.", 45000L, 8, "xl", 8, "sinnoh_league_trophy", "sinnoh")
        )
    }
}

object HitosManager {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    val configFile = File("config/desgraciamon4_badges/hitos.json")
    var config: HitosConfig = HitosConfig()
        private set

    init {
        load()
    }

    fun matchesBadge(badgeId: String, itemId: String, m: HitoMilestone): Boolean {
        val cleanBadge = badgeId.lowercase()
        val cleanItem = itemId.lowercase()
        if (m.badgeDefId.isNotBlank() && m.badgeDefId.equals(cleanBadge, true)) return true
        if (m.badgeItemId.isNotBlank() && (m.badgeItemId.equals(cleanItem, true) || m.badgeItemId.substringAfter(':').equals(cleanBadge, true))) return true
        if (m.id.equals("league", true) && cleanBadge == "kanto_league_trophy") return true
        return false
    }

    fun hasUnlockedMilestone(unlockedBadges: Collection<String>, m: HitoMilestone): Boolean {
        if (!m.isProgressMilestone()) return false
        return unlockedBadges.any { unlocked ->
            val clean = unlocked.lowercase()
            m.badgeDefId.equals(clean, true) ||
                m.badgeItemId.substringAfter(':').equals(clean, true) ||
                (m.id.equals("league", true) && (clean == "kanto_league_trophy" || clean == "kanto_champion"))
        }
    }

    fun nextPendingProgression(unlockedBadges: Collection<String>): HitoMilestone? {
        return config.progressionMilestones().firstOrNull { !hasUnlockedMilestone(unlockedBadges, it) }
    }

    fun earliestOverdueCatchupMilestone(
        unlockedBadges: Collection<String>,
        currentDay: Int = config.currentServerDay()
    ): HitoMilestone? {
        return config.progressionMilestones().firstOrNull { m ->
            m.hasCatchupXpBoost() &&
                currentDay > config.endDayOf(m) &&
                !hasUnlockedMilestone(unlockedBadges, m)
        }
    }

    @Synchronized
    fun load() {
        runCatching {
            if (configFile.exists()) {
                configFile.reader(Charsets.UTF_8).use {
                    config = gson.fromJson(it, HitosConfig::class.java) ?: HitosConfig()
                }
            } else {
                config = HitosConfig()
            }
            config.ensureRegionStartDays()
            config.milestones.forEach { it.normalize() }
            // Migracion a esquema v3: Tramos de 8+8+2 dias (Opcion A) + recompensas reescaladas
            if (config.schemaVersion < 3) {
                val startMap = config.ensureRegionStartDays()
                startMap["kanto"] = 1
                startMap["johto"] = 22
                startMap["hoenn"] = 40
                startMap["sinnoh"] = 58
                val defaultsById = HitosConfig.defaultMilestones().associateBy { it.id.lowercase() }
                for (m in config.milestones) {
                    val def = defaultsById[m.id.lowercase()]
                    if (def != null) {
                        m.dayOffset = def.dayOffset
                        m.durationDays = def.durationDays
                        m.rewardDollars = def.rewardDollars
                        m.rewardCommands = def.rewardCommands?.toMutableList() ?: mutableListOf()
                        m.rewardSummary = def.rewardSummary
                    }
                }
                if (config.milestones.none { it.id.equals("primeros_dias", ignoreCase = true) }) {
                    defaultsById["primeros_dias"]?.let { config.milestones.add(0, it.copy()) }
                }
                config.schemaVersion = 3
            }
            for (reg in HitosConfig.REGION_ORDER) {
                if (config.milestonesForRegion(reg).isEmpty()) {
                    val startD = config.startDayOfRegion(reg)
                    val seeded = when (reg) {
                        "kanto" -> HitosConfig.kantoMilestones(startD + 3)
                        "johto" -> HitosConfig.johtoMilestones(startD)
                        "hoenn" -> HitosConfig.hoennMilestones(startD)
                        "sinnoh" -> HitosConfig.sinnohMilestones(startD)
                        else -> emptyList()
                    }
                    config.milestones.addAll(seeded)
                }
            }
            config.recalculateDates()
            save()
        }.onFailure {
            config = HitosConfig()
            config.recalculateDates()
        }
    }

    @Synchronized
    fun save() {
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writer(Charsets.UTF_8).use {
                gson.toJson(config, it)
            }
        }
    }

    @Synchronized
    fun toJson(): String {
        config.recalculateDates()
        return gson.toJson(config)
    }

    @Synchronized
    fun resetForDisconnect() {
        config = HitosConfig()
    }

    @Synchronized
    fun loadFromJson(jsonStr: String) {
        runCatching {
            val parsed = gson.fromJson(jsonStr, HitosConfig::class.java)
            if (parsed != null) {
                parsed.recalculateDates()
                config = parsed
            }
        }
    }

    fun setDia1(fecha: String, server: net.minecraft.server.MinecraftServer? = null): Boolean {
        return try {
            LocalDate.parse(fecha)
            config.serverStartDate = fecha
            config.recalculateDates()
            save()
            server?.let { syncAll(it) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setManualCurrentDay(day: Int, server: net.minecraft.server.MinecraftServer? = null) {
        config.manualCurrentDay = day.coerceAtLeast(0)
        config.recalculateDates()
        save()
        server?.let { syncAll(it) }
    }

    fun setManualActiveRegion(region: String, server: net.minecraft.server.MinecraftServer? = null): Boolean {
        val clean = region.lowercase().trim()
        if (clean == "auto" || clean == "automatico") {
            config.manualActiveRegion = ""
        } else if (clean in HitosConfig.REGION_ORDER) {
            config.manualActiveRegion = clean
        } else {
            return false
        }
        config.recalculateDates()
        save()
        server?.let { syncAll(it) }
        return true
    }

    fun setRegionStartDay(region: String, startDay: Int, server: net.minecraft.server.MinecraftServer? = null): Boolean {
        val clean = region.lowercase().trim()
        if (clean !in HitosConfig.REGION_ORDER || startDay < 1) return false
        val map = config.ensureRegionStartDays()
        val oldStart = map[clean] ?: 1
        val delta = startDay - oldStart
        map[clean] = startDay
        if (delta != 0) {
            for (m in config.milestones) {
                if (m.region.equals(clean, ignoreCase = true)) {
                    m.dayOffset = (m.dayOffset + delta).coerceAtLeast(1)
                }
            }
        }
        config.recalculateDates()
        save()
        server?.let { syncAll(it) }
        return true
    }

    fun setDiscordAdminRole(roleId: String, server: net.minecraft.server.MinecraftServer? = null) {
        val cleaned = roleId.replace(Regex("[^0-9]"), "")
        config.discordAdminRoleId = cleaned
        save()
        server?.let { syncAll(it) }
    }

    fun resolveDefaultIcon(id: String, iconItem: String, isProgression: Boolean): String {
        val trimmed = iconItem.trim()
        if (trimmed.isNotBlank() && !trimmed.equals("auto", ignoreCase = true)) {
            return trimmed
        }
        val match = HitosConfig.defaultMilestones().firstOrNull { it.id.equals(id, ignoreCase = true) }
        if (match != null && match.effectiveIconItemId().isNotBlank()) {
            return match.effectiveIconItemId()
        }
        return if (isProgression) "minecraft:nether_star" else "minecraft:clock"
    }

    fun addOrUpdateMilestone(
        id: String,
        dayOffset: Int,
        iconItem: String,
        title: String,
        subtitle: String = "",
        isProgression: Boolean = true,
        server: net.minecraft.server.MinecraftServer? = null
    ) {
        val resolvedIcon = resolveDefaultIcon(id, iconItem, isProgression)
        val existing = config.milestones.find { it.id.equals(id, ignoreCase = true) }
        if (existing != null) {
            existing.dayOffset = dayOffset
            existing.iconItemId = resolvedIcon
            existing.badgeItemId = if (isProgression) resolvedIcon else ""
            existing.title = title
            existing.progression = isProgression
            if (subtitle.isNotBlank()) existing.subtitle = subtitle
        } else {
            config.milestones.add(
                HitoMilestone(
                    id = id,
                    title = title,
                    subtitle = subtitle.ifBlank { if (isProgression) "Hito de Progreso" else "Evento Especial" },
                    leaderOrBoss = subtitle.ifBlank { if (isProgression) "Desafio" else "Evento" },
                    dayOffset = dayOffset,
                    durationDays = 2,
                    description = if (isProgression) "Hito activo en el cronograma de Desgraciamon." else "Evento especial del servidor (opcional para el progreso).",
                    rewardSummary = if (isProgression) "Recompensa por hito" else "Evento informativo",
                    badgeItemId = if (isProgression) resolvedIcon else "",
                    badgeDefId = if (isProgression) id else "",
                    iconItemId = resolvedIcon,
                    iconEmoji = "",
                    progression = isProgression
                )
            )
        }
        config.recalculateDates()
        save()
        server?.let { syncAll(it) }
    }

    fun removeMilestone(id: String, server: net.minecraft.server.MinecraftServer? = null): Boolean {
        val removed = config.milestones.removeIf { it.id.equals(id, ignoreCase = true) }
        if (removed) {
            config.recalculateDates()
            save()
            server?.let { syncAll(it) }
        }
        return removed
    }

    fun saveFullMilestone(originalId: String, updated: HitoMilestone, server: net.minecraft.server.MinecraftServer? = null) {
        updated.normalize()
        updated.dayOffset = updated.dayOffset.coerceAtLeast(1)
        updated.durationDays = updated.durationDays.coerceAtLeast(1)
        updated.rewardDollars = updated.rewardDollars.coerceAtLeast(0L)
        val isProg = updated.isProgressMilestone()
        if (updated.iconItemId.isBlank()) {
            updated.iconItemId = resolveDefaultIcon(updated.id, "", isProg)
        }
        if (originalId.isNotBlank() && !originalId.equals(updated.id, ignoreCase = true)) {
            config.milestones.removeIf { it.id.equals(originalId, ignoreCase = true) }
        }
        val idx = config.milestones.indexOfFirst { it.id.equals(updated.id, ignoreCase = true) }
        if (idx >= 0) {
            config.milestones[idx] = updated
        } else {
            config.milestones.add(updated)
        }
        config.recalculateDates()
        save()
        server?.let { syncAll(it) }
    }

    fun updateGlobalSettings(
        headerTitle: String,
        serverStartDate: String,
        manualCurrentDay: Int,
        server: net.minecraft.server.MinecraftServer? = null
    ): Boolean {
        val trimmedTitle = headerTitle.trim()
        if (trimmedTitle.isNotBlank()) {
            config.headerTitle = trimmedTitle
        }
        val trimmedDate = serverStartDate.trim()
        if (trimmedDate.isNotBlank()) {
            try {
                LocalDate.parse(trimmedDate)
                config.serverStartDate = trimmedDate
            } catch (_: Exception) {
                return false
            }
        }
        config.manualCurrentDay = manualCurrentDay.coerceAtLeast(0)
        config.recalculateDates()
        save()
        server?.let { syncAll(it) }
        return true
    }

    fun syncAll(server: net.minecraft.server.MinecraftServer) {
        for (player in server.playerList.players) {
            BadgeNetwork.syncHitosPlayer(player)
        }
    }

    @JvmStatic
    fun loadAndSync(server: net.minecraft.server.MinecraftServer? = null) {
        load()
        if (server != null) {
            syncAll(server)
        }
    }
}
