package com.desgraciamon.discordbridge

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class DiscordLinkData private constructor() : SavedData() {
    private data class Link(val discordId: String, val minecraftName: String)
    private data class Pending(val playerId: UUID, val playerName: String, val expiresAt: Long)
    data class CaptureRecord(val pokemonId: UUID, val speciesId: String, val speciesName: String, val capturedAt: Long, val shiny: Boolean = false)

    data class PlayerStatsRecord(
        var captures: Int = 0,
        var shinies: Int = 0,
        var legendaries: Int = 0,
        var moneyEarned: Long = 0L,
        var moneySpent: Long = 0L,
        var lastKnownBalance: Long = 0L,
        var lastKnownLevelCap: Int = 20,
        var skinTextureHash: String? = null,
        var skinModel: String? = null
    )

    private val links = linkedMapOf<UUID, Link>()
    private val pending = linkedMapOf<String, Pending>()
    private val lastSpecialCapture = linkedMapOf<UUID, Long>()
    private val captureHistory = linkedMapOf<UUID, MutableList<CaptureRecord>>()
    private val privateTeams = linkedSetOf<UUID>()
    private val playerStats = linkedMapOf<UUID, PlayerStatsRecord>()

    @Synchronized
    fun createCode(playerId: UUID, playerName: String, now: Long = System.currentTimeMillis()): String {
        prune(now)
        val code = buildString(8) {
            repeat(8) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        pending[hash(code)] = Pending(playerId, playerName, now + LINK_CODE_LIFETIME)
        setDirty()
        return code
    }

    @Synchronized
    fun isPlayerLinked(playerId: UUID): Boolean = links.containsKey(playerId)

    @Synchronized
    fun link(code: String, discordId: String, now: Long = System.currentTimeMillis()): Pair<LinkResult, String?> {
        prune(now)
        val candidate = pending.remove(hash(code.uppercase().trim())) ?: run {
            setDirty()
            return LinkResult.INVALID_OR_EXPIRED to null
        }
        if (links.values.any { it.discordId == discordId }) {
            setDirty()
            return LinkResult.DISCORD_ALREADY_LINKED to candidate.playerName
        }
        if (links.containsKey(candidate.playerId)) {
            setDirty()
            return LinkResult.MINECRAFT_ALREADY_LINKED to candidate.playerName
        }
        links[candidate.playerId] = Link(discordId, candidate.playerName)
        setDirty()
        return LinkResult.LINKED to candidate.playerName
    }

    @Synchronized
    fun unlinkPlayer(playerId: UUID): Boolean = (links.remove(playerId) != null).also { if (it) setDirty() }

    @Synchronized
    fun unlinkDiscord(discordId: String): String? {
        val linkedPlayer = links.entries.firstOrNull { it.value.discordId == discordId } ?: return null
        links.remove(linkedPlayer.key)
        setDirty()
        return linkedPlayer.value.minecraftName
    }

    @Synchronized
    fun unlinkByNameOrId(query: String): Pair<UUID, String>? {
        val clean = query.trim()
        if (clean.isEmpty()) return null
        val parsedUuid = runCatching { UUID.fromString(clean) }.getOrNull()
        val entry = when {
            parsedUuid != null && links.containsKey(parsedUuid) -> parsedUuid to links[parsedUuid]!!
            else -> links.entries.firstOrNull {
                it.value.minecraftName.equals(clean, ignoreCase = true) || it.value.discordId == clean
            }?.let { it.key to it.value }
        } ?: return null
        links.remove(entry.first)
        setDirty()
        return entry.first to entry.second.minecraftName
    }

    @Synchronized
    fun playerForDiscord(discordId: String): UUID? = links.entries.firstOrNull { it.value.discordId == discordId }?.key

    @Synchronized
    fun minecraftName(playerId: UUID): String? = links[playerId]?.minecraftName

    @Synchronized
    fun isTeamPrivate(playerId: UUID): Boolean = playerId in privateTeams

    @Synchronized
    fun setTeamPrivate(playerId: UUID, isPrivate: Boolean) {
        val changed = if (isPrivate) privateTeams.add(playerId) else privateTeams.remove(playerId)
        if (changed) setDirty()
    }

    @Synchronized
    fun getStats(playerId: UUID): PlayerStatsRecord = playerStats.getOrPut(playerId) { PlayerStatsRecord() }

    @Synchronized
    fun recordCapture(playerId: UUID, shiny: Boolean, legendary: Boolean) {
        val stats = getStats(playerId)
        stats.captures++
        if (shiny) stats.shinies++
        if (legendary) stats.legendaries++
        setDirty()
    }

    @Synchronized
    fun updateBalance(playerId: UUID, currentBalance: Long) {
        val stats = getStats(playerId)
        if (stats.lastKnownBalance == 0L && stats.moneyEarned == 0L && stats.moneySpent == 0L && currentBalance > 0L) {
            stats.lastKnownBalance = currentBalance
            stats.moneyEarned = currentBalance
            setDirty()
            return
        }
        if (currentBalance > stats.lastKnownBalance) {
            val delta = currentBalance - stats.lastKnownBalance
            stats.moneyEarned += delta
            stats.lastKnownBalance = currentBalance
            setDirty()
        } else if (currentBalance < stats.lastKnownBalance) {
            val delta = stats.lastKnownBalance - currentBalance
            stats.moneySpent += delta
            stats.lastKnownBalance = currentBalance
            setDirty()
        }
    }

    @Synchronized
    fun recordMoneyEarned(playerId: UUID, amount: Long) {
        if (amount <= 0) return
        val stats = getStats(playerId)
        stats.moneyEarned += amount
        stats.lastKnownBalance += amount
        setDirty()
    }

    @Synchronized
    fun recordMoneySpent(playerId: UUID, amount: Long) {
        if (amount <= 0) return
        val stats = getStats(playerId)
        stats.moneySpent += amount
        stats.lastKnownBalance = (stats.lastKnownBalance - amount).coerceAtLeast(0L)
        setDirty()
    }

    @Synchronized
    fun setLevelCap(playerId: UUID, cap: Int) {
        val stats = getStats(playerId)
        if (cap > stats.lastKnownLevelCap) {
            stats.lastKnownLevelCap = cap
            setDirty()
        }
    }

    @Synchronized
    fun setSkin(playerId: UUID, textureHash: String, model: String?) {
        val stats = getStats(playerId)
        stats.skinTextureHash = textureHash
        stats.skinModel = model
        setDirty()
    }

    @Synchronized
    fun getSkinTextureHash(playerId: UUID): String? = playerStats[playerId]?.skinTextureHash

    @Synchronized
    fun recordSpecialCapture(playerId: UUID, pokemonId: UUID, speciesId: String, speciesName: String, capturedAt: Long, shiny: Boolean) {
        lastSpecialCapture[playerId] = capturedAt
        val records = captureHistory.getOrPut(playerId) { mutableListOf() }
        records.removeAll { it.pokemonId == pokemonId }
        records.add(CaptureRecord(pokemonId, speciesId, speciesName, capturedAt, shiny))
        if (records.size > MAX_CAPTURE_RECORDS) records.removeAt(0)
        setDirty()
    }

    @Synchronized
    fun captureTimestamp(playerId: UUID, pokemonId: UUID): Long? =
        captureHistory[playerId]?.lastOrNull { it.pokemonId == pokemonId }?.capturedAt

    @Synchronized
    fun captureRecord(playerId: UUID, pokemonId: UUID): CaptureRecord? =
        captureHistory[playerId]?.lastOrNull { it.pokemonId == pokemonId }

    @Synchronized
    fun fallbackCooldownMillis(playerId: UUID, now: Long = System.currentTimeMillis()): Long =
        ((lastSpecialCapture[playerId] ?: 0L) + COOLDOWN_MILLIS - now).coerceAtLeast(0L)

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        synchronized(this) {
            val storedLinks = CompoundTag()
            links.forEach { (playerId, link) ->
                storedLinks.put(playerId.toString(), CompoundTag().also {
                    it.putString("discordId", link.discordId)
                    it.putString("minecraftName", link.minecraftName)
                })
            }
            val storedPending = CompoundTag()
            pending.forEach { (digest, link) ->
                storedPending.put(digest, CompoundTag().also {
                    it.putString("playerId", link.playerId.toString())
                    it.putString("playerName", link.playerName)
                    it.putLong("expiresAt", link.expiresAt)
                })
            }
            val captures = CompoundTag()
            lastSpecialCapture.forEach { (playerId, capturedAt) -> captures.putLong(playerId.toString(), capturedAt) }
            val history = net.minecraft.nbt.ListTag()
            captureHistory.forEach { (playerId, records) -> records.forEach { record ->
                history.add(CompoundTag().also {
                    it.putString("playerId", playerId.toString())
                    it.putString("pokemonId", record.pokemonId.toString())
                    it.putString("speciesId", record.speciesId)
                    it.putString("speciesName", record.speciesName)
                    it.putLong("capturedAt", record.capturedAt)
                    it.putBoolean("shiny", record.shiny)
                })
            } }
            val statsCompound = CompoundTag()
            playerStats.forEach { (playerId, record) ->
                statsCompound.put(playerId.toString(), CompoundTag().also {
                    it.putInt("captures", record.captures)
                    it.putInt("shinies", record.shinies)
                    it.putInt("legendaries", record.legendaries)
                    it.putLong("moneyEarned", record.moneyEarned)
                    it.putLong("moneySpent", record.moneySpent)
                    it.putLong("lastKnownBalance", record.lastKnownBalance)
                    it.putInt("lastKnownLevelCap", record.lastKnownLevelCap)
                    record.skinTextureHash?.let { h -> it.putString("skinTextureHash", h) }
                    record.skinModel?.let { m -> it.putString("skinModel", m) }
                })
            }
            tag.put("links", storedLinks)
            tag.put("pending", storedPending)
            tag.put("lastSpecialCapture", captures)
            tag.put("captureHistory", history)
            tag.put("playerStats", statsCompound)
            tag.put("privateTeams", net.minecraft.nbt.ListTag().also { stored ->
                privateTeams.forEach { playerId -> stored.add(net.minecraft.nbt.StringTag.valueOf(playerId.toString())) }
            })
            return tag
        }
    }

    private fun prune(now: Long) {
        pending.entries.removeIf { it.value.expiresAt <= now }
    }

    companion object {
        private const val DATA_ID = "desgraciamon4_discord_bridge"
        private const val LINK_CODE_LIFETIME = 10 * 60 * 1000L
        private const val COOLDOWN_MILLIS = 48 * 60 * 60 * 1000L
        private const val MAX_CAPTURE_RECORDS = 10000
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()
        private val factory = Factory(::DiscordLinkData, ::load, null)

        fun get(server: MinecraftServer): DiscordLinkData =
            server.overworld().dataStorage.computeIfAbsent(factory, DATA_ID)

        @JvmStatic
        fun discordId(server: MinecraftServer, playerId: UUID): String? = get(server).links[playerId]?.discordId

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): DiscordLinkData {
            val data = DiscordLinkData()
            val storedLinks = tag.getCompound("links")
            storedLinks.allKeys.forEach { key ->
                val playerId = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
                val stored = storedLinks.getCompound(key)
                val discordId = stored.getString("discordId")
                if (discordId.isNotBlank()) data.links[playerId] = Link(discordId, stored.getString("minecraftName"))
            }
            val storedPending = tag.getCompound("pending")
            storedPending.allKeys.forEach { digest ->
                val stored = storedPending.getCompound(digest)
                val playerId = runCatching { UUID.fromString(stored.getString("playerId")) }.getOrNull() ?: return@forEach
                data.pending[digest] = Pending(playerId, stored.getString("playerName"), stored.getLong("expiresAt"))
            }
            val captures = tag.getCompound("lastSpecialCapture")
            captures.allKeys.forEach { key ->
                val playerId = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
                data.lastSpecialCapture[playerId] = captures.getLong(key)
            }
            val history = tag.getList("captureHistory", net.minecraft.nbt.Tag.TAG_COMPOUND.toInt())
            for (index in 0 until history.size) {
                val stored = history.getCompound(index)
                val playerId = runCatching { UUID.fromString(stored.getString("playerId")) }.getOrNull() ?: continue
                val pokemonId = runCatching { UUID.fromString(stored.getString("pokemonId")) }.getOrNull() ?: continue
                data.captureHistory.getOrPut(playerId) { mutableListOf() }.add(
                    CaptureRecord(
                        pokemonId,
                        stored.getString("speciesId"),
                        stored.getString("speciesName"),
                        stored.getLong("capturedAt"),
                        stored.getBoolean("shiny")
                    )
                )
            }
            val statsCompound = tag.getCompound("playerStats")
            statsCompound.allKeys.forEach { key ->
                val playerId = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
                val stored = statsCompound.getCompound(key)
                val record = PlayerStatsRecord(
                    captures = stored.getInt("captures"),
                    shinies = stored.getInt("shinies"),
                    legendaries = stored.getInt("legendaries"),
                    moneyEarned = stored.getLong("moneyEarned"),
                    moneySpent = stored.getLong("moneySpent"),
                    lastKnownBalance = stored.getLong("lastKnownBalance"),
                    lastKnownLevelCap = if (stored.contains("lastKnownLevelCap")) stored.getInt("lastKnownLevelCap") else 20,
                    skinTextureHash = if (stored.contains("skinTextureHash")) stored.getString("skinTextureHash") else null,
                    skinModel = if (stored.contains("skinModel")) stored.getString("skinModel") else null
                )
                data.playerStats[playerId] = record
            }
            val privateTeams = tag.getList("privateTeams", net.minecraft.nbt.Tag.TAG_STRING.toInt())
            for (index in 0 until privateTeams.size) {
                runCatching { UUID.fromString(privateTeams.getString(index)) }
                    .getOrNull()?.let(data.privateTeams::add)
            }
            return data
        }

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

enum class LinkResult { LINKED, INVALID_OR_EXPIRED, DISCORD_ALREADY_LINKED, MINECRAFT_ALREADY_LINKED }
