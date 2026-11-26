package com.desgraciamon.mirror

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Un Pokémon guardado en datos de una temporada anterior. */
class MemoryEntry(
    val tag: CompoundTag,
    val season: String,
    val origin: String,
    val speciesId: String,
    val level: Int,
    val shiny: Boolean,
    val nickname: String,
    val legendary: Boolean,
    val fromParty: Boolean,
    val ball: String,
)

class OwnerMemories(val all: List<MemoryEntry>, val selected: List<MemoryEntry>)

/**
 * Lee los archivos `.dat` de Cobblemon (`playerpartystore/xx/<uuid>.dat` y `pcstore/xx/<uuid>.dat`)
 * de cada temporada configurada (`Desgraciamon 1`, `Desgraciamon 2`, etc.) y construye el pool de rotación.
 */
object MemoryLoader {
    private val logger = LoggerFactory.getLogger("desgraciamon4-mirror")
    private val cache = ConcurrentHashMap<UUID, OwnerMemories>()
    private val SLOT = Regex("Slot\\d+")
    private val BOX = Regex("Box\\d+")

    fun clearCache() = cache.clear()

    fun get(owner: UUID, server: MinecraftServer? = null): OwnerMemories = cache.getOrPut(owner) { load(owner, server) }

    private fun load(owner: UUID, server: MinecraftServer?): OwnerMemories {
        val config = MirrorSettings.config
        val all = mutableListOf<MemoryEntry>()
        val seenKeys = hashSetOf<String>()
        val discordId = server?.let { DiscordBridgeHook.getDiscordId(it, owner) }
        val username = server?.let { srv ->
            srv.playerList.getPlayer(owner)?.scoreboardName ?: srv.profileCache?.get(owner)?.orElse(null)?.name
        }
        val candidateUuids = PlayerLinksConfig.resolveMemoryUuids(discordId, username, owner)

        for (source in config.sources) {
            val seasonLabel = normalizeSeasonLabel(source.label, source.path)
            for (root in readStores(source, "playerpartystore", candidateUuids)) {
                slotsOf(root).forEach { (slot, tag) ->
                    toEntry(tag, seasonLabel, "Equipo · $slot", true)?.let { entry ->
                        val dedupKey = pokemonDedupKey(tag, entry)
                        if (seenKeys.add(dedupKey)) all.add(entry)
                    }
                }
            }
            for (root in readStores(source, "pcstore", candidateUuids)) {
                root.allKeys.filter { BOX.matches(it) }
                    .sortedBy { it.removePrefix("Box").toInt() }
                    .forEach { boxKey ->
                        val box = root.getCompound(boxKey)
                        val boxNumber = boxKey.removePrefix("Box").toInt() + 1
                        slotsOf(box).forEach { (_, tag) ->
                            toEntry(tag, seasonLabel, "PC · Caja $boxNumber", false)?.let { entry ->
                                val dedupKey = pokemonDedupKey(tag, entry)
                                if (seenKeys.add(dedupKey)) all.add(entry)
                            }
                        }
                    }
            }
        }
        return OwnerMemories(all, select(all, config))
    }

    private fun normalizeSeasonLabel(label: String, path: String): String {
        val combined = "$label $path".lowercase()
        return when {
            combined.contains("2") -> "Desgraciamon 2"
            combined.contains("1") -> "Desgraciamon 1"
            label.isNotBlank() -> label
            else -> "Desgraciamon 1"
        }
    }

    private fun pokemonDedupKey(tag: CompoundTag, entry: MemoryEntry): String {
        if (tag.hasUUID("UUID")) {
            return tag.getUUID("UUID").toString()
        }
        return "${entry.season}|${entry.speciesId}|${entry.level}|${entry.nickname}"
    }

    private fun slotsOf(root: CompoundTag): List<Pair<String, CompoundTag>> =
        root.allKeys.filter { SLOT.matches(it) }
            .sortedBy { it.removePrefix("Slot").toInt() }
            .map { it to root.getCompound(it) }
            .filter { (_, tag) -> tag.contains("Species") }

    private fun readStores(source: MemorySource, store: String, candidateUuids: List<String>): List<CompoundTag> {
        val base = File(source.path, store)
        val results = mutableListOf<CompoundTag>()
        for (id in candidateUuids) {
            val candidates = listOf(File(base, "${id.take(2)}/$id.dat"), File(base, "$id.dat"))
            val file = candidates.firstOrNull { it.isFile } ?: continue
            val tag = runCatching { NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap()) }
                .recoverCatching { NbtIo.read(file.toPath()) ?: throw IllegalStateException("archivo vacío") }
                .onFailure { logger.error("No se pudo leer ${file.path}", it) }
                .getOrNull()
            if (tag != null) results.add(tag)
        }
        return results
    }

    private fun toEntry(tag: CompoundTag, season: String, origin: String, fromParty: Boolean): MemoryEntry? {
        val speciesId = tag.getString("Species").ifBlank { return null }
        val species = ResourceLocation.tryParse(speciesId)?.let { PokemonSpecies.getByIdentifier(it) }
        if (species == null) {
            logger.warn("Especie desconocida en recuerdos: {}", speciesId)
            return null
        }
        val labels = species.labels
        val forcedAspects = if (tag.contains("ForcedAspects")) {
            val list = tag.getList("ForcedAspects", 8)
            (0 until list.size).map { list.getString(it) }.toSet()
        } else emptySet()

        return MemoryEntry(
            tag = tag,
            season = season,
            origin = origin,
            speciesId = speciesId,
            level = tag.getInt("Level").coerceAtLeast(1),
            shiny = tag.getBoolean("Shiny"),
            nickname = tag.getString("Nickname"),
            legendary = ("legendary" in labels || "mythical" in labels || "legendary" in forcedAspects) && "ultra_beast" !in labels,
            fromParty = fromParty,
            ball = tag.getString("CaughtBall"),
        )
    }

    private fun select(all: List<MemoryEntry>, config: MirrorConfig): List<MemoryEntry> {
        val max = config.maxPokemon.coerceAtLeast(1)
        val picked = LinkedHashSet<MemoryEntry>()

        fun addAll(entries: Sequence<MemoryEntry>) {
            for (entry in entries) {
                if (picked.size >= max) return
                picked += entry
            }
        }

        val byLevel = all.asSequence().sortedByDescending { it.level }
        if (config.includeParty) addAll(all.asSequence().filter { it.fromParty })
        if (config.includeLegendaries) addAll(byLevel.filter { it.legendary })
        if (config.includeShinies) addAll(byLevel.filter { it.shiny })
        if (config.includeNicknamed) addAll(byLevel.filter { it.nickname.isNotBlank() })

        if (config.fillWithHighestLevel && picked.size < max) {
            // Para los Pokémon de PC comunes (sin mote ni shiny), incluir máximo 1 por especie+temporada
            // para no saturar la rotación con cajas de crianza (ej. 25 Dittos o 20 Cubones nivel 1).
            val seenNormalSpecies = HashSet<String>()
            for (p in picked) {
                seenNormalSpecies += "${p.season}:${p.speciesId}"
            }
            for (entry in byLevel) {
                if (picked.size >= max) break
                if (entry in picked) continue
                val key = "${entry.season}:${entry.speciesId}"
                if (seenNormalSpecies.add(key)) {
                    picked += entry
                }
            }
        }
        return picked.toList()
    }
}
