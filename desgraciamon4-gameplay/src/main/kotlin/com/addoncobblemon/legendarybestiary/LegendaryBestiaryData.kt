package com.addoncobblemon.legendarybestiary

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.saveddata.SavedData

data class CaptureRecord(
    val originalOwner: String,
    val currentOwner: String,
    val capturedAt: Long
)

data class LegendaryBestiaryEntry(
    val speciesId: String,
    val name: String,
    val captured: Boolean,
    val originalOwner: String = "",
    val currentOwner: String = "",
    val capturedAt: Long = 0L,
    val nextCaptureAt: Long = 0L
) {
    val owner: String get() = currentOwner.ifBlank { originalOwner }
}

class LegendaryBestiaryData private constructor() : SavedData() {
    private val captures = linkedMapOf<String, CaptureRecord>()

    fun recordCapture(speciesId: String, originalOwner: String, currentOwner: String = originalOwner, capturedAt: Long = System.currentTimeMillis()) {
        val existing = captures[speciesId]
        val orig = originalOwner.takeIf { it.isNotBlank() } ?: existing?.originalOwner?.takeIf { it.isNotBlank() } ?: currentOwner
        val curr = currentOwner.takeIf { it.isNotBlank() } ?: orig
        val ts = existing?.capturedAt?.takeIf { it > 0L } ?: capturedAt
        captures[speciesId] = CaptureRecord(orig, curr, ts)
        setDirty()
    }

    fun updateOwner(speciesId: String, currentOwner: String, originalOwner: String? = null, capturedAt: Long = System.currentTimeMillis()) {
        val existing = captures[speciesId]
        val orig = originalOwner?.takeIf { it.isNotBlank() } ?: existing?.originalOwner?.takeIf { it.isNotBlank() } ?: currentOwner
        val ts = existing?.capturedAt?.takeIf { it > 0L } ?: capturedAt
        captures[speciesId] = CaptureRecord(orig, currentOwner, ts)
        setDirty()
    }

    fun entries(): List<LegendaryBestiaryEntry> {
        val result = mutableListOf<LegendaryBestiaryEntry>()
        val baseSpecies = PokemonSpecies.species
            .filter { species ->
                !isUltraBeast(species) && species.labels.any { label ->
                    label.equals(CobblemonPokemonLabels.LEGENDARY, true) ||
                    label.equals(CobblemonPokemonLabels.MYTHICAL, true)
                }
            }

        for (species in baseSpecies) {
            val baseSpeciesId = species.resourceIdentifier.toString()
            val record = captures[baseSpeciesId]
            val timestamp = record?.capturedAt ?: 0L
            result.add(
                LegendaryBestiaryEntry(
                    speciesId = baseSpeciesId,
                    name = species.name,
                    captured = record != null,
                    originalOwner = record?.originalOwner.orEmpty(),
                    currentOwner = record?.currentOwner.orEmpty(),
                    capturedAt = timestamp,
                    nextCaptureAt = if (timestamp == 0L) 0L else timestamp + COOLDOWN_MILLIS
                )
            )

            // Regional and special alternate forms
            val addedFormKeys = mutableSetOf<String>()
            for (form in species.forms) {
                val fName = form.name.lowercase().trim()
                if (isNotableForm(fName)) {
                    val canonicalForm = normalizeFormKey(fName)
                    if (!addedFormKeys.add(canonicalForm)) continue
                    val formKey = "$baseSpeciesId#$fName"
                    val altKey = "$baseSpeciesId#$canonicalForm"
                    val formRecord = captures[formKey] ?: captures[altKey]
                    val formTs = formRecord?.capturedAt ?: 0L
                    result.add(
                        LegendaryBestiaryEntry(
                            speciesId = formKey,
                            name = getFormDisplayName(species.name, fName),
                            captured = formRecord != null,
                            originalOwner = formRecord?.originalOwner.orEmpty(),
                            currentOwner = formRecord?.currentOwner.orEmpty(),
                            capturedAt = formTs,
                            nextCaptureAt = if (formTs == 0L) 0L else formTs + COOLDOWN_MILLIS
                        )
                    )
                }
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val records = CompoundTag()
        captures.forEach { (speciesId, record) ->
            records.putString(speciesId, record.currentOwner)
            records.putString("$speciesId#original", record.originalOwner)
            records.putLong("$speciesId#capturedAt", record.capturedAt)
        }
        tag.put("captures", records)
        return tag
    }

    companion object {
        private const val DATA_ID = "legendary_bestiary"
        private const val COOLDOWN_MILLIS = 48L * 60L * 60L * 1000L
        private val FACTORY = Factory(::LegendaryBestiaryData, ::load, null)

        val ULTRA_BEAST_NAMES = setOf(
            "nihilego", "buzzwole", "pheromosa", "xurkitree", "celesteela",
            "kartana", "guzzlord", "poipole", "naganadel", "stakataka", "blacephalon"
        )

        fun isUltraBeast(species: com.cobblemon.mod.common.pokemon.Species): Boolean {
            if (species.labels.any { it.equals(CobblemonPokemonLabels.ULTRA_BEAST, true) || it.equals("ultra_beast", true) }) {
                return true
            }
            val path = species.resourceIdentifier.path.lowercase()
            val name = species.name.lowercase()
            return ULTRA_BEAST_NAMES.contains(path) || ULTRA_BEAST_NAMES.contains(name)
        }

        fun normalizeFormKey(fName: String): String {
            return when (val clean = fName.lowercase().trim()) {
                "galar", "galarian" -> "galar"
                "hisui", "hisuian" -> "hisui"
                "alola", "alolan" -> "alola"
                "paldea", "paldean" -> "paldea"
                else -> clean
            }
        }

        fun isNotableForm(fName: String): Boolean {
            val clean = fName.lowercase().trim()
            return clean == "galar" || clean == "galarian" ||
                   clean == "hisui" || clean == "hisuian" ||
                   clean == "alola" || clean == "alolan" ||
                   clean == "paldea" || clean == "paldean"
        }

        fun getFormDisplayName(speciesName: String, fName: String): String {
            val clean = fName.lowercase().trim()
            return when {
                clean == "galar" || clean == "galarian" -> "$speciesName de Galar"
                clean == "hisui" || clean == "hisuian" -> "$speciesName de Hisui"
                clean == "alola" || clean == "alolan" -> "$speciesName de Alola"
                clean == "paldea" || clean == "paldean" -> "$speciesName de Paldea"
                else -> "$speciesName (${fName.replaceFirstChar { it.uppercase() }})"
            }
        }

        fun getCanonicalSpeciesKey(pokemon: Pokemon): String {
            val basePath = pokemon.species.resourceIdentifier.path.lowercase().replace("_", "").replace("-", "")
            val fName = pokemon.form.name.lowercase().trim()
            val aspects = pokemon.aspects.map { it.lowercase() }
            return when {
                fName == "galar" || fName == "galarian" || "galarian" in aspects || "galar" in aspects -> "$basePath#galar"
                fName == "hisui" || fName == "hisuian" || "hisuian" in aspects || "hisui" in aspects -> "$basePath#hisui"
                fName == "alola" || fName == "alolan" || "alolan" in aspects || "alola" in aspects -> "$basePath#alola"
                else -> basePath
            }
        }

        fun getMaxAllowedForSpecies(speciesIdOrKey: String): Int {
            val cleanBase = speciesIdOrKey.substringBefore('#').substringAfter(':').lowercase().replace("_", "").replace("-", "")
            return when (cleanBase) {
                "cosmog", "cosmoem" -> 2
                "kubfu", "urshifu" -> 2
                "necrozma" -> 2
                "kyurem" -> 2
                "typenull" -> 2
                "calyrex" -> 2
                "poipole" -> 2
                "zygarde" -> 3
                "deoxys" -> 4
                "meltan" -> 99
                else -> 1
            }
        }

        fun countPlayerOwnedMatching(player: ServerPlayer, targetPokemon: Pokemon): Int {
            val targetKey = getCanonicalSpeciesKey(targetPokemon)
            val partyCount = runCatching {
                Cobblemon.storage.getParty(player).count { getCanonicalSpeciesKey(it) == targetKey }
            }.getOrDefault(0)
            val pcCount = runCatching {
                Cobblemon.storage.getPC(player).count { getCanonicalSpeciesKey(it) == targetKey }
            }.getOrDefault(0)
            return partyCount + pcCount
        }

        fun getSpeciesLimitInfo(speciesId: String): String {
            val cleanBase = speciesId.substringBefore('#').substringAfter(':').lowercase().replace("_", "").replace("-", "")
            return when (cleanBase) {
                "cosmog", "cosmoem" -> "Límite global: Máx. 2 compartido (baja a 1/0 con Solgaleo/Lunala)"
                "articuno", "zapdos", "moltres" -> "Límite global: Máx. 2 en el servidor (Kanto/Galar)"
                else -> "Límite global: Máx. 1 en todo el servidor"
            }
        }

        fun getMonumentStructureInfo(speciesId: String): String {
            val cleanBase = speciesId.substringBefore('#').substringAfter(':').lowercase().replace("_", "").replace("-", "")
            val formPart = if (speciesId.contains('#')) speciesId.substringAfter('#').lowercase().trim() else ""
            if (cleanBase in setOf("articuno", "zapdos", "moltres") && formPart in setOf("galar", "galarian")) {
                return "Estructura: Árbol Dinamax (Dyna Tree)"
            }
            return when (cleanBase) {
                "articuno" -> "Estructura: Islas Espuma / Santuario Ártico"
                "zapdos" -> "Estructura: Central Eléctrica"
                "moltres" -> "Estructura: Monte Ascua"
                "mewtwo" -> "Estructura: Cueva Celeste / Mansión Pokémon"
                "mew" -> "Estructura: Templo de la Jungla Lejana"
                "raikou", "entei", "suicune" -> "Estructura: Torre Quemada"
                "lugia" -> "Estructura: Islas Remolino"
                "hooh" -> "Estructura: Torre Campana"
                "celebi" -> "Estructura: Santuario del Encinar"
                "regirock", "regice", "registeel", "regigigas", "regieleki", "regidrago" -> "Estructura: Ruinas Subterráneas / Templo Puntaneva"
                "latias", "latios" -> "Estructura: Isla del Sur"
                "kyogre" -> "Estructura: Cueva Abisal"
                "groudon" -> "Estructura: Cueva Terrena"
                "rayquaza" -> "Estructura: Pilar Celeste"
                "jirachi" -> "Estructura: Roca Deseo"
                "deoxys" -> "Estructura: Isla Origen (Meteorito)"
                "uxie", "mesprit", "azelf" -> "Estructura: Cavernas de los Lagos (Agudeza, Veracidad, Valor)"
                "dialga", "palkia", "arceus" -> "Estructura: Columna Lanza (Monte Corona)"
                "heatran" -> "Estructura: Montaña Dura (Stark Mountain)"
                "giratina" -> "Estructura: Cueva Retorno / Mundo Distorsión"
                "cresselia", "darkrai" -> "Estructura: Isla Plenilunio / Isla Lunanueva"
                "manaphy", "phione" -> "Estructura: Templo del Mar"
                "shaymin" -> "Estructura: Paraíso Floral"
                "victini" -> "Estructura: Isla Libertad"
                "cobalion", "terrakion", "virizion", "keldeo" -> "Estructura: Cueva Loza / Pantano Teja"
                "tornadus", "thundurus", "landorus", "enamorus" -> "Estructura: Santuario de la Abundancia"
                "reshiram", "zekrom", "kyurem" -> "Estructura: Torre Duodraco / Boquete Gigante"
                "meloetta" -> "Estructura: Café Sonata"
                "genesect" -> "Estructura: Laboratorio P2"
                "xerneas", "yveltal", "zygarde" -> "Estructura: Guarida Flare / Cueva Desenlace"
                "diancie" -> "Estructura: Reino Diamante"
                "hoopa" -> "Estructura: Santuario del Desierto"
                "volcanion" -> "Estructura: Meseta Nebulosa"
                "typenull", "silvally" -> "Estructura: Paraíso Æther"
                "tapukoko", "tapulele", "tapubulu", "tapufini" -> "Estructura: Ruinas de Alola (Guerra, Vida, Cosecha, Tránsito)"
                "cosmog", "cosmoem", "solgaleo", "lunala", "necrozma" -> "Estructura: Altar del Sol y de la Luna"
                "magearna" -> "Estructura: Reino Azoth"
                "marshadow" -> "Estructura: Monte Lanakila"
                "zeraora" -> "Estructura: Desierto Árido"
                "meltan", "melmetal" -> "Estructura: Ruinas de Acero Ancestral"
                "zacian", "zamazenta", "eternatus" -> "Estructura: Bosque Oniria / Planta Energía"
                "kubfu", "urshifu" -> "Estructura: Torres de los Dos Puños"
                "zarude" -> "Estructura: Bosque Maestro"
                "calyrex", "glastrier", "spectrier" -> "Estructura: Templo Corona"
                "wochien", "chienpao", "tinglu", "chiyu" -> "Estructura: Santuarios de las Cuatro Ruinas"
                "koraidon", "miraidon" -> "Estructura: Área Cero"
                "okidogi", "munkidori", "fezandipiti" -> "Estructura: Parque Leal (Noroteo)"
                "ogerpon" -> "Estructura: Cueva Espantosa"
                "terapagos" -> "Estructura: Abismo Cero"
                "pecharunt" -> "Estructura: Puesto de Mochi (Noroteo)"
                else -> "Estructura: Monumento Legendario Ancestral"
            }
        }

        fun get(server: MinecraftServer): LegendaryBestiaryData =
            server.overworld().dataStorage.computeIfAbsent(FACTORY, DATA_ID)

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): LegendaryBestiaryData {
            val data = LegendaryBestiaryData()
            val records = tag.getCompound("captures")
            records.allKeys.filterNot { it.contains('#') }.forEach { speciesId ->
                val curr = records.getString(speciesId)
                val orig = if (records.contains("$speciesId#original")) records.getString("$speciesId#original") else curr
                val ts = records.getLong("$speciesId#capturedAt")
                data.captures[speciesId] = CaptureRecord(
                    originalOwner = orig.ifBlank { curr },
                    currentOwner = curr,
                    capturedAt = ts
                )
            }
            return data
        }
    }
}
