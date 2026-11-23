package com.desgraciamon.badges

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.server.MinecraftServer
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BadgeData {
    data class BadgeRecord(
        val badgeId: String,
        val gymLeader: String = "",
        val region: String = "",
        val timestamp: Long = System.currentTimeMillis(),
        val date: String = formatDate(timestamp)
    ) {
        companion object {
            private val DATE_FORMAT = SimpleDateFormat("dd/MM/yyyy HH:mm:ss").apply {
                timeZone = TimeZone.getDefault()
            }
            fun formatDate(ts: Long): String = runCatching { DATE_FORMAT.format(Date(ts)) }.getOrDefault("")
        }
    }

    data class PlayerBadges(
        val unlocked: LinkedHashSet<String> = LinkedHashSet(),
        val hidden: LinkedHashSet<String> = LinkedHashSet(),
        val records: LinkedHashMap<String, BadgeRecord> = LinkedHashMap(),
        val completedOnTimeHitos: LinkedHashSet<String> = LinkedHashSet(),
        val claimedHitoRewards: LinkedHashSet<String> = LinkedHashSet(),
        var activeCatchupHitoId: String = ""
    )

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var file: File? = null

    @Volatile
    var isLoaded: Boolean = false
        private set

    fun initForServer(server: MinecraftServer) {
        val worldFolder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
        val dir = worldFolder.resolve("data/desgraciamon4_badges").toFile()
        dir.mkdirs()
        val serverFile = File(dir, "players.json")
        file = serverFile
        val legacy = File("config/desgraciamon4_badges/players.json")
        if (!serverFile.exists() && legacy.exists()) {
            runCatching { legacy.copyTo(serverFile, overwrite = false) }
        }
        load()
        isLoaded = true
    }

    fun resetServer() {
        isLoaded = false
        file = null
        players.clear()
        AdminAuth.clearAll()
    }

    private val players = ConcurrentHashMap<UUID, PlayerBadges>()

    fun load() {
        val targetFile = file ?: return
        runCatching {
            targetFile.parentFile?.mkdirs()
            if (!targetFile.exists()) {
                save()
                return
            }
            val type = object : TypeToken<Map<String, PlayerBadges>>() {}.type
            val raw: Map<String, PlayerBadges> = targetFile.reader(StandardCharsets.UTF_8).use { gson.fromJson(it, type) ?: emptyMap() }
            players.clear()
            val removedGymIds = GymBadges.BY_BADGE_ID.keys
            for ((k, v) in raw) {
                val uuid = runCatching { UUID.fromString(k) }.getOrNull() ?: continue
                val cleanUnlocked = LinkedHashSet(v.unlocked.map { it.lowercase() }.filterNot { it in removedGymIds })
                val cleanHidden = LinkedHashSet(v.hidden.map { it.lowercase() }.filterNot { TextCatalog.isNonHideable(it) || it in removedGymIds })
                val recordsMap = LinkedHashMap<String, BadgeRecord>()
                val rawRecords = v.records as Map<String, BadgeRecord>?
                if (rawRecords != null) {
                    for ((bId, rec) in rawRecords) {
                        val low = bId.lowercase()
                        if (low !in removedGymIds) {
                            recordsMap[low] = rec.copy(badgeId = low)
                        }
                    }
                }
                for (bId in cleanUnlocked) {
                    if (!recordsMap.containsKey(bId)) {
                        val ts = targetFile.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                        recordsMap[bId] = BadgeRecord(
                            badgeId = bId,
                            gymLeader = "",
                            region = "",
                            timestamp = ts,
                            date = BadgeRecord.formatDate(ts)
                        )
                    }
                }
                val rawOnTime = v.completedOnTimeHitos as Collection<String>?
                val rawClaimed = v.claimedHitoRewards as Collection<String>?
                val rawCatchup = v.activeCatchupHitoId as String?
                players[uuid] = PlayerBadges(
                    unlocked = cleanUnlocked,
                    hidden = cleanHidden,
                    records = recordsMap,
                    completedOnTimeHitos = LinkedHashSet(rawOnTime?.map { it.lowercase() } ?: emptyList()),
                    claimedHitoRewards = LinkedHashSet(rawClaimed?.map { it.lowercase() } ?: emptyList()),
                    activeCatchupHitoId = rawCatchup?.lowercase().orEmpty()
                )
            }
        }.onFailure {
            BadgesMod.LOGGER.error("Error cargando $targetFile", it)
        }
    }

    fun save() {
        val targetFile = file ?: return
        runCatching {
            targetFile.parentFile?.mkdirs()
            val out = LinkedHashMap<String, PlayerBadges>()
            for ((uuid, data) in players) {
                out[uuid.toString()] = data
            }
            val tempFile = java.io.File(targetFile.parentFile, "players.json.tmp")
            tempFile.writeText(gson.toJson(out), StandardCharsets.UTF_8)
            java.nio.file.Files.move(tempFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            BadgesMod.LOGGER.error("Error guardando $targetFile", it)
        }
    }

    fun player(uuid: UUID): PlayerBadges = players.computeIfAbsent(uuid) { PlayerBadges() }

    fun hasUnlocked(uuid: UUID, badgeId: String): Boolean =
        players[uuid]?.unlocked?.contains(badgeId.lowercase()) == true

    fun hasAnyAdmin(): Boolean =
        players.values.any { it.unlocked.contains(AdminAuth.ADMIN_BADGE_ID) }

    fun unlockedBadges(uuid: UUID): List<String> =
        player(uuid).unlocked.filter { TextCatalog.badgeExists(it) }

    fun visibleBadges(uuid: UUID): List<String> {
        val p = player(uuid)
        return p.unlocked.filter { (it !in p.hidden || TextCatalog.isNonHideable(it)) && TextCatalog.badgeExists(it) }
    }

    fun getRecord(uuid: UUID, badgeId: String): BadgeRecord? =
        player(uuid).records[badgeId.lowercase()]

    fun getAllRecords(uuid: UUID): Map<String, BadgeRecord> =
        Collections.unmodifiableMap(player(uuid).records)

    fun getRecordsSorted(uuid: UUID): List<BadgeRecord> =
        player(uuid).records.values.sortedBy { it.timestamp }

    fun grant(
        uuid: UUID,
        badgeId: String,
        gymLeader: String = "",
        region: String = "",
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        val clean = badgeId.lowercase()
        val p = player(uuid)
        val gymDef = GymBadges.BY_BADGE_ID[clean]
        val leader = if (gymLeader.isNotBlank()) gymLeader else (gymDef?.gymLeaderName ?: "")
        val reg = if (region.isNotBlank()) region else (gymDef?.region ?: "")

        val added = p.unlocked.add(clean)
        val hadRecord = p.records.containsKey(clean)
        if (added || !hadRecord) {
            p.records[clean] = BadgeRecord(
                badgeId = clean,
                gymLeader = leader,
                region = reg,
                timestamp = timestamp,
                date = BadgeRecord.formatDate(timestamp)
            )
            save()
            return true
        }
        return false
    }

    fun revoke(uuid: UUID, badgeId: String): Boolean {
        val clean = badgeId.lowercase()
        val p = player(uuid)
        val removed = p.unlocked.remove(clean)
        p.hidden.remove(clean)
        p.records.remove(clean)
        if (removed) save()
        return removed
    }

    fun clear(uuid: UUID) {
        val p = player(uuid)
        p.unlocked.clear()
        p.hidden.clear()
        p.records.clear()
        save()
    }

    fun toggleVisibility(uuid: UUID, badgeId: String): Boolean {
        val clean = badgeId.lowercase()
        val p = player(uuid)
        if (clean !in p.unlocked) return false
        if (TextCatalog.isNonHideable(clean)) {
            if (p.hidden.remove(clean)) save()
            return true
        }
        val nowVisible = if (clean in p.hidden) {
            p.hidden.remove(clean)
            true
        } else {
            p.hidden.add(clean)
            false
        }
        save()
        return nowVisible
    }

    fun setAllVisible(uuid: UUID, visible: Boolean) {
        val p = player(uuid)
        if (visible) {
            p.hidden.clear()
        } else {
            p.hidden.clear()
            p.hidden.addAll(p.unlocked.filterNot { TextCatalog.isNonHideable(it) })
        }
        save()
    }
}
