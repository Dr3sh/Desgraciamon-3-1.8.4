package com.desgraciamon.legendarylimits

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.storage.PokemonStore
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.util.UUID

data class TrackedPokemonEntry(
    val pokemonUuid: UUID,
    val speciesId: String,
    val ownerUuid: UUID,
    val ownerName: String,
    val updatedAt: Long,
    val isDebug: Boolean = false
)

data class ResyncStats(
    val playersScanned: Int,
    val totalTracked: Int,
    val uniqueSpecies: Int
)

class LegendaryLimitsData private constructor() : SavedData() {
    private val entries = linkedMapOf<UUID, TrackedPokemonEntry>()

    fun allEntries(): List<TrackedPokemonEntry> = entries.values.toList()

    fun countSpecies(speciesId: String): Int {
        val normalized = LegendaryLimitsConfig.normalizeSpeciesId(speciesId)
        return entries.values.count { it.speciesId == normalized }
    }

    fun entriesForSpecies(speciesId: String): List<TrackedPokemonEntry> {
        val normalized = LegendaryLimitsConfig.normalizeSpeciesId(speciesId)
        return entries.values.filter { it.speciesId == normalized }
    }

    fun upsert(
        pokemonUuid: UUID,
        speciesId: String,
        ownerUuid: UUID,
        ownerName: String,
        isDebug: Boolean = false
    ) {
        val normalized = LegendaryLimitsConfig.normalizeSpeciesId(speciesId)
        if (normalized.isEmpty()) return
        val existing = entries[pokemonUuid]
        if (existing != null &&
            existing.speciesId == normalized &&
            existing.ownerUuid == ownerUuid &&
            existing.ownerName == ownerName &&
            existing.isDebug == isDebug
        ) {
            return
        }
        entries[pokemonUuid] = TrackedPokemonEntry(
            pokemonUuid = pokemonUuid,
            speciesId = normalized,
            ownerUuid = ownerUuid,
            ownerName = ownerName,
            updatedAt = System.currentTimeMillis(),
            isDebug = isDebug
        )
        setDirty()
    }

    fun remove(pokemonUuid: UUID): Boolean {
        val removed = entries.remove(pokemonUuid) != null
        if (removed) setDirty()
        return removed
    }

    fun removeBySpecies(speciesId: String): Int {
        val normalized = LegendaryLimitsConfig.normalizeSpeciesId(speciesId)
        val toRemove = entries.values.filter { it.speciesId == normalized }.map { it.pokemonUuid }
        toRemove.forEach { entries.remove(it) }
        if (toRemove.isNotEmpty()) setDirty()
        return toRemove.size
    }

    fun clearAll(): Int {
        val count = entries.size
        if (count > 0) {
            entries.clear()
            setDirty()
        }
        return count
    }

    fun syncPokemon(ownerUuid: UUID, ownerName: String, pokemon: Pokemon) {
        val speciesId = LegendaryLimitsConfig.normalizeSpeciesId(pokemon.species.resourceIdentifier.toString())
        if (LegendaryLimitsConfig.isTrackedSpecies(speciesId, pokemon.species)) {
            upsert(pokemon.uuid, speciesId, ownerUuid, ownerName, isDebug = false)
        } else {
            remove(pokemon.uuid)
        }
    }

    fun syncPlayer(player: ServerPlayer) {
        val party = runCatching { Cobblemon.storage.getParty(player) }.getOrNull()
        val pc = runCatching { Cobblemon.storage.getPC(player) }.getOrNull()
        if (party == null && pc == null) return
        reconcilePlayerStorage(player.uuid, player.name.string, party, pc)
    }

    private fun reconcilePlayerStorage(
        ownerUuid: UUID,
        ownerName: String,
        party: PokemonStore<*>?,
        pc: PokemonStore<*>?
    ) {
        val presentUuids = HashSet<UUID>()
        val collect: (Pokemon) -> Unit = { pokemon ->
            val speciesId = LegendaryLimitsConfig.normalizeSpeciesId(pokemon.species.resourceIdentifier.toString())
            if (LegendaryLimitsConfig.isTrackedSpecies(speciesId, pokemon.species)) {
                presentUuids.add(pokemon.uuid)
                upsert(pokemon.uuid, speciesId, ownerUuid, ownerName, isDebug = false)
            }
        }

        party?.forEach(collect)
        pc?.forEach(collect)

        val staleIds = entries.values
            .filter { !it.isDebug && it.ownerUuid == ownerUuid && it.pokemonUuid !in presentUuids }
            .map { it.pokemonUuid }

        if (staleIds.isNotEmpty()) {
            staleIds.forEach { entries.remove(it) }
            setDirty()
        }
    }

    fun resyncAllPlayers(server: MinecraftServer): ResyncStats {
        val uuids = linkedSetOf<UUID>()

        server.playerList.players.forEach { uuids.add(it.uuid) }

        val playerDataDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
        if (Files.isDirectory(playerDataDir)) {
            runCatching {
                Files.list(playerDataDir).use { stream ->
                    stream.forEach { path ->
                        val fileName = path.fileName.toString()
                        if (fileName.endsWith(".dat")) {
                            runCatching { UUID.fromString(fileName.removeSuffix(".dat")) }
                                .getOrNull()
                                ?.let { uuids.add(it) }
                        }
                    }
                }
            }
        }

        entries.values.filter { !it.isDebug }.forEach { uuids.add(it.ownerUuid) }

        var scanned = 0
        for (uuid in uuids) {
            val onlinePlayer = server.playerList.getPlayer(uuid)
            val resolvedName = onlinePlayer?.name?.string
                ?: server.profileCache?.get(uuid)?.orElse(null)?.name
                ?: entries.values.firstOrNull { it.ownerUuid == uuid && it.ownerName.isNotBlank() }?.ownerName
                ?: uuid.toString().substring(0, 8)

            val party = if (onlinePlayer != null) {
                runCatching { Cobblemon.storage.getParty(onlinePlayer) }.getOrNull()
            } else {
                runCatching { Cobblemon.storage.getParty(uuid, server.registryAccess()) }.getOrNull()
            }
            val pc = if (onlinePlayer != null) {
                runCatching { Cobblemon.storage.getPC(onlinePlayer) }.getOrNull()
            } else {
                runCatching { Cobblemon.storage.getPC(uuid, server.registryAccess()) }.getOrNull()
            }

            if (party != null || pc != null) {
                reconcilePlayerStorage(uuid, resolvedName, party, pc)
                scanned++
            }
        }

        val activeSpecies = entries.values.map { it.speciesId }.toSet().size
        return ResyncStats(
            playersScanned = scanned,
            totalTracked = entries.size,
            uniqueSpecies = activeSpecies
        )
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = ListTag()
        for (entry in entries.values) {
            val item = CompoundTag()
            item.putUUID("pokemonUuid", entry.pokemonUuid)
            item.putString("speciesId", entry.speciesId)
            item.putUUID("ownerUuid", entry.ownerUuid)
            item.putString("ownerName", entry.ownerName)
            item.putLong("updatedAt", entry.updatedAt)
            item.putBoolean("isDebug", entry.isDebug)
            list.add(item)
        }
        tag.put("entries", list)
        return tag
    }

    companion object {
        private const val DATA_ID = "desgraciamon_legendary_limits"
        private val FACTORY = Factory(::LegendaryLimitsData, ::load, null)

        fun get(server: MinecraftServer): LegendaryLimitsData =
            server.overworld().dataStorage.computeIfAbsent(FACTORY, DATA_ID)

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): LegendaryLimitsData {
            val data = LegendaryLimitsData()
            val list = tag.getList("entries", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val item = list.getCompound(i)
                if (!item.hasUUID("pokemonUuid") || !item.hasUUID("ownerUuid")) continue
                val pokemonUuid = item.getUUID("pokemonUuid")
                val speciesId = LegendaryLimitsConfig.normalizeSpeciesId(item.getString("speciesId"))
                if (speciesId.isEmpty()) continue
                val ownerUuid = item.getUUID("ownerUuid")
                val ownerName = item.getString("ownerName")
                val updatedAt = item.getLong("updatedAt")
                val isDebug = item.getBoolean("isDebug")
                data.entries[pokemonUuid] = TrackedPokemonEntry(
                    pokemonUuid = pokemonUuid,
                    speciesId = speciesId,
                    ownerUuid = ownerUuid,
                    ownerName = ownerName,
                    updatedAt = updatedAt,
                    isDebug = isDebug
                )
            }
            return data
        }
    }
}
