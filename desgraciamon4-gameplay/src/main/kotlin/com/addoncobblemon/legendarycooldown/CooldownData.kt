package com.addoncobblemon.legendarycooldown

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class CooldownData private constructor() : SavedData() {
    data class PlayerData(
        var cooldownUntil: Long = 0L,
        var bossBarEnabled: Boolean = true,
        var joinNoticeEnabled: Boolean = true,
        var readyMessageSent: Boolean = false
    )

    private val players = mutableMapOf<UUID, PlayerData>()

    fun player(uuid: UUID): PlayerData = players.getOrPut(uuid) { PlayerData() }
    fun remainingMillis(uuid: UUID, now: Long = System.currentTimeMillis()): Long =
        ((players[uuid]?.cooldownUntil ?: 0L) - now).coerceAtLeast(0L)

    fun changed() = setDirty()

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val storedPlayers = CompoundTag()
        players.forEach { (uuid, data) ->
            storedPlayers.put(uuid.toString(), CompoundTag().also {
                it.putLong("cooldownUntil", data.cooldownUntil)
                it.putBoolean("bossBarEnabled", data.bossBarEnabled)
                it.putBoolean("joinNoticeEnabled", data.joinNoticeEnabled)
                it.putBoolean("readyMessageSent", data.readyMessageSent)
            })
        }
        tag.put("players", storedPlayers)
        return tag
    }

    companion object {
        private const val DATA_ID = "legendary_cooldown"
        private val FACTORY = Factory(::CooldownData, ::load, null)

        fun get(server: MinecraftServer): CooldownData =
            server.overworld().dataStorage.computeIfAbsent(FACTORY, DATA_ID)

        @JvmStatic
        fun remainingMillis(server: MinecraftServer, uuid: UUID): Long =
            get(server).remainingMillis(uuid)

        @JvmStatic
        fun isBossBarEnabled(server: MinecraftServer, uuid: UUID): Boolean =
            get(server).player(uuid).bossBarEnabled

        @JvmStatic
        fun setBossBarEnabled(server: MinecraftServer, uuid: UUID, enabled: Boolean) {
            get(server).player(uuid).bossBarEnabled = enabled
            get(server).changed()
        }

        @JvmStatic
        fun isJoinNoticeEnabled(server: MinecraftServer, uuid: UUID): Boolean =
            get(server).player(uuid).joinNoticeEnabled

        @JvmStatic
        fun setJoinNoticeEnabled(server: MinecraftServer, uuid: UUID, enabled: Boolean) {
            get(server).player(uuid).joinNoticeEnabled = enabled
            get(server).changed()
        }

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): CooldownData {
            val data = CooldownData()
            val storedPlayers = tag.getCompound("players")
            storedPlayers.allKeys.forEach { key ->
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
                val stored = storedPlayers.getCompound(key)
                data.players[uuid] = PlayerData(
                    cooldownUntil = stored.getLong("cooldownUntil"),
                    bossBarEnabled = stored.getBoolean("bossBarEnabled"),
                    joinNoticeEnabled = if (stored.contains("joinNoticeEnabled")) {
                        stored.getBoolean("joinNoticeEnabled")
                    } else {
                        true
                    },
                    readyMessageSent = stored.getBoolean("readyMessageSent")
                )
            }
            return data
        }
    }
}