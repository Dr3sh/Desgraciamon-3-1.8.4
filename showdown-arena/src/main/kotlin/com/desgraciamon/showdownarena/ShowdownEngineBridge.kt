package com.desgraciamon.showdownarena

import com.cobblemon.mod.common.battles.runner.ShowdownService
import com.google.gson.Gson
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ThreadLocalRandom

data class ShowdownFormatInfo(
    val id: String,
    val name: String,
    val generation: Int,
    val gameType: String?,
    val mod: String?,
    val team: String?,
    val ruleset: List<String> = emptyList(),
    val banlist: List<String> = emptyList(),
    val unbanlist: List<String> = emptyList(),
    val restricted: List<String> = emptyList(),
    val customRules: List<String> = emptyList(),
    val teamLength: Map<String, Int>? = null,
    val maxLevel: Int? = null,
    val forcedLevel: Int? = null
)

data class ShowdownPokemonSet(
    val species: String,
    val forme: String? = null,
    val gender: String? = null,
    val item: String? = null,
    val ability: String? = null,
    val nature: String? = null,
    val moves: List<String> = emptyList(),
    val level: Int? = null,
    val shiny: Boolean? = null,
    val teraType: String? = null,
    val gigantamax: Boolean? = null,
    val dynamaxLevel: Int? = null,
    val evs: Map<String, Int> = emptyMap(),
    val ivs: Map<String, Int> = emptyMap()
)

object ShowdownEngineBridge {
    private val gson = Gson()
    private val lock = Any()

    @Volatile
    private var formatCache: List<ShowdownFormatInfo>? = null

    @Volatile
    private var jsAllowedInitialized = false

    @Volatile
    private var isWarmedUp = false

    fun warmupAsync() {
        if (isWarmedUp) return
        Thread({
            try {
                warmup()
            } catch (_: Exception) {}
        }, "Showdown-Warmup-Thread").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun warmup() {
        if (isWarmedUp) return
        synchronized(lock) {
            if (isWarmedUp) return
            try {
                // Forzar inicialización de Dex y generadores comunes para compilar JIT de GraalVM
                evaluateJson(
                    """
                    (() => {
                        const {Dex, Teams} = require('./sim');
                        // Pre-cargar Dex de gens comunes
                        for (let g = 1; g <= 9; g++) {
                            try {
                                Teams.getGenerator('gen' + g + 'randombattle', [1234, 5678, 9101, 1121]);
                            } catch (_) {}
                        }
                        return true;
                    })()
                    """.trimIndent()
                )
                isWarmedUp = true
            } catch (_: Exception) {}
        }
    }

    fun formats(): List<ShowdownFormatInfo> = formatCache ?: synchronized(lock) {
        formatCache ?: gson.fromJson(
            evaluateJson(
                """
                (() => {
                    const {Dex} = require('./sim');
                    return Dex.formats.all().map(format => ({
                        id: format.id,
                        name: format.name,
                        generation: Number((format.mod + ' ' + format.id).match(/gen([1-9])/i)?.[1] || format.gen || 0),
                        gameType: format.gameType,
                        mod: format.mod,
                        team: format.team,
                        ruleset: format.ruleset || [],
                        banlist: format.banlist || [],
                        unbanlist: format.unbanlist || [],
                        restricted: format.restricted || [],
                        customRules: format.customRules || [],
                        teamLength: format.teamLength || null,
                        maxLevel: format.maxLevel || null,
                        forcedLevel: format.forcedLevel || null
                    }));
                })()
                """.trimIndent()
            ),
            Array<ShowdownFormatInfo>::class.java
        ).toList().also { formatCache = it }
    }

    fun format(formatId: String): ShowdownFormatInfo? = formats().firstOrNull { it.id.equals(formatId, true) }

    private fun ensureJsAllowedCache(
        implementedSpecies: Set<String>,
        implementedMoves: Set<String>,
        implementedAbilities: Set<String>,
        implementedItems: Set<String>
    ) {
        if (jsAllowedInitialized) return
        synchronized(lock) {
            if (jsAllowedInitialized) return
            val speciesJson = gson.toJson(implementedSpecies)
            val movesJson = gson.toJson(implementedMoves)
            val abilitiesJson = gson.toJson(implementedAbilities)
            val itemsJson = gson.toJson(implementedItems)
            evaluateJson(
                """
                (() => {
                    const {toID} = require('./sim');
                    globalThis.__d4AllowedSpecies = new Set($speciesJson.map(toID));
                    globalThis.__d4AllowedMoves = new Set($movesJson.map(toID));
                    globalThis.__d4AllowedAbilities = new Set($abilitiesJson.map(toID));
                    globalThis.__d4AllowedItems = new Set($itemsJson.map(toID));
                    return true;
                })()
                """.trimIndent()
            )
            jsAllowedInitialized = true
        }
    }

    /**
     * Genera equipos de Random Battle usando el generador nativo de Showdown con semillas de 4x16 bits reales
     * y sin validar objetos faltantes como bloqueo total (se filtran en Kotlin).
     */
    fun generateRandomBattleTeam(
        generation: Int,
        isDoubles: Boolean,
        teamSize: Int,
        implementedSpecies: Set<String>,
        implementedMoves: Set<String>,
        implementedAbilities: Set<String>,
        implementedItems: Set<String>,
        excludedSpecies: Set<String> = emptySet()
    ): List<ShowdownPokemonSet> {
        ensureJsAllowedCache(implementedSpecies, implementedMoves, implementedAbilities, implementedItems)

        val gen = if (generation in 1..9) generation else 9
        val excludedJson = gson.toJson(excludedSpecies)
        val rng = ThreadLocalRandom.current()
        val s1 = rng.nextInt(1, 65536)
        val s2 = rng.nextInt(1, 65536)
        val s3 = rng.nextInt(1, 65536)
        val s4 = rng.nextInt(1, 65536)

        val expression =
            """
            (() => {
                const {Dex, Teams, toID} = require('./sim');
                const allowedSpecies = globalThis.__d4AllowedSpecies;
                const allowedMoves = globalThis.__d4AllowedMoves;
                const excludedSpecies = new Set($excludedJson.map(toID));
                const gen = $gen;
                const isDoubles = $isDoubles;
                const targetSize = $teamSize;
                const primaryFormatId = isDoubles && Dex.formats.get('gen' + gen + 'randomdoublesbattle').exists
                    ? 'gen' + gen + 'randomdoublesbattle'
                    : (Dex.formats.get('gen' + gen + 'randombattle').exists ? 'gen' + gen + 'randombattle' : 'gen9randombattle');

                const normalizeSet = set => {
                    const species = Dex.species.get(set.species || set.name);
                    const validMoves = (set.moves || []).map(m => toID(m)).filter(m => allowedMoves.has(m));
                    return {
                        species: species.baseSpecies || species.name,
                        forme: species.forme || '',
                        gender: set.gender || null,
                        item: set.item ? Dex.items.get(set.item).id : '',
                        ability: set.ability ? Dex.abilities.get(set.ability).id : '',
                        nature: set.nature ? Dex.natures.get(set.nature).id : '',
                        moves: validMoves,
                        level: set.level || null,
                        shiny: !!set.shiny,
                        teraType: set.teraType || null,
                        gigantamax: set.gigantamax ?? null,
                        dynamaxLevel: set.dynamaxLevel ?? null,
                        evs: set.evs || {},
                        ivs: set.ivs || {}
                    };
                };

                const collected = [];
                const seenSpecies = new Set();

                for (let attempt = 0; attempt < 16 && collected.length < targetSize; attempt++) {
                    const seed = [
                        ($s1 + attempt * 7919) & 0xFFFF,
                        ($s2 + attempt * 6271) & 0xFFFF,
                        ($s3 + attempt * 4999) & 0xFFFF,
                        ($s4 + attempt * 3571) & 0xFFFF
                    ];
                    let generated;
                    try {
                        generated = Teams.generate(primaryFormatId, {seed});
                    } catch (_) {
                        try {
                            generated = Teams.generate('gen9randombattle', {seed});
                        } catch (__) {
                            continue;
                        }
                    }
                    if (!Array.isArray(generated)) continue;
                    for (const rawSet of generated) {
                        const sp = Dex.species.get(rawSet.species || rawSet.name);
                        const baseId = toID(sp.baseSpecies || sp.id);
                        if (!allowedSpecies.has(baseId) || seenSpecies.has(baseId) || excludedSpecies.has(baseId)) continue;
                        const norm = normalizeSet(rawSet);
                        if (norm.moves.length === 0) continue;
                        seenSpecies.add(baseId);
                        collected.push(norm);
                        if (collected.length >= targetSize) break;
                    }
                }
                return collected;
            })()
            """.trimIndent()

        return gson.fromJson(evaluateJson(expression), Array<ShowdownPokemonSet>::class.java).toList()
    }

    /**
     * Genera sets competitivos para una lista específica de especies ya elegidas en Kotlin (OU, Ubers, VGC, Monotype).
     * Instantáneo (<5 ms), sin bucles de validación.
     */
    fun generateSetsForSpeciesList(
        generation: Int,
        speciesIds: List<String>,
        isDoubles: Boolean,
        implementedSpecies: Set<String>,
        implementedMoves: Set<String>,
        implementedAbilities: Set<String>,
        implementedItems: Set<String>
    ): List<ShowdownPokemonSet> {
        if (speciesIds.isEmpty()) return emptyList()
        ensureJsAllowedCache(implementedSpecies, implementedMoves, implementedAbilities, implementedItems)

        val gen = if (generation in 1..9) generation else 9
        val speciesIdsJson = gson.toJson(speciesIds)
        val rng = ThreadLocalRandom.current()
        val s1 = rng.nextInt(1, 65536)
        val s2 = rng.nextInt(1, 65536)
        val s3 = rng.nextInt(1, 65536)
        val s4 = rng.nextInt(1, 65536)

        val expression =
            """
            (() => {
                const {Dex, Teams, toID} = require('./sim');
                const allowedMoves = globalThis.__d4AllowedMoves;
                const gen = $gen;
                const isDoubles = $isDoubles;
                const requestedIds = $speciesIdsJson;
                const randomFormat = 'gen' + gen + 'randombattle';
                const seed = [$s1, $s2, $s3, $s4];
                let generator = null;
                try { generator = Teams.getGenerator(randomFormat, seed); } catch (_) {}
                let fallbackGen = null;
                try { fallbackGen = Teams.getGenerator('gen9randombattle', [($s1 + 13) & 0xFFFF, ($s2 + 29) & 0xFFFF, ($s3 + 47) & 0xFFFF, ($s4 + 61) & 0xFFFF]); } catch (_) {}

                const results = [];
                for (let i = 0; i < requestedIds.length; i++) {
                    const spId = toID(requestedIds[i]);
                    const species = Dex.species.get(spId);
                    if (!species || !species.exists) continue;
                    let rawSet = null;
                    if (generator) {
                        try { rawSet = generator.randomSet(species, {}, i === 0, isDoubles); } catch (_) { rawSet = null; }
                    }
                    if (!rawSet && fallbackGen) {
                        try { rawSet = fallbackGen.randomSet(species, {}, i === 0, isDoubles); } catch (_) { rawSet = null; }
                    }
                    if (!rawSet) continue;
                    try {
                        const validMoves = (rawSet.moves || []).map(m => toID(m)).filter(m => allowedMoves.has(m));
                        results.push({
                            species: species.baseSpecies || species.name,
                            forme: species.forme || '',
                            gender: rawSet.gender || null,
                            item: rawSet.item ? Dex.items.get(rawSet.item).id : '',
                            ability: rawSet.ability ? Dex.abilities.get(rawSet.ability).id : '',
                            nature: rawSet.nature ? Dex.natures.get(rawSet.nature).id : '',
                            moves: validMoves,
                            level: rawSet.level || null,
                            shiny: !!rawSet.shiny,
                            teraType: rawSet.teraType || null,
                            gigantamax: rawSet.gigantamax ?? null,
                            dynamaxLevel: rawSet.dynamaxLevel ?? null,
                            evs: rawSet.evs || {},
                            ivs: rawSet.ivs || {}
                        });
                    } catch (_) {
                        // fallback handled in Kotlin
                    }
                }
                return results;
            })()
            """.trimIndent()

        return gson.fromJson(evaluateJson(expression), Array<ShowdownPokemonSet>::class.java).toList()
    }

    private fun evaluateJson(expression: String): String = synchronized(lock) {
        val service = ShowdownService.service
        val context = service.javaClass.methods
            .firstOrNull { it.name == "getContext" && it.parameterCount == 0 }
            ?.invoke(service)
            ?: throw IllegalArgumentException("El servicio GraalVM de Showdown no está disponible.")
        val eval = context.javaClass.methods.firstOrNull { method ->
            val parameters = method.parameterTypes
            method.name == "eval" && parameters.size == 2 &&
                parameters[0] == String::class.java && CharSequence::class.java.isAssignableFrom(parameters[1])
        } ?: throw IllegalArgumentException("No se pudo localizar la API de evaluación de Showdown.")

        val value = try {
            eval.invoke(context, "js", "JSON.stringify($expression)")
        } catch (exception: InvocationTargetException) {
            throw IllegalArgumentException(exception.targetException.message ?: "Error ejecutando Showdown.", exception.targetException)
        }
        value.javaClass.getMethod("asString").invoke(value) as String
    }
}
