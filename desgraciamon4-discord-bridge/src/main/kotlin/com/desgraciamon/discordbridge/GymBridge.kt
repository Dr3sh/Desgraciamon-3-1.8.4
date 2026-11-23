package com.desgraciamon.discordbridge

import com.google.gson.JsonParser
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.storage.LevelResource
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID

data class GymLeaderInfo(
    val id: String,
    val badgeId: String,
    val name: String,
    val badge: String,
    val badgeName: String,
    val badgeEmoji: String
)

data class SeriesInfo(
    val id: String,
    val name: String,
    val championId: String,
    val trophyBadgeId: String,
    val championName: String,
    val leaders: List<GymLeaderInfo>
)

data class EarnedBadgeDetail(
    val badgeId: String,
    val badgeName: String,
    val shortBadgeName: String,
    val badgeEmoji: String,
    val leaderName: String,
    val regionName: String,
    val earned: Boolean,
    val defeatDate: String? = null
)

data class RegionBadgeProgress(
    val series: SeriesInfo,
    val badges: List<EarnedBadgeDetail>,
    val earnedCount: Int,
    val totalCount: Int,
    val championDefeated: Boolean,
    val championDefeatDate: String? = null
)

data class GymSummary(
    val regionId: String,
    val regionName: String,
    val levelCap: Int,
    val defeatedBadgesCount: Int,
    val totalBadgesCount: Int,
    val badgesDisplay: String,
    val totalTrainersDefeated: Long,
    val championDefeated: Boolean,
    val regionsProgress: Map<String, RegionBadgeProgress> = emptyMap()
)

object GymBridge {
    private data class ServerBadgeRecord(
        val badgeId: String,
        val gymLeader: String,
        val region: String,
        val timestamp: Long,
        val date: String
    )

    private data class ServerPlayerBadgeData(
        val unlocked: Set<String>,
        val records: Map<String, ServerBadgeRecord>
    )

    val SERIES_REGISTRY = linkedMapOf(
        "kanto" to SeriesInfo(
            id = "kanto",
            name = "Kanto",
            championId = "kanto_champion_blue",
            trophyBadgeId = "kanto_league_trophy",
            championName = "Azul (Blue)",
            leaders = listOf(
                GymLeaderInfo("kanto_brock", "kanto_boulder_badge", "Brock", "Roca", "Medalla Roca", ""),
                GymLeaderInfo("kanto_misty", "kanto_cascade_badge", "Misty", "Cascada", "Medalla Cascada", ""),
                GymLeaderInfo("kanto_ltsurge", "kanto_thunder_badge", "Lt. Surge", "Trueno", "Medalla Trueno", ""),
                GymLeaderInfo("kanto_erika", "kanto_rainbow_badge", "Erika", "Arcoíris", "Medalla Arcoíris", ""),
                GymLeaderInfo("kanto_koga", "kanto_soul_badge", "Koga", "Alma", "Medalla Alma", ""),
                GymLeaderInfo("kanto_sabrina", "kanto_marsh_badge", "Sabrina", "Pantano", "Medalla Pantano", ""),
                GymLeaderInfo("kanto_blaine", "kanto_volcano_badge", "Blaine", "Volcán", "Medalla Volcán", ""),
                GymLeaderInfo("kanto_giovanni", "kanto_earth_badge", "Giovanni", "Tierra", "Medalla Tierra", "")
            )
        ),
        "johto" to SeriesInfo(
            id = "johto",
            name = "Johto",
            championId = "johto_champion_lance",
            trophyBadgeId = "johto_league_trophy",
            championName = "Lance",
            leaders = listOf(
                GymLeaderInfo("johto_valerio", "johto_zephyr_badge", "Pegaso (Falkner)", "Céfiro", "Medalla Céfiro", ""),
                GymLeaderInfo("johto_raffaello", "johto_hive_badge", "Antón (Bugsy)", "Colmena", "Medalla Colmena", ""),
                GymLeaderInfo("johto_chiara", "johto_plain_badge", "Blanca (Whitney)", "Planicie", "Medalla Planicie", ""),
                GymLeaderInfo("johto_angelo", "johto_fog_badge", "Morti (Morty)", "Niebla", "Medalla Niebla", ""),
                GymLeaderInfo("johto_furio", "johto_storm_badge", "Aníbal (Chuck)", "Tormenta", "Medalla Tormenta", ""),
                GymLeaderInfo("johto_jasmine", "johto_mineral_badge", "Yasmina (Jasmine)", "Mineral", "Medalla Mineral", ""),
                GymLeaderInfo("johto_alfredo", "johto_glacier_badge", "Fredo (Pryce)", "Glaciar", "Medalla Glaciar", ""),
                GymLeaderInfo("johto_sandra", "johto_rising_badge", "Débora (Clair)", "Dragón", "Medalla Dragón", "")
            )
        ),
        "hoenn" to SeriesInfo(
            id = "hoenn",
            name = "Hoenn",
            championId = "hoenn_champion_rocco",
            trophyBadgeId = "hoenn_league_trophy",
            championName = "Máximo (Steven)",
            leaders = listOf(
                GymLeaderInfo("hoenn_petra", "hoenn_stone_badge", "Petra (Roxanne)", "Piedra", "Medalla Piedra", ""),
                GymLeaderInfo("hoenn_rudi", "hoenn_knuckle_badge", "Marcial (Brawly)", "Puño", "Medalla Puño", ""),
                GymLeaderInfo("hoenn_walter", "hoenn_dynamo_badge", "Erico (Wattson)", "Dinamo", "Medalla Dinamo", ""),
                GymLeaderInfo("hoenn_fiammetta", "hoenn_heat_badge", "Candela (Flannery)", "Calor", "Medalla Calor", ""),
                GymLeaderInfo("hoenn_norman", "hoenn_balance_badge", "Norman", "Equilibrio", "Medalla Equilibrio", ""),
                GymLeaderInfo("hoenn_alice", "hoenn_feather_badge", "Alana (Winona)", "Pluma", "Medalla Pluma", ""),
                GymLeaderInfo("hoenn_tell", "hoenn_mind_badge", "Vito y Leti (Tate & Liza)", "Mente", "Medalla Mente", ""),
                GymLeaderInfo("hoenn_adriano", "hoenn_rain_badge", "Plubio (Wallace)", "Lluvia", "Medalla Lluvia", "")
            )
        ),
        "sinnoh" to SeriesInfo(
            id = "sinnoh",
            name = "Sinnoh",
            championId = "sinnoh_champion_camilla",
            trophyBadgeId = "sinnoh_league_trophy",
            championName = "Cintia (Cynthia)",
            leaders = listOf(
                GymLeaderInfo("sinnoh_pedro", "sinnoh_coal_badge", "Roco (Roark)", "Lignito", "Medalla Lignito", ""),
                GymLeaderInfo("sinnoh_gardenia", "sinnoh_forest_badge", "Gardenia", "Bosque", "Medalla Bosque", ""),
                GymLeaderInfo("sinnoh_marzia", "sinnoh_cobble_badge", "Brega (Maylene)", "Adoquín", "Medalla Adoquín", ""),
                GymLeaderInfo("sinnoh_omar", "sinnoh_fen_badge", "Mananti (Crasher Wake)", "Ciénaga", "Medalla Ciénaga", ""),
                GymLeaderInfo("sinnoh_fannie", "sinnoh_relic_badge", "Fantina", "Reliquia", "Medalla Reliquia", ""),
                GymLeaderInfo("sinnoh_ferruccio", "sinnoh_mine_badge", "Acerón (Byron)", "Mina", "Medalla Mina", ""),
                GymLeaderInfo("sinnoh_bianca", "sinnoh_icicle_badge", "Inverna (Candice)", "Carámbano", "Medalla Carámbano", ""),
                GymLeaderInfo("sinnoh_corrado", "sinnoh_beacon_badge", "Lectro (Volkner)", "Faro", "Medalla Faro", "")
            )
        )
    )

    fun getSummary(server: MinecraftServer, playerId: UUID): GymSummary {
        val onlinePlayer = server.playerList.getPlayer(playerId)
        var seriesId = "kanto"
        var levelCap = 20
        var defeatedTrainerIds = emptySet<String>()
        var defeatCount = 0L

        // 1. Leer medallas del servidor desde <world>/data/desgraciamon4_badges/players.json (y BadgeData en memoria)
        val serverBadges = readServerBadges(server, playerId)

        // 2. Leer progreso de RCTMod (online o archivo NBT data/rctmod.player.<uuid>.stat.dat)
        if (onlinePlayer != null) {
            runCatching {
                val stateClass = Class.forName("com.gitlab.srcmc.rctmod.api.data.sync.PlayerState")
                val getMethod = stateClass.getMethod("get", Player::class.java)
                val state = getMethod.invoke(null, onlinePlayer)
                if (state != null) {
                    val cur = stateClass.getMethod("getCurrentSeries").invoke(state) as? String
                    if (!cur.isNullOrBlank()) seriesId = cur.substringAfter(":")
                    val cap = stateClass.getMethod("getLevelCap").invoke(state) as? Int
                    if (cap != null && cap > 0) levelCap = cap
                    @Suppress("UNCHECKED_CAST")
                    val defeated = stateClass.getMethod("getDefeatedTrainerIds").invoke(state) as? Set<String>
                    if (defeated != null) defeatedTrainerIds = defeated.map { it.substringAfter(":").lowercase() }.toSet()
                    val count = (stateClass.getMethod("getTrainerDefeatCount").invoke(state) as? Number)?.toLong()
                    defeatCount = count ?: defeatedTrainerIds.size.toLong()
                    DiscordLinkData.get(server).setLevelCap(playerId, levelCap)
                }
            }
        } else {
            runCatching {
                val path = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("rctmod.player.$playerId.stat.dat")
                if (Files.exists(path)) {
                    val tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())
                    val dataTag = if (tag.contains("data")) tag.getCompound("data") else tag
                    val cur = dataTag.getString("currentSeries")
                    if (cur.isNotBlank()) seriesId = cur.substringAfter(":")
                    val defeatsTag = dataTag.getCompound("progressDefeats")
                    defeatedTrainerIds = defeatsTag.allKeys.map { it.substringAfter(":").lowercase() }.toSet()
                    defeatCount = defeatedTrainerIds.size.toLong()
                }
            }
            levelCap = DiscordLinkData.get(server).getStats(playerId).lastKnownLevelCap.coerceAtLeast(20)
        }

        // 3. Construir progreso detallado por región (Kanto, Johto, Hoenn, Sinnoh) combinando players.json + RCTMod
        val regionsMap = LinkedHashMap<String, RegionBadgeProgress>()
        for ((regKey, sInfo) in SERIES_REGISTRY) {
            val badgeDetails = sInfo.leaders.map { leader ->
                val bId = leader.badgeId.lowercase()
                val lId = leader.id.lowercase()
                val rec = serverBadges.records[bId]
                val earnedInBadgesJson = serverBadges.unlocked.contains(bId) || rec != null
                val earnedInRct = defeatedTrainerIds.contains(lId) ||
                    defeatedTrainerIds.any { it.endsWith("/$lId") || it.substringBefore('.') == lId }
                val earned = earnedInBadgesJson || earnedInRct
                EarnedBadgeDetail(
                    badgeId = leader.badgeId,
                    badgeName = leader.badgeName,
                    shortBadgeName = leader.badge,
                    badgeEmoji = leader.badgeEmoji,
                    leaderName = leader.name,
                    regionName = sInfo.name,
                    earned = earned,
                    defeatDate = rec?.date?.takeIf { it.isNotBlank() }
                )
            }
            val trophyKey = sInfo.trophyBadgeId.lowercase()
            val trophyRecord = serverBadges.records[trophyKey]
            val champEarned = serverBadges.unlocked.contains(trophyKey) ||
                trophyRecord != null ||
                defeatedTrainerIds.contains(sInfo.championId.lowercase()) ||
                (regKey == "kanto" && "rival_red" in defeatedTrainerIds)

            regionsMap[regKey] = RegionBadgeProgress(
                series = sInfo,
                badges = badgeDetails,
                earnedCount = badgeDetails.count { it.earned },
                totalCount = badgeDetails.size,
                championDefeated = champEarned,
                championDefeatDate = trophyRecord?.date?.takeIf { it.isNotBlank() }
            )
        }

        // Si el jugador tiene medallas en una región posterior pero seriesId seguía en kanto, reflejar la región activa
        val resolvedSeriesKey = seriesId.lowercase().takeIf { SERIES_REGISTRY.containsKey(it) } ?: when {
            (regionsMap["sinnoh"]?.earnedCount ?: 0) > 0 -> "sinnoh"
            (regionsMap["hoenn"]?.earnedCount ?: 0) > 0 -> "hoenn"
            (regionsMap["johto"]?.earnedCount ?: 0) > 0 -> "johto"
            else -> "kanto"
        }

        val activeProgress = regionsMap[resolvedSeriesKey] ?: regionsMap["kanto"]!!
        val display = activeProgress.badges.joinToString("  ") { b ->
            if (b.earned) {
                val dateSuffix = if (!b.defeatDate.isNullOrBlank()) " (`${b.defeatDate}`)" else ""
                "${b.badgeEmoji} **${b.badgeName}**$dateSuffix"
            } else {
                "~~${b.shortBadgeName}~~"
            }
        }

        val totalGymEarned = regionsMap.values.sumOf { it.earnedCount }
        val finalDefeatCount = maxOf(defeatCount, totalGymEarned.toLong())

        return GymSummary(
            regionId = activeProgress.series.id,
            regionName = activeProgress.series.name,
            levelCap = levelCap,
            defeatedBadgesCount = activeProgress.earnedCount,
            totalBadgesCount = activeProgress.totalCount,
            badgesDisplay = display,
            totalTrainersDefeated = finalDefeatCount,
            championDefeated = activeProgress.championDefeated,
            regionsProgress = regionsMap
        )
    }

    /**
     * Lee `<world>/data/desgraciamon4_badges/players.json` (y `com.desgraciamon.badges.BadgeData` en memoria si está cargado).
     */
    private fun readServerBadges(server: MinecraftServer, playerId: UUID): ServerPlayerBadgeData {
        val unlocked = LinkedHashSet<String>()
        val records = LinkedHashMap<String, ServerBadgeRecord>()

        // A) Intentar leer desde com.desgraciamon.badges.BadgeData en memoria
        runCatching {
            val badgeDataClass = Class.forName("com.desgraciamon.badges.BadgeData")
            val instance = badgeDataClass.getField("INSTANCE").get(null)
            val playerMethod = badgeDataClass.getMethod("player", UUID::class.java)
            val playerBadgesObj = playerMethod.invoke(instance, playerId)
            if (playerBadgesObj != null) {
                val unlockedSet = playerBadgesObj.javaClass.getMethod("getUnlocked").invoke(playerBadgesObj) as? Iterable<*>
                unlockedSet?.forEach { id -> if (id != null) unlocked.add(id.toString().lowercase()) }

                val recordsMap = playerBadgesObj.javaClass.getMethod("getRecords").invoke(playerBadgesObj) as? Map<*, *>
                recordsMap?.forEach { (k, v) ->
                    if (k != null && v != null) {
                        val bId = k.toString().lowercase()
                        val leader = runCatching { v.javaClass.getMethod("getGymLeader").invoke(v)?.toString() ?: "" }.getOrDefault("")
                        val region = runCatching { v.javaClass.getMethod("getRegion").invoke(v)?.toString() ?: "" }.getOrDefault("")
                        val ts = runCatching { (v.javaClass.getMethod("getTimestamp").invoke(v) as? Number)?.toLong() ?: 0L }.getOrDefault(0L)
                        val date = runCatching { v.javaClass.getMethod("getDate").invoke(v)?.toString() ?: "" }.getOrDefault("")
                        records[bId] = ServerBadgeRecord(bId, leader, region, ts, date)
                    }
                }
            }
        }

        // B) Leer desde <world>/data/desgraciamon4_badges/players.json
        val worldFile = server.getWorldPath(LevelResource.ROOT)
            .resolve("data")
            .resolve("desgraciamon4_badges")
            .resolve("players.json")
        val legacyFile = server.serverDirectory.resolve("config").resolve("desgraciamon4_badges").resolve("players.json")

        val targetPath = when {
            Files.exists(worldFile) -> worldFile
            Files.exists(legacyFile) -> legacyFile
            else -> null
        }

        if (targetPath != null) {
            runCatching {
                val content = Files.readString(targetPath, StandardCharsets.UTF_8)
                val root = JsonParser.parseString(content).asJsonObject
                val key = root.keySet().firstOrNull { it.equals(playerId.toString(), ignoreCase = true) }
                if (key != null) {
                    val pObj = root.getAsJsonObject(key)
                    if (pObj.has("unlocked") && pObj.get("unlocked").isJsonArray) {
                        for (el in pObj.getAsJsonArray("unlocked")) {
                            if (el.isJsonPrimitive) unlocked.add(el.asString.lowercase())
                        }
                    }
                    if (pObj.has("records") && pObj.get("records").isJsonObject) {
                        val recObj = pObj.getAsJsonObject("records")
                        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss").apply {
                            timeZone = TimeZone.getDefault()
                        }
                        for (bKey in recObj.keySet()) {
                            val r = recObj.getAsJsonObject(bKey) ?: continue
                            val cleanKey = bKey.lowercase()
                            val leader = if (r.has("gymLeader")) r.get("gymLeader").asString else ""
                            val region = if (r.has("region")) r.get("region").asString else ""
                            val ts = if (r.has("timestamp")) r.get("timestamp").asLong else 0L
                            var dateStr = if (r.has("date")) r.get("date").asString else ""
                            if (dateStr.isBlank() && ts > 0L) {
                                dateStr = runCatching { dateFormat.format(Date(ts)) }.getOrDefault("")
                            }
                            records[cleanKey] = ServerBadgeRecord(cleanKey, leader, region, ts, dateStr)
                        }
                    }
                }
            }
        }

        return ServerPlayerBadgeData(unlocked, records)
    }
}
