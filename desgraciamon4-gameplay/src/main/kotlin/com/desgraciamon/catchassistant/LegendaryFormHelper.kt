package com.desgraciamon.catchassistant

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.server.level.ServerPlayer

object LegendaryFormHelper {
    private val ULTRA_BEASTS = setOf(
        "nihilego", "buzzwole", "pheromosa", "xurkitree", "celesteela",
        "kartana", "guzzlord", "poipole", "naganadel", "stakataka", "blacephalon"
    )

    fun isUltraBeast(speciesPath: String, speciesName: String): Boolean {
        val p = speciesPath.lowercase()
        val n = speciesName.lowercase()
        return ULTRA_BEASTS.contains(p) || ULTRA_BEASTS.contains(n)
    }

    fun normalizeFormKey(formName: String): String {
        return when (val clean = formName.lowercase().trim()) {
            "galar", "galarian" -> "galar"
            "hisui", "hisuian" -> "hisui"
            "alola", "alolan" -> "alola"
            else -> clean
        }
    }

    fun isNotableForm(formName: String): Boolean {
        val clean = formName.lowercase().trim()
        return clean in setOf(
            "galar", "galarian", "origin", "therian", "hisui", "hisuian",
            "alola", "alolan", "crowned", "ice_rider", "ice", "shadow_rider", "shadow",
            "rapid_strike", "rapid-strike", "sky", "unbound", "pirouette",
            "black", "white", "dusk_mane", "dusk", "dawn_wings", "dawn", "ultra"
        )
    }

    fun getFormDisplayName(speciesName: String, formName: String): String {
        val clean = formName.lowercase().trim()
        return when {
            clean == "galar" || clean == "galarian" -> "$speciesName de Galar"
            clean == "origin" -> "$speciesName Origen"
            clean == "therian" -> "$speciesName Tótem"
            clean == "hisui" || clean == "hisuian" -> "$speciesName de Hisui"
            clean == "alola" || clean == "alolan" -> "$speciesName de Alola"
            clean == "crowned" -> "$speciesName Corona"
            clean == "ice_rider" || clean == "ice" -> "$speciesName Jinete Glacial"
            clean == "shadow_rider" || clean == "shadow" -> "$speciesName Jinete Espectral"
            clean == "rapid_strike" || clean == "rapid-strike" -> "$speciesName Golpe Fluido"
            clean == "sky" -> "$speciesName Forma Cielo"
            clean == "unbound" -> "$speciesName Desatado"
            clean == "pirouette" -> "$speciesName Forma Danza"
            clean == "black" -> "$speciesName Negro"
            clean == "white" -> "$speciesName Blanco"
            clean == "dusk_mane" || clean == "dusk" -> "$speciesName Melena Crepuscular"
            clean == "dawn_wings" || clean == "dawn" -> "$speciesName Alas del Alba"
            clean == "ultra" -> "Ultra $speciesName"
            else -> "$speciesName (${formName.replaceFirstChar { it.uppercase() }})"
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

    fun getFormPokeApiId(speciesPath: String, formName: String): Int? {
        val sp = speciesPath.lowercase().trim()
        val fn = formName.lowercase().trim()
        return when {
            sp == "articuno" && (fn == "galar" || fn == "galarian") -> 10169
            sp == "zapdos" && (fn == "galar" || fn == "galarian") -> 10170
            sp == "moltres" && (fn == "galar" || fn == "galarian") -> 10171
            sp == "dialga" && fn == "origin" -> 10245
            sp == "palkia" && fn == "origin" -> 10246
            sp == "giratina" && fn == "origin" -> 10007
            sp == "landorus" && fn == "therian" -> 10021
            sp == "thundurus" && fn == "therian" -> 10020
            sp == "tornadus" && fn == "therian" -> 10019
            sp == "enamorus" && fn == "therian" -> 10249
            sp == "zacian" && fn == "crowned" -> 10188
            sp == "zamazenta" && fn == "crowned" -> 10189
            sp == "calyrex" && (fn == "ice" || fn == "ice_rider") -> 10193
            sp == "calyrex" && (fn == "shadow" || fn == "shadow_rider") -> 10194
            sp == "urshifu" && (fn == "rapid-strike" || fn == "rapid_strike") -> 10191
            sp == "shaymin" && fn == "sky" -> 10006
            sp == "hoopa" && fn == "unbound" -> 10086
            sp == "meloetta" && fn == "pirouette" -> 10018
            sp == "kyurem" && fn == "black" -> 10022
            sp == "kyurem" && fn == "white" -> 10023
            sp == "necrozma" && (fn == "dusk" || fn == "dusk_mane") -> 10155
            sp == "necrozma" && (fn == "dawn" || fn == "dawn_wings") -> 10156
            sp == "necrozma" && fn == "ultra" -> 10157
            else -> null
        }
    }
}
