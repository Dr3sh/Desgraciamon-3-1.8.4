package com.desgraciamon.showdownarena

enum class ArenaBattleType(val displayName: String) {
    SINGLES("Individual (1v1)"),
    DOUBLES("Dobles (2v2)")
}

enum class FormatCategory(val idSlug: String, val displayName: String, val description: String) {
    RANDOM_COMPETITIVE("random", "Random Battle (Competitivo)", "Equipos aleatorios con sets y estrategias de Pokémon Showdown."),
    RANDOM_CHAOS("chaos", "Random Puro (Caos)", "Pokémon totalmente aleatorios con movimientos y objetos variados."),
    OU("ou", "OU (OverUsed)", "Formato insignia de Smogon: Pokémon competitivos de primer nivel."),
    UBERS("ubers", "Ubers (Titanes & Leyendas)", "Combates al máximo poder con Pokémon legendarios y formas supremas."),
    VGC_DOUBLES("vgc", "VGC / Dobles", "Combates dobles 2v2 al estilo del campeonato oficial de Pokémon."),
    MONOTYPE("monotype", "Monotype", "Todos los integrantes del equipo comparten el mismo tipo elemental.")
}

data class ArenaFormat(
    val id: String,
    val name: String,
    val generation: Int, // 1 a 9, o 0 para Nacional/Global
    val category: FormatCategory,
    val battleType: ArenaBattleType = ArenaBattleType.SINGLES,
    val showdownMod: String? = null,
    val fixedLevel: Int? = null,
    val teamSize: Int = 6
) {
    val genDisplay: String
        get() = if (generation in 1..9) "Gen $generation" else "Todas las Gens"

    val fullTitle: String
        get() = "$genDisplay - ${category.displayName} (${teamSize}v${teamSize})"

    val showdownFormatId: String
        get() = when (category) {
            FormatCategory.RANDOM_COMPETITIVE -> if (generation in 1..9) "gen${generation}randombattle" else "gen9randombattle"
            FormatCategory.RANDOM_CHAOS -> if (generation in 1..9) "gen${generation}customgame" else "gen9customgame"
            FormatCategory.OU -> if (generation in 1..9) "gen${generation}ou" else "gen9ou"
            FormatCategory.UBERS -> if (generation in 1..9) "gen${generation}ubers" else "gen9ubers"
            FormatCategory.VGC_DOUBLES -> if (generation in 1..9) "gen${generation}randomdoublesbattle" else "gen9vgc2024"
            FormatCategory.MONOTYPE -> if (generation in 1..9) "gen${generation}monotype" else "gen9monotype"
        }
}

object ArenaFormatCatalog {

    val formats: List<ArenaFormat> = buildList {
        // Formatos por Generación (Gen 1 a Gen 9) + Global (Gen 0)
        for (gen in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)) {
            val prefix = if (gen in 1..9) "gen$gen" else "global"
            val genLabel = if (gen in 1..9) "Gen $gen" else "Global"

            // 1. Random Battle Competitivo Showdown
            add(ArenaFormat(
                id = "$prefix-random",
                name = "$genLabel Random Battle",
                generation = gen,
                category = FormatCategory.RANDOM_COMPETITIVE,
                battleType = ArenaBattleType.SINGLES,
                teamSize = 6
            ))

            // 2. Random Puro / Caos
            add(ArenaFormat(
                id = "$prefix-chaos",
                name = "$genLabel Random Puro",
                generation = gen,
                category = FormatCategory.RANDOM_CHAOS,
                battleType = ArenaBattleType.SINGLES,
                teamSize = 6
            ))

            // 3. OU (OverUsed)
            add(ArenaFormat(
                id = "$prefix-ou",
                name = "$genLabel OU",
                generation = gen,
                category = FormatCategory.OU,
                battleType = ArenaBattleType.SINGLES,
                fixedLevel = 50,
                teamSize = 6
            ))

            // 4. Ubers
            add(ArenaFormat(
                id = "$prefix-ubers",
                name = "$genLabel Ubers",
                generation = gen,
                category = FormatCategory.UBERS,
                battleType = ArenaBattleType.SINGLES,
                fixedLevel = 50,
                teamSize = 6
            ))

            // 5. VGC / Dobles
            add(ArenaFormat(
                id = "$prefix-vgc",
                name = "$genLabel VGC Dobles",
                generation = gen,
                category = FormatCategory.VGC_DOUBLES,
                battleType = ArenaBattleType.DOUBLES,
                fixedLevel = 50,
                teamSize = 4
            ))

            // 6. Monotype
            add(ArenaFormat(
                id = "$prefix-monotype",
                name = "$genLabel Monotype",
                generation = gen,
                category = FormatCategory.MONOTYPE,
                battleType = ArenaBattleType.SINGLES,
                fixedLevel = 50,
                teamSize = 6
            ))
        }
    }

    fun forGeneration(generation: Int): List<ArenaFormat> {
        return formats.filter { it.generation == generation }
    }

    fun forCategory(category: FormatCategory): List<ArenaFormat> {
        return formats.filter { it.category == category }
    }

    fun find(idOrQuery: String): ArenaFormat? {
        val raw = idOrQuery.lowercase().trim()
        // Soporte para sufijo opcional de tamaño de equipo: ej. "gen3-ou-3" o "global-vgc-4"
        val sizeMatch = Regex("^(.*)-([1-6])$").find(raw)
        val baseQuery = sizeMatch?.groupValues?.get(1) ?: raw
        val customSize = sizeMatch?.groupValues?.get(2)?.toIntOrNull()

        val normalized = baseQuery.replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .replace("random-competitive", "random")
            .replace("random-chaos", "chaos")
            .replace("vgc-doubles", "vgc")

        val baseFormat = formats.firstOrNull {
            it.id == normalized ||
            it.id.replace("-", "") == normalized.replace("-", "") ||
            it.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') == normalized
        } ?: return null

        return if (customSize != null) {
            val minSize = if (baseFormat.battleType == ArenaBattleType.DOUBLES) 2 else 1
            baseFormat.copy(teamSize = customSize.coerceIn(minSize, 6))
        } else {
            baseFormat
        }
    }
}
