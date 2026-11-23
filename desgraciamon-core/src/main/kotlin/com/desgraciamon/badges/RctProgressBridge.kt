package com.desgraciamon.badges

import net.minecraft.server.level.ServerPlayer

data class RctPlayerSummary(
    val available: Boolean = false,
    val seriesId: String = "",
    val regionName: String = "",
    val levelCap: Int = 0,
    val gymsCompleted: Int = 0,
    val gymsTotal: Int = 8,
    val leagueCompleted: Boolean = false,
    val completedRegions: Set<String> = emptySet(),
    val defeatedTrainerIds: Set<String> = emptySet(),
    val defeatedBadgeIds: Set<String> = emptySet(),
    val summaryText: String = ""
)

object RctProgressBridge {

    fun regionFromSeries(seriesId: String): String {
        val clean = seriesId.lowercase().trim()
        return when {
            clean.contains("kanto") || clean.contains("radicalred") -> "Kanto"
            clean.contains("johto") -> "Johto"
            clean.contains("hoenn") -> "Hoenn"
            clean.contains("sinnoh") || clean.contains("bdsp") -> "Sinnoh"
            else -> ""
        }
    }

    fun readFor(player: ServerPlayer): RctPlayerSummary {
        return runCatching {
            val rctClass = Class.forName("com.gitlab.srcmc.rctmod.api.RCTMod")
            val rctInstance = rctClass.getMethod("getInstance").invoke(null) ?: return RctPlayerSummary()
            val tm = rctInstance.javaClass.getMethod("getTrainerManager").invoke(rctInstance) ?: return RctPlayerSummary()
            val getDataMethod = tm.javaClass.methods.firstOrNull {
                it.name == "getData" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(player.javaClass)
            }
            val pData = getDataMethod?.invoke(tm, player) ?: return RctPlayerSummary()

            val currentSeries = runCatching {
                (pData.javaClass.getMethod("getCurrentSeries").invoke(pData) as? String).orEmpty()
            }.getOrDefault("")

            val levelCap = runCatching {
                (pData.javaClass.getMethod("getLevelCap").invoke(pData) as? Number)?.toInt() ?: 0
            }.getOrDefault(0)

            val defeatedIds = LinkedHashSet<String>()
            runCatching {
                val ids = pData.javaClass.getMethod("getDefeatedTrainerIds").invoke(pData) as? Collection<*>
                ids?.forEach { if (it is String) defeatedIds.add(it.lowercase().trim()) }
            }

            val completedRegions = LinkedHashSet<String>()
            runCatching {
                val map = pData.javaClass.getMethod("getCompletedSeries").invoke(pData) as? Map<*, *>
                map?.forEach { (k, v) ->
                    val count = (v as? Number)?.toInt() ?: 0
                    if (k is String && count > 0) {
                        val reg = regionFromSeries(k)
                        if (reg.isNotBlank()) completedRegions.add(reg)
                    }
                }
            }

            val defeatedBadgeIds = LinkedHashSet<String>()
            for (tid in defeatedIds) {
                GymBadges.findByLeader(tid)?.let { defeatedBadgeIds.add(it.id.lowercase()) }
            }
            for (reg in completedRegions) {
                GymBadges.badgesForRegion(reg).forEach { defeatedBadgeIds.add(it.id.lowercase()) }
            }

            // Resolve active region from current series, or fallback to latest defeated trainer's region
            var activeRegion = regionFromSeries(currentSeries)
            if (activeRegion.isBlank()) {
                val matchedRegions = defeatedIds.mapNotNull { GymBadges.findByLeader(it)?.region }.distinct()
                activeRegion = when {
                    "Sinnoh" in matchedRegions -> "Sinnoh"
                    "Hoenn" in matchedRegions -> "Hoenn"
                    "Johto" in matchedRegions -> "Johto"
                    "Kanto" in matchedRegions -> "Kanto"
                    else -> "Kanto"
                }
            }

            val regionBadges = GymBadges.badgesForRegion(activeRegion)
            val gymOnly = regionBadges.filter { !it.id.endsWith("_league_trophy") }
            val leagueBadge = regionBadges.firstOrNull { it.id.endsWith("_league_trophy") }

            val regionDone = activeRegion in completedRegions
            val gymsCompleted = if (regionDone) {
                gymOnly.size
            } else {
                gymOnly.count { it.id.lowercase() in defeatedBadgeIds }
            }
            val leagueCompleted = regionDone || (leagueBadge != null && leagueBadge.id.lowercase() in defeatedBadgeIds)

            val progressPart = if (leagueCompleted) {
                "Liga Completada"
            } else {
                "$gymsCompleted/${gymOnly.size} Gimnasios"
            }
            val capPart = if (levelCap > 0) " | Tope Nv. $levelCap" else ""
            val summaryText = "RCT: $activeRegion | $progressPart$capPart"

            RctPlayerSummary(
                available = true,
                seriesId = currentSeries,
                regionName = activeRegion,
                levelCap = levelCap,
                gymsCompleted = gymsCompleted,
                gymsTotal = gymOnly.size,
                leagueCompleted = leagueCompleted,
                completedRegions = completedRegions,
                defeatedTrainerIds = defeatedIds,
                defeatedBadgeIds = defeatedBadgeIds,
                summaryText = summaryText
            )
        }.getOrElse { err ->
            BadgesMod.LOGGER.warn("[RCT] Error leyendo progreso de RCT para ${player.scoreboardName}: ${err.message}")
            RctPlayerSummary()
        }
    }
}
