package com.desgraciamon.discordbridge

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress
import com.google.gson.JsonParser
import com.mojang.authlib.properties.Property
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.level.storage.LevelResource
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.text.NumberFormat
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID

object ProfileService {

    data class CaptureSummary(val total: Int, val shinies: Int, val legendaries: Int)
    data class SurvivalSummary(val playTime: String, val deaths: Int)
    data class EconomySummary(val balance: String, val earned: String, val spent: String)
    data class GuildSummary(val name: String, val role: String, val memberCount: Int, val colorInt: Int?)

    private val TEXTURE_HASH_REGEX = Regex("^[a-fA-F0-9]{32,64}$")

    fun buildEmbed(
        server: MinecraftServer,
        targetDiscordUser: User,
        playerId: UUID,
        selectedRegion: String? = null
    ): MessageEmbed {
        val linkData = DiscordLinkData.get(server)
        val onlinePlayer = server.playerList.getPlayer(playerId)
        val mcName = onlinePlayer?.gameProfile?.name ?: linkData.minecraftName(playerId) ?: "Entrenador"

        val isOnline = onlinePlayer != null
        val statusDot = if (isOnline) "🟢" else "⚫"
        val onlineStatus = if (isOnline) "En línea" else "Desconectado"
        val guildSummary = getGuildSummary(server, playerId)
        val guildText = if (guildSummary != null) {
            "**${guildSummary.name}** (${guildSummary.role} · ${guildSummary.memberCount} miembros)"
        } else {
            "**Sin gremio**"
        }

        val gymSummary = GymBridge.getSummary(server, playerId)
        val captureSummary = getCaptureSummary(server, onlinePlayer, playerId)
        val survivalSummary = getSurvivalSummary(server, onlinePlayer, playerId)
        val economySummary = getEconomySummary(server, onlinePlayer, playerId)

        val skinIdentifier = resolveSkinIdentifier(server, onlinePlayer, playerId, linkData)
        val skinThumbnailUrl = "https://mc-heads.net/body/$skinIdentifier/right"
        val avatarIconUrl = "https://mc-heads.net/avatar/$skinIdentifier/64"

        val embedColor = guildSummary?.colorInt ?: if (isOnline) 0x2ECC71 else 0x5865F2

        val embed = EmbedBuilder()
            .setAuthor("Perfil", null, avatarIconUrl)
            .setTitle(mcName)
            .setColor(embedColor)
            .setThumbnail(skinThumbnailUrl)
            .setDescription(
                "${targetDiscordUser.asMention} · $statusDot **$onlineStatus** · Gremio: $guildText\n" +
                "Región Activa: **${gymSummary.regionName}** (Cap Nv. **${gymSummary.levelCap}**)"
            )

        // Fila 1: Pokédex (izquierda) | Economía (derecha)
        val captureValue = buildString {
            appendLine("Capturados: **${captureSummary.total}**")
            appendLine("Shinies: **${captureSummary.shinies}**")
            append("Legendarios: **${captureSummary.legendaries}**")
        }
        embed.addField("Pokédex", captureValue, true)

        val economyValue = buildString {
            appendLine("Balance: **${economySummary.balance}**")
            appendLine("Ganado: **+${economySummary.earned}**")
            append("Gastado: **-${economySummary.spent}**")
        }
        embed.addField("Economía (CobbleDollars)", economyValue, true)
        embed.addBlankField(true)

        // Fila 2: Supervivencia (izquierda) | Liga Pokémon (derecha)
        val survivalValue = buildString {
            appendLine("Tiempo Jugado: **${survivalSummary.playTime}**")
            appendLine("Muertes: **${survivalSummary.deaths}**")
            append("Entrenadores: **${gymSummary.totalTrainersDefeated}**")
        }
        embed.addField("Supervivencia", survivalValue, true)

        val effectiveRegionKey = selectedRegion?.lowercase(Locale.ROOT) ?: gymSummary.regionId.lowercase(Locale.ROOT)
        val regProg = gymSummary.regionsProgress[effectiveRegionKey] ?: gymSummary.regionsProgress["kanto"]

        val totalEarnedAllRegions = gymSummary.regionsProgress.values.sumOf { it.earnedCount }
        val totalPossibleAllRegions = gymSummary.regionsProgress.values.sumOf { it.totalCount }
        val overallPct = if (totalPossibleAllRegions > 0) (totalEarnedAllRegions * 100) / totalPossibleAllRegions else 0

        fun regionSummaryText(key: String, fallbackName: String): String {
            val rp = gymSummary.regionsProgress[key]
            return if (rp != null) "${rp.series.name}: **${rp.earnedCount}/${rp.totalCount}**" else "$fallbackName: **0/8**"
        }

        val leagueValue = buildString {
            appendLine("${regionSummaryText("kanto", "Kanto")} · ${regionSummaryText("johto", "Johto")}")
            append("${regionSummaryText("hoenn", "Hoenn")} · ${regionSummaryText("sinnoh", "Sinnoh")}")
        }
        embed.addField("Liga Pokémon ($overallPct% · $totalEarnedAllRegions/$totalPossibleAllRegions)", leagueValue, true)
        embed.addBlankField(true)

        // Fila 3: Medallas de la región seleccionada en 2 columnas (4 + 4) + Campeón
        if (regProg != null) {
            val isCurrentActive = regProg.series.id.equals(gymSummary.regionId, ignoreCase = true)
            val activeTag = if (isCurrentActive) " — Activa" else ""
            val (leftBadges, rightBadges) = formatRegionBadgesColumns(regProg)

            embed.addField(
                "Medallas de ${regProg.series.name} (${regProg.earnedCount}/${regProg.totalCount})$activeTag",
                leftBadges,
                true
            )
            embed.addField("\u200B", rightBadges, true)
            embed.addBlankField(true)

            val champLine = if (regProg.championDefeated) {
                val datePart = if (!regProg.championDefeatDate.isNullOrBlank()) " (`${regProg.championDefeatDate}`)" else ""
                "✅ **Campeón ${regProg.series.championName}:** Derrotado$datePart"
            } else {
                "⏳ **Campeón ${regProg.series.championName}:** En progreso"
            }
            embed.addField("\u200B", champLine, false)
        }

        embed.setFooter("Desgraciamon 3 • Usa el menú inferior para cambiar de región", targetDiscordUser.effectiveAvatarUrl)
        embed.setTimestamp(Instant.now())
        return embed.build()
    }

    private fun formatBadgeLine(b: EarnedBadgeDetail): String {
        val icon = if (b.earned) "✅" else "⬜"
        return "$icon **${b.shortBadgeName}** · ${b.leaderName}"
    }

    private fun formatRegionBadgesColumns(regProg: RegionBadgeProgress): Pair<String, String> {
        val badges = regProg.badges
        if (badges.isEmpty()) {
            return "*Sin medallas registradas.*" to "\u200B"
        }
        val mid = (badges.size + 1) / 2
        val left = badges.take(mid).joinToString("\n") { formatBadgeLine(it) }.ifBlank { "\u200B" }
        val right = badges.drop(mid).joinToString("\n") { formatBadgeLine(it) }.ifBlank { "\u200B" }
        return left to right
    }

    private fun resolveSkinIdentifier(
        server: MinecraftServer,
        onlinePlayer: ServerPlayer?,
        playerId: UUID,
        linkData: DiscordLinkData
    ): String {
        // 1. Live GameProfile textures property (includes active SkinRestorer skin in-game)
        val liveBase64 = onlinePlayer?.gameProfile?.properties?.get("textures")?.firstOrNull()?.value()
        val liveHash = extractTextureHashFromBase64(liveBase64)
        if (liveHash != null) {
            linkData.setSkin(playerId, liveHash, null)
            return liveHash
        }

        // 2. SkinRestorer storage directly (works even when offline after using /skin in-game)
        val restorerHash = readSkinRestorerHash(playerId)
        if (restorerHash != null) {
            linkData.setSkin(playerId, restorerHash, null)
            return restorerHash
        }

        // 3. Fallback to cached Discord /skin texture hash
        val storedHash = linkData.getSkinTextureHash(playerId)?.trim()
        if (!storedHash.isNullOrBlank() && TEXTURE_HASH_REGEX.matches(storedHash)) {
            return storedHash
        }

        return playerId.toString()
    }

    private fun readSkinRestorerHash(playerId: UUID): String? {
        return runCatching {
            val srClass = Class.forName("net.lionarius.skinrestorer.SkinRestorer")
            val storage = srClass.getMethod("getSkinStorage").invoke(null) ?: return null
            val skinValue = storage.javaClass.getMethod("getSkin", UUID::class.java).invoke(storage, playerId) ?: return null
            val propObj = skinValue.javaClass.getMethod("value").invoke(skinValue) ?: return null
            val base64Val = when (propObj) {
                is Property -> propObj.value()
                else -> propObj.javaClass.getMethod("value").invoke(propObj) as? String
            }
            extractTextureHashFromBase64(base64Val)
        }.getOrNull()
    }

    private fun extractTextureHashFromBase64(base64Value: String?): String? {
        if (base64Value.isNullOrBlank()) return null
        return runCatching {
            val decoded = String(Base64.getDecoder().decode(base64Value.trim()), StandardCharsets.UTF_8)
            val root = JsonParser.parseString(decoded).asJsonObject
            val skinUrl = root.getAsJsonObject("textures")
                ?.getAsJsonObject("SKIN")
                ?.get("url")?.asString ?: return null
            val candidate = skinUrl.substringAfterLast("/").substringBefore("?").trim()
            if (TEXTURE_HASH_REGEX.matches(candidate)) candidate else null
        }.getOrNull()
    }

    private fun getGuildSummary(server: MinecraftServer, playerId: UUID): GuildSummary? {
        // 1. Query desgraciamon_guilds in-memory storage via reflection
        val reflected = runCatching {
            val mainClass = Class.forName("net.desgraciamon.guilds.DesgraciamonGuilds")
            val storage = mainClass.getMethod("getStorage").invoke(null) ?: return@runCatching null
            val indexMethod = storage.javaClass.getDeclaredMethod("index").apply { isAccessible = true }
            val index = indexMethod.invoke(storage) ?: return@runCatching null
            val playerGuildsField = index.javaClass.getDeclaredField("playerGuilds").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val playerGuilds = playerGuildsField.get(index) as? Map<*, *> ?: return@runCatching null
            val guildId = (playerGuilds[playerId.toString()] ?: playerGuilds[playerId])?.toString() ?: return@runCatching null
            val getGuildMethod = storage.javaClass.getDeclaredMethod("getGuild", String::class.java).apply { isAccessible = true }
            val guild = getGuildMethod.invoke(storage, guildId) ?: return@runCatching null

            val disbanded = guild.javaClass.getDeclaredField("disbanded").apply { isAccessible = true }.getBoolean(guild)
            if (disbanded) return@runCatching null

            val guildName = (guild.javaClass.getDeclaredField("name").apply { isAccessible = true }.get(guild) as? String)?.trim()
            if (guildName.isNullOrBlank()) return@runCatching null

            val colorHex = guild.javaClass.getDeclaredField("colorHex").apply { isAccessible = true }.get(guild) as? String
            val members = guild.javaClass.getDeclaredField("members").apply { isAccessible = true }.get(guild) as? Map<*, *>
            val memberCount = members?.size ?: 1
            val uuidStr = playerId.toString()
            val memberObj = members?.entries?.firstOrNull { entry ->
                entry.key?.toString().equals(uuidStr, ignoreCase = true)
            }?.value
            val rawRole = memberObj?.let { m ->
                m.javaClass.getDeclaredField("role").apply { isAccessible = true }.get(m) as? String
            }
            GuildSummary(
                name = guildName,
                role = formatGuildRole(rawRole),
                memberCount = memberCount,
                colorInt = parseHexColor(colorHex)
            )
        }.getOrNull()
        if (reflected != null) return reflected

        // 2. Fallback to disk JSON files under <world>/desgraciamon_guilds/data/
        return runCatching {
            val dataDir = server.getWorldPath(LevelResource.ROOT).resolve("desgraciamon_guilds").resolve("data")
            val indexFile = dataDir.resolve("guilds_index.json")
            if (!Files.isRegularFile(indexFile)) return@runCatching null
            val indexJson = JsonParser.parseString(Files.readString(indexFile)).asJsonObject
            val playerGuilds = indexJson.getAsJsonObject("playerGuilds") ?: return@runCatching null
            val uuidStr = playerId.toString()
            val guildId = playerGuilds.entrySet()
                .firstOrNull { it.key.equals(uuidStr, ignoreCase = true) }
                ?.value?.asString ?: return@runCatching null

            val candidatePaths = listOf(
                dataDir.resolve("guilds").resolve("$guildId.json"),
                dataDir.resolve("$guildId.json")
            )
            val guildFile = candidatePaths.firstOrNull { Files.isRegularFile(it) } ?: return@runCatching null
            val guildJson = JsonParser.parseString(Files.readString(guildFile)).asJsonObject
            if (guildJson.has("disbanded") && guildJson.get("disbanded").asBoolean) return@runCatching null

            val name = guildJson.get("name")?.asString?.trim()
            if (name.isNullOrBlank()) return@runCatching null
            val colorHex = if (guildJson.has("colorHex") && !guildJson.get("colorHex").isJsonNull) {
                guildJson.get("colorHex").asString
            } else null
            val membersObj = guildJson.getAsJsonObject("members")
            val memberCount = membersObj?.size() ?: 1
            val rawRole = membersObj?.entrySet()
                ?.firstOrNull { it.key.equals(uuidStr, ignoreCase = true) }
                ?.value?.asJsonObject?.get("role")?.asString
            GuildSummary(
                name = name,
                role = formatGuildRole(rawRole),
                memberCount = memberCount,
                colorInt = parseHexColor(colorHex)
            )
        }.getOrNull()
    }

    private fun formatGuildRole(raw: String?): String {
        return when (raw?.trim()?.uppercase(Locale.ROOT)) {
            "LEADER", "FOUNDER", "LIDER" -> "Lider"
            "OFFICER", "OFICIAL", "CO_LEADER", "SUBLIDER" -> "Oficial"
            else -> "Miembro"
        }
    }

    private fun parseHexColor(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        val clean = hex.trim().removePrefix("#")
        if (clean.length != 6) return null
        return runCatching { clean.toInt(16) }.getOrNull()
    }

    private fun getCaptureSummary(server: MinecraftServer, onlinePlayer: ServerPlayer?, playerId: UUID): CaptureSummary {
        val linkData = DiscordLinkData.get(server)
        val statsRecord = linkData.getStats(playerId)

        val pcList = runCatching { Cobblemon.storage.getPC(playerId, server.registryAccess()).toList() }.getOrNull() ?: emptyList()
        val partyList = runCatching { Cobblemon.storage.getParty(playerId, server.registryAccess()).toList() }.getOrNull() ?: emptyList()
        val owned = pcList + partyList

        val ownedTotal = owned.size
        val ownedShinies = owned.count { it.shiny }
        val ownedLegendaries = owned.count { it.isLegendary() || it.isMythical() }

        var dexCaught = 0
        runCatching {
            val dex = Cobblemon.playerDataManager.getPokedexData(playerId)
            for ((_, record) in dex.speciesRecords) {
                if (record.getKnowledge() == PokedexEntryProgress.OWNED) {
                    dexCaught++
                }
            }
        }

        val total = maxOf(statsRecord.captures, ownedTotal, dexCaught)
        val shinies = maxOf(statsRecord.shinies, ownedShinies)
        val legendaries = maxOf(statsRecord.legendaries, ownedLegendaries)

        return CaptureSummary(total, shinies, legendaries)
    }

    private fun getSurvivalSummary(server: MinecraftServer, onlinePlayer: ServerPlayer?, playerId: UUID): SurvivalSummary {
        var playTimeTicks = 0L
        var deaths = 0

        if (onlinePlayer != null) {
            val stats = onlinePlayer.stats
            playTimeTicks = stats.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)).toLong()
            deaths = stats.getValue(Stats.CUSTOM.get(Stats.DEATHS))
        } else {
            runCatching {
                val path = server.getWorldPath(LevelResource.ROOT).resolve("stats").resolve("$playerId.json")
                if (Files.exists(path)) {
                    val root = JsonParser.parseString(Files.readString(path)).asJsonObject
                    val custom = root.getAsJsonObject("stats")?.getAsJsonObject("minecraft:custom")
                    if (custom != null) {
                        if (custom.has("minecraft:play_time")) playTimeTicks = custom.get("minecraft:play_time").asLong
                        else if (custom.has("minecraft:play_one_minute")) playTimeTicks = custom.get("minecraft:play_one_minute").asLong
                        if (custom.has("minecraft:deaths")) deaths = custom.get("minecraft:deaths").asInt
                    }
                }
            }
        }

        val totalMinutes = (playTimeTicks / 20L) / 60L
        val totalHours = totalMinutes / 60L
        val minutes = totalMinutes % 60L

        return SurvivalSummary(
            playTime = "${totalHours}h ${minutes}m",
            deaths = deaths
        )
    }

    private fun getEconomySummary(server: MinecraftServer, onlinePlayer: ServerPlayer?, playerId: UUID): EconomySummary {
        val linkData = DiscordLinkData.get(server)
        var balance = 0L

        if (onlinePlayer != null) {
            balance = getCobbleDollarsBalance(onlinePlayer)
        } else {
            runCatching {
                val path = server.getWorldPath(LevelResource.ROOT).resolve("cobbledollarsplayerdata").resolve("$playerId.json")
                if (Files.exists(path)) {
                    val root = JsonParser.parseString(Files.readString(path)).asJsonObject
                    if (root.has("cobbledollars")) balance = root.get("cobbledollars").asLong
                }
            }
        }

        linkData.updateBalance(playerId, balance)
        val statsRecord = linkData.getStats(playerId)

        val formatter = NumberFormat.getIntegerInstance(Locale.US)
        return EconomySummary(
            balance = formatter.format(balance),
            earned = formatter.format(statsRecord.moneyEarned),
            spent = formatter.format(statsRecord.moneySpent)
        )
    }

    private fun getCobbleDollarsBalance(player: ServerPlayer): Long {
        return try {
            val apiClass = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt")
            val method = apiClass.getMethod("getCobbleDollars", net.minecraft.world.entity.player.Player::class.java)
            val bigInt = method.invoke(null, player) as? java.math.BigInteger
            bigInt?.toLong() ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }
}
