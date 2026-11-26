package com.desgraciamon.catchassistant

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.time.Instant
import java.util.UUID

data class LegendaryCaptureRecord(
    val speciesId: String,
    val speciesName: String,
    val capturedByPlayerName: String,
    val capturedByPlayerUuid: UUID,
    val capturedAt: Instant,
    val nextCaptureAt: Instant,
    var lastDiscordSent: Boolean = false,
    val shiny: Boolean = false
)

class LegendaryCaptureData private constructor() : SavedData() {
    val records = linkedMapOf<String, LegendaryCaptureRecord>()
    private val captureNotices = linkedMapOf<UUID, Boolean>()

    fun captureNoticeEnabled(playerId: UUID): Boolean = captureNotices[playerId] ?: true

    fun setCaptureNoticeEnabled(playerId: UUID, enabled: Boolean) {
        captureNotices[playerId] = enabled
        setDirty()
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val entries = CompoundTag()
        records.forEach { (speciesId, record) ->
            val recordTag = CompoundTag()
            recordTag.putString("speciesName", record.speciesName)
            recordTag.putString("capturedByPlayerName", record.capturedByPlayerName)
            recordTag.putString("capturedByPlayerUuid", record.capturedByPlayerUuid.toString())
            recordTag.putLong("capturedAtEpoch", record.capturedAt.toEpochMilli())
            recordTag.putLong("nextCaptureAtEpoch", record.nextCaptureAt.toEpochMilli())
            recordTag.putBoolean("lastDiscordSent", record.lastDiscordSent)
            recordTag.putBoolean("shiny", record.shiny)
            entries.put(speciesId, recordTag)
        }
        tag.put("records", entries)
        val notices = CompoundTag()
        captureNotices.forEach { (playerId, enabled) -> notices.putBoolean(playerId.toString(), enabled) }
        tag.put("captureNotices", notices)
        return tag
    }

    companion object {
        private const val DATA_ID = "desgraciamon_catch_assistant"
        private val FACTORY = Factory(::LegendaryCaptureData, ::load, null)

        fun get(server: MinecraftServer): LegendaryCaptureData =
            server.overworld().dataStorage.computeIfAbsent(FACTORY, DATA_ID)

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): LegendaryCaptureData {
            val data = LegendaryCaptureData()
            val entries = tag.getCompound("records")
            entries.allKeys.forEach { key ->
                val recordTag = entries.getCompound(key)
                val speciesId = key
                val uuid = runCatching { UUID.fromString(recordTag.getString("capturedByPlayerUuid")) }.getOrNull() ?: UUID(0L, 0L)
                data.records[speciesId] = LegendaryCaptureRecord(
                    speciesId = speciesId,
                    speciesName = recordTag.getString("speciesName"),
                    capturedByPlayerName = recordTag.getString("capturedByPlayerName"),
                    capturedByPlayerUuid = uuid,
                    capturedAt = Instant.ofEpochMilli(recordTag.getLong("capturedAtEpoch")),
                    nextCaptureAt = Instant.ofEpochMilli(recordTag.getLong("nextCaptureAtEpoch")),
                    lastDiscordSent = recordTag.getBoolean("lastDiscordSent"),
                    shiny = recordTag.getBoolean("shiny")
                )
            }
            val notices = tag.getCompound("captureNotices")
            notices.allKeys.forEach { key ->
                runCatching { UUID.fromString(key) }.getOrNull()?.let { playerId ->
                    data.captureNotices[playerId] = notices.getBoolean(key)
                }
            }
            return data
        }

        @JvmStatic
        fun isCaptureNoticeEnabled(server: MinecraftServer, playerId: UUID): Boolean =
            get(server).captureNoticeEnabled(playerId)

        @JvmStatic
        fun setCaptureNoticeEnabled(server: MinecraftServer, playerId: UUID, enabled: Boolean) {
            get(server).setCaptureNoticeEnabled(playerId, enabled)
        }
    }
}
