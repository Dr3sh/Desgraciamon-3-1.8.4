package com.desgraciamon.vortex.server

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokeball.ThrownPokeballHitEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import com.cobblemon.mod.common.api.events.pokemon.RidePokemonEvent
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.riding.RidingStyle
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.detail.PokemonHerdSpawnDetail
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.IVs
import com.cobblemon.mod.common.pokemon.OriginalTrainerType
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.desgraciamon.vortex.config.VortexConfig
import com.desgraciamon.vortex.economy.CobbleDollarsIntegration
import com.desgraciamon.vortex.network.OpenRewardScreenPacket
import com.desgraciamon.vortex.network.VortexSyncPacket
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.BossEvent
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object VortexManager {
    private val LOGGER = LoggerFactory.getLogger("Desgraciamon-Vortex")

    const val VORTEX_TAG = "vortex_entity"
    const val VORTEX_LIMBO_NBT = "vortex_limbo"

    @Volatile
    var isVortexActive: Boolean = false
        private set

    @Volatile
    var isNoFlyActive: Boolean = false
        private set

    var vortexCenter: BlockPos = BlockPos.ZERO
        private set

    var remainingTicks: Int = 0
        private set

    var totalDurationSeconds: Int = 600
        private set

    var activeAnomalyEntityUUID: UUID? = null
        private set

    var activeAnomalySpeciesName: String = ""
        private set

    var currentEffectType: String = "startrails"
        private set

    var isAutoLegendaryActive: Boolean = false
        private set

    var trackedWildLegendaryUUID: UUID? = null
        private set

    private var forcedChunkPos: net.minecraft.world.level.ChunkPos? = null
    private var forcedChunkLevel: ServerLevel? = null

    var currentServer: MinecraftServer? = null
        private set

    fun isMirrorEntity(entity: net.minecraft.world.entity.Entity): Boolean {
        val dimLocation = entity.level().dimension().location().toString()
        if (dimLocation.contains("mirror") || dimLocation.contains("memory")) return true
        if (entity.tags.any { it.contains("mirror") || it.contains("memory") }) return true
        return false
    }

    /**
     * Módulo 5.1: Limbo cache mapping winning player's UUID to their captured Anomaly Pokemon.
     */
    val limbo: MutableMap<UUID, Pokemon> = ConcurrentHashMap()

    enum class VortexEventType {
        NORMAL,     // vortex, startrails, equilibrio (muestra bossbar, HUD overlay con coords y temporizador, coords en chat)
        LEGENDARY   // dialga, palkia, giratina (solo atmósfera en el cielo, sin bossbar, sin HUD overlay, sin coords)
    }

    fun getEventType(effectType: String = currentEffectType): VortexEventType {
        return when (effectType.lowercase(Locale.ROOT)) {
            "dialga", "palkia", "giratina", "yveltal", "xerneas", "rayquaza", "kyogre", "groudon", "zygarde", "rayquaza2", "kyogre2", "groudon2", "solgaleo", "lunala", "palkiaold", "rayquazaold", "xerneasold", "yveltalold" -> VortexEventType.LEGENDARY
            else -> VortexEventType.NORMAL
        }
    }

    fun isLegendaryEvent(effectType: String = currentEffectType): Boolean {
        return getEventType(effectType) == VortexEventType.LEGENDARY
    }

    private val bossBar = ServerBossEvent(
        com.desgraciamon.visuals.TextCatalog.component("vortex.bossbar.title"),
        BossEvent.BossBarColor.PURPLE,
        BossEvent.BossBarOverlay.PROGRESS
    )

    fun registerEvents() {
        // Módulo 3.3: Restricción Cobblemon - Cancelar montura en el aire o de Pokémon voladores si No-Fly está activo
        CobblemonEvents.RIDE_EVENT_PRE.subscribe(Priority.HIGHEST) { event: RidePokemonEvent.Pre ->
            if (isVortexActive && isNoFlyActive) {
                val pokemonEntity = event.pokemon
                val canFly = pokemonEntity.pokemon.riding.behaviours?.containsKey(RidingStyle.AIR) == true
                val isAirborne = !pokemonEntity.onGround() || pokemonEntity.isFlying()
                if (canFly || isAirborne || !event.player.onGround()) {
                    event.cancel()
                    applySlowFallingSafeguard(event.player)
                    event.player.displayClientMessage(
                        com.desgraciamon.visuals.TextCatalog.component("vortex.nofly.ride"),
                        true
                    )
                }
            }
        }

        // Módulo 4.3: Supresión de robo de captura si otro jugador está combatiendo la anomalía
        CobblemonEvents.THROWN_POKEBALL_HIT.subscribe(Priority.HIGHEST) { event: ThrownPokeballHitEvent ->
            val targetPokemon = event.pokemon
            if (isAnomalyEntity(targetPokemon)) {
                val thrower = event.pokeBall.owner as? ServerPlayer
                if (targetPokemon.isBattling || targetPokemon.battleId != null) {
                    val battle = targetPokemon.battleId?.let { com.cobblemon.mod.common.battles.BattleRegistry.getBattle(it) }
                    if (battle != null && (thrower == null || battle.getActor(thrower.uuid) == null)) {
                        event.cancel()
                        thrower?.sendSystemMessage(
                            com.desgraciamon.visuals.TextCatalog.component("vortex.anomaly.steal_blocked")
                        )
                    }
                }
            }
        }

        // Desactivado: Los legendarios ya no activan efecto de cielo al aparecer en el mundo
        /*
        CobblemonEvents.ENTITY_SPAWN.subscribe { event ->
            val entity = event.entity as? PokemonEntity ?: return@subscribe
            if (!entity.pokemon.isWild()) return@subscribe
            if (isMirrorEntity(entity)) return@subscribe
            val species = entity.pokemon.species.name.lowercase(Locale.ROOT)
            if (species in listOf("dialga", "palkia", "giratina", "yveltal", "xerneas", "rayquaza", "kyogre", "groudon", "zygarde", "solgaleo", "lunala")) {
                val server = currentServer ?: entity.server ?: return@subscribe
                server.execute {
                    startWildLegendaryEffect(server, entity)
                }
            }
        }
        */

        // Módulo 5.1: Secuestro del Pokémon al completarse la captura o fin de evento legendario
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.HIGHEST) { event: PokemonCapturedEvent ->
            if (isAutoLegendaryActive) {
                val specName = event.pokemon.species.name.lowercase(Locale.ROOT)
                if (specName in listOf("dialga", "palkia", "giratina", "yveltal", "xerneas", "rayquaza", "kyogre", "groudon", "zygarde", "solgaleo", "lunala")) {
                    val winner = event.player
                    val server = winner.server
                    val legTitle = when (specName) {
                        "dialga" -> "§9§lDialga"
                        "palkia" -> "§d§lPalkia"
                        "yveltal" -> "§4§lYveltal"
                        "xerneas" -> "§d§lXerneas"
                        "rayquaza" -> "§a§lRayquaza"
                        "kyogre" -> "§9§lKyogre"
                        "groudon" -> "§6§lGroudon"
                        "zygarde" -> "§2§lZygarde"
                        "solgaleo" -> "§6§lSolgaleo"
                        "lunala" -> "§d§lLunala"
                        else -> "§4§lGiratina"
                    }
                    stopVortex(server, Component.literal("§a§l${winner.scoreboardName} §fha capturado a $legTitle§f! El firmamento vuelve a la calma."))
                    isAutoLegendaryActive = false
                    trackedWildLegendaryUUID = null
                }
            }

            val pokemon = event.pokemon
            if (pokemon.persistentData.getBoolean(VORTEX_TAG) || pokemon.persistentData.getBoolean(VORTEX_LIMBO_NBT)) {
                val winner = event.player
                // Guardar en el Limbo vinculado al UUID del jugador
                limbo[winner.uuid] = pokemon
                activeAnomalyEntityUUID = null

                val config = VortexConfig.INSTANCE
                val displayName = pokemon.species.translatedName.string

                // Enviar paquete para abrir la GUI de Decisión (Reclamar vs Vender al Mercado Negro)
                ServerPlayNetworking.send(
                    winner,
                    OpenRewardScreenPacket(
                        pokemonName = displayName,
                        speciesIdentifier = pokemon.species.resourceIdentifier.toString(),
                        level = pokemon.level,
                        rewardAmount = config.blackMarketReward
                    )
                )

                LOGGER.info(
                    "Player {} captured Vortex Anomaly ({}) - moved to Limbo awaiting decision.",
                    winner.scoreboardName,
                    displayName
                )
            }
        }
    }

    /**
     * Inicia automaticamente el efecto de cielo y atmosfera cuando un Legendario Mayor salvaje aparece.
     * Desactivado por solicitud: los legendarios ya no activan efectos de cielo.
     */
    fun startWildLegendaryEffect(server: MinecraftServer, entity: PokemonEntity) {
        // Desactivado: ningun legendario salvaje alterara el cielo
        return
    }

    fun findTrackedEntity(server: MinecraftServer, uuid: UUID?): PokemonEntity? {
        if (uuid == null) return null
        for (level in server.allLevels) {
            val ent = level.getEntity(uuid)
            if (ent is PokemonEntity) return ent
        }
        return null
    }

    /**
     * Módulo 1.1 & 1.2: Comando y Triangulación Algorítmica
     */
    fun startVortex(server: MinecraftServer, noFly: Boolean, effectType: String = "startrails"): BlockPos {
        val config = VortexConfig.load()
        val overworld: ServerLevel = server.overworld()

        // Limpiar vórtice previo si existiera
        if (isVortexActive) {
            stopVortex(server, broadcastMessage = null)
        }

        isAutoLegendaryActive = false
        trackedWildLegendaryUUID = null
        currentEffectType = effectType.lowercase(Locale.ROOT)
        val isLegendary = isLegendaryEvent(currentEffectType)

        // 1.2. Triangulación Algorítmica: Promedio X/Z de todos los jugadores en el Overworld
        val overworldPlayers = overworld.players().filter { !it.isSpectator }
        val avgX: Int
        val avgZ: Int
        if (overworldPlayers.isNotEmpty()) {
            avgX = overworldPlayers.map { it.x }.average().roundToInt()
            avgZ = overworldPlayers.map { it.z }.average().roundToInt()
        } else {
            val spawnPos = overworld.sharedSpawnPos
            avgX = spawnPos.x
            avgZ = spawnPos.z
        }

        // Asegurar que el chunk esté cargado antes de calcular getHeightmapPos (getTopPosition)
        overworld.getChunk(avgX shr 4, avgZ shr 4)
        val topPos = findSafeTopPosition(overworld, avgX, avgZ)

        isVortexActive = true
        isNoFlyActive = noFly
        vortexCenter = topPos
        totalDurationSeconds = if (isLegendary) 7200 else config.durationSeconds.coerceAtLeast(10)
        remainingTicks = totalDurationSeconds * 20

        // 4.1 & 4.2: Descenso de la Anomalía solo para eventos normales
        if (!isLegendary) {
            val spawnedEntity = spawnAnomalyPokemon(overworld, topPos, config)
            activeAnomalyEntityUUID = spawnedEntity?.uuid
            activeAnomalySpeciesName = spawnedEntity?.pokemon?.species?.translatedName?.string ?: "Anomalía"
        } else {
            activeAnomalyEntityUUID = null
            activeAnomalySpeciesName = ""
        }

        // Actualizar BossBar (solo activo para eventos normales)
        updateBossBar(server)

        // 1.3. Networking (S2C): Enviar VortexSyncPacket a todos los clientes conectados
        syncToAllPlayers(server)

        // Sonido global y anuncio en el servidor
        val flyTag = if (noFly) " §d[Campo No-Fly Activo]" else ""
        val (tagHeader, soundEvent) = when (currentEffectType) {
            "vortex" -> Pair("§5§l[VÓRTICE DIMENSIONAL] §d¡Una grieta dimensional se ha abierto", SoundEvents.END_PORTAL_SPAWN)
            "equilibrio" -> Pair("§f§l[BESTIA EQUILIBRIO] §f¡La gracia divina ha descendido sobre los cielos", SoundEvents.BEACON_ACTIVATE)
            "dialga" -> Pair("§9§l[DIALGA - TIEMPO] §b¡El flujo temporal se ha distorsionado! Un poder cósmico", SoundEvents.AMETHYST_BLOCK_CHIME)
            "palkia" -> Pair("§d§l[PALKIA - ESPACIO] §d¡El tejido del espacio ha sido rasgado! Un poder cósmico", SoundEvents.END_PORTAL_SPAWN)
            "giratina" -> Pair("§4§l[GIRATINA - DISTORSIÓN] §c¡El velo del Mundo Distorsión ha caído! La sombra del vacío", SoundEvents.WITHER_SPAWN)
            "yveltal" -> Pair("§4§l[YVELTAL - DESTRUCCIÓN] §c¡El heraldo de la muerte ha despertado! Un eclipse abisal", SoundEvents.WARDEN_ROAR)
            "xerneas" -> Pair("§d§l[XERNEAS - VIDA ETERNA] §b¡El ciervo de la creación ha despertado! La cornamenta celestial", SoundEvents.AMETHYST_BLOCK_CHIME)
            "rayquaza" -> Pair("§a§l[RAYQUAZA - VÓRTICE PRIMAL] §a¡El dragón del ozono ha rasgado los cielos! Una tempestad estratosférica", SoundEvents.ENDER_DRAGON_GROWL)
            "kyogre" -> Pair("§9§l[KYOGRE - DILUVIO PRIMAL] §b¡Las mareas abisales han inundado los cielos! Un tifón oceánico", SoundEvents.LIGHTNING_BOLT_THUNDER)
            "groudon" -> Pair("§6§l[GROUDON - FURIA VOLCÁNICA] §c¡La tierra se ha quebrado bajo un calor abrasador! Una tempestad volcánica", SoundEvents.GENERIC_EXPLODE.value())
            "rayquaza2" -> Pair("§a§l[RAYQUAZA II - SKY-HIGH MAELSTROM] §a¡El ojo del huracán polar desgarra la estratosfera!", SoundEvents.ENDER_DRAGON_GROWL)
            "kyogre2" -> Pair("§9§l[KYOGRE II - ABYSSAL DELUGE] §b¡Una supercélula abisal descarga un diluvio torrencial!", SoundEvents.LIGHTNING_BOLT_THUNDER)
            "groudon2" -> Pair("§6§l[GROUDON II - CATACLYSMIC DROUGHT] §c¡El sol apocalíptico incinera la tierra en cenizas!", SoundEvents.GENERIC_EXPLODE.value())
            "zygarde" -> Pair("§2§l[ZYGARDE - EQUILIBRIO ECOLÓGICO] §a¡El guardián del orden ha despertado! Las células del equilibrio", SoundEvents.WARDEN_SONIC_BOOM)
            "solgaleo" -> Pair("§6§l[SOLGALEO - THE SOLAR CORONA RUPTURE] §e¡La ruptura de la corona solar ha calcinado el cenit!", SoundEvents.ENDER_DRAGON_GROWL)
            "lunala" -> Pair("§d§l[LUNALA - THE FULL MOON ECLIPSE] §d¡El eclipse de luna llena roja y el vacío estelar han oscurecido la realidad!", SoundEvents.WARDEN_SONIC_BOOM)
            "goosebumps", "travis" -> Pair("§d§l[GOOSEBUMPS - TRAVIS SCOTT] §b¡El mundo colapsa en la dimensión psicodélica! La realidad", SoundEvents.WARDEN_SONIC_BOOM)
            else -> Pair("§9§l[STAR TRAILS] §b¡Un vórtice estelar ha cubierto los cielos", SoundEvents.AMETHYST_BLOCK_CHIME)
        }

        val announcement = if (isLegendary) {
            Component.literal(
                "$tagHeader se ha manifestado sobre el firmamento!$flyTag\n" +
                    "§7¡Mira al cielo para contemplar la manifestación de su poder cósmico!"
            )
        } else {
            Component.literal(
                "$tagHeader en §fX: ${topPos.x}, Y: ${topPos.y}, Z: ${topPos.z}§r!$flyTag\n" +
                    "§7Una anomalía genética Shiny (IVs 6x31) ha descendido. Tiempo límite: §e${formatTime(totalDurationSeconds)}"
            )
        }
        server.playerList.broadcastSystemMessage(announcement, false)

        for (player in server.playerList.players) {
            player.playNotifySound(soundEvent, SoundSource.AMBIENT, 0.8f, 0.75f)
            if (noFly) {
                enforceNoFlyOnPlayer(player)
            }
        }

        return topPos
    }

    /**
     * Encuentra el bloque seguro más cercano usando world.getHeightmapPos (getTopPosition en Mojmap).
     */
    private fun findSafeTopPosition(level: ServerLevel, centerX: Int, centerZ: Int): BlockPos {
        val initialCandidate = level.getHeightmapPos(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            BlockPos(centerX, 0, centerZ)
        )
        if (isPositionSafe(level, initialCandidate)) {
            return initialCandidate
        }

        // Buscar en espiral alrededor del centro el bloque seguro más cercano
        for (radius in 1..16) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (kotlin.math.abs(dx) != radius && kotlin.math.abs(dz) != radius) continue
                    val candidate = level.getHeightmapPos(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        BlockPos(centerX + dx, 0, centerZ + dz)
                    )
                    if (isPositionSafe(level, candidate)) {
                        return candidate
                    }
                }
            }
        }
        return initialCandidate.atY(initialCandidate.y.coerceAtLeast(level.minBuildHeight + 2))
    }

    private fun isPositionSafe(level: ServerLevel, pos: BlockPos): Boolean {
        if (pos.y <= level.minBuildHeight + 1 || pos.y >= level.maxBuildHeight - 2) return false
        val belowState = level.getBlockState(pos.below())
        val feetState = level.getBlockState(pos)
        val headState = level.getBlockState(pos.above())
        if (!belowState.fluidState.isEmpty || !feetState.fluidState.isEmpty) return false
        return !belowState.isAir && feetState.isAir && headState.isAir
    }

    /**
     * Módulo 4.1, 4.2 & 4.3: Generar el Pokémon exclusivo ("Rare" o "Ultra Rare", sin "Legendary/Mythical"),
     * IVs perfectos (31 en todo), Shiny = true, Tag `vortex_entity` y silenciar cue de shiny.
     */
    private fun spawnAnomalyPokemon(level: ServerLevel, pos: BlockPos, config: VortexConfig): PokemonEntity? {
        val chosenSpecies = pickRareNonLegendarySpecies(config)
        val pokemon = chosenSpecies.create(config.anomalyLevel.coerceIn(1, 100)).apply {
            shiny = true
            for (stat in Stats.PERMANENT) {
                setIV(stat, IVs.MAX_VALUE) // 31 IV en HP, ATTACK, DEFENCE, SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED
            }
            persistentData.putBoolean(VORTEX_TAG, true)
            persistentData.putBoolean(VORTEX_LIMBO_NBT, true)
            initialize()
        }

        val entity = PokemonEntity(level, pokemon).apply {
            addTag(VORTEX_TAG)
            // Módulo 4.3: Suprimir efecto de sonido/partícula shiny de Cobblemon
            isSilent = true
            isPersistenceRequired()
            moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, level.random.nextFloat() * 360f, 0f)
            entityData.set(PokemonEntity.SPAWN_DIRECTION, level.random.nextFloat() * 360f)
            finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null)
        }

        level.addFreshEntity(entity)
        LOGGER.info(
            "Spawned Vortex Anomaly: {} (Shiny=true, IVs=6x31) at {}, {}, {}",
            chosenSpecies.resourceIdentifier,
            pos.x,
            pos.y,
            pos.z
        )
        return entity
    }

    private fun pickRareNonLegendarySpecies(config: VortexConfig): Species {
        val targetBuckets = config.allowedBuckets.map { it.lowercase(Locale.ROOT) }.toSet()
        val excludedLabels = (config.excludedLabels.map { it.lowercase(Locale.ROOT) } + listOf(
            CobblemonPokemonLabels.LEGENDARY,
            CobblemonPokemonLabels.MYTHICAL,
            CobblemonPokemonLabels.ULTRA_BEAST,
            CobblemonPokemonLabels.PARADOX
        )).toSet()

        val poolSpecies = LinkedHashSet<Species>()
        try {
            for (detail in CobblemonSpawnPools.WORLD_SPAWN_POOL.details) {
                if (detail.bucket.lowercase(Locale.ROOT) !in targetBuckets) continue

                val speciesNames = ArrayList<String>()
                when (detail) {
                    is PokemonSpawnDetail -> detail.pokemon.species?.let { speciesNames.add(it) }
                    is PokemonHerdSpawnDetail -> {
                        for (herdable in detail.herdablePokemon) {
                            herdable.pokemon.species?.let { speciesNames.add(it) }
                        }
                    }
                }

                for (speciesStr in speciesNames) {
                    val species = PokemonSpecies.getByName(speciesStr)
                        ?: try {
                            PokemonSpecies.getByIdentifier(ResourceLocation.parse(speciesStr))
                        } catch (_: Exception) {
                            null
                        }
                    if (species != null && species.implemented) {
                        var hasExcludedLabel = false
                        for (label in species.labels) {
                            if (label.lowercase(Locale.ROOT) in excludedLabels) {
                                hasExcludedLabel = true
                                break
                            }
                        }
                        if (!hasExcludedLabel) {
                            poolSpecies.add(species)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            LOGGER.warn("Could not query WORLD_SPAWN_POOL buckets, falling back to implemented species filter", t)
        }

        if (poolSpecies.isNotEmpty()) {
            return poolSpecies.random()
        }

        val fallbackList = PokemonSpecies.implemented.filter { species ->
            species.labels.none { it.lowercase(Locale.ROOT) in excludedLabels }
        }
        return if (fallbackList.isNotEmpty()) fallbackList.random() else PokemonSpecies.random()
    }

    /**
     * Tick del servidor: Cuenta atrás (10 min configurable), chequeo No-Fly y sincronización periódica.
     */
    fun onServerTick(server: MinecraftServer) {
        currentServer = server

        if (!isVortexActive) {
            return
        }

        // Si es un evento automático ligado a un legendario salvaje vivo en el mundo
        if (isAutoLegendaryActive && remainingTicks % 20 == 0) {
            val tracked = findTrackedEntity(server, trackedWildLegendaryUUID)
            if (tracked == null || !tracked.isAlive || tracked.isRemoved || !tracked.pokemon.isWild()) {
                val name = when (currentEffectType) {
                    "dialga" -> "Dialga"
                    "palkia" -> "Palkia"
                    "yveltal" -> "Yveltal"
                    "xerneas" -> "Xerneas"
                    "rayquaza" -> "Rayquaza"
                    "kyogre" -> "Kyogre"
                    "groudon" -> "Groudon"
                    "zygarde" -> "Zygarde"
                    "solgaleo" -> "Solgaleo"
                    "lunala" -> "Lunala"
                    else -> "Giratina"
                }
                stopVortex(server, Component.literal("§7El legendario salvaje §e$name §7ha desaparecido o sido capturado. El firmamento vuelve a la calma."))
                isAutoLegendaryActive = false
                trackedWildLegendaryUUID = null
                return
            } else {
                tracked.setPersistenceRequired()
                val currentCp = tracked.chunkPosition()
                val currentLevel = tracked.level() as? ServerLevel
                if (currentLevel != null && (forcedChunkPos != currentCp || forcedChunkLevel != currentLevel)) {
                    forcedChunkLevel?.setChunkForced(forcedChunkPos!!.x, forcedChunkPos!!.z, false)
                    currentLevel.setChunkForced(currentCp.x, currentCp.z, true)
                    forcedChunkPos = currentCp
                    forcedChunkLevel = currentLevel
                }
                vortexCenter = tracked.blockPosition()
            }
        }

        // Módulo 3: Forzar desmontaje de jugadores en el aire si No-Fly está activo
        if (isNoFlyActive) {
            for (player in server.playerList.players) {
                enforceNoFlyOnPlayer(player)
            }
        }

        // Relámpagos atmosféricos para Giratina y Yveltal: visuales y sonoros (setVisualOnly=true), al menos a 20 bloques de los jugadores
        if ((currentEffectType == "giratina" || currentEffectType == "yveltal") && server.tickCount % 45 == 0) {
            triggerGiratinaAtmosphericLightning(server)
        }

        remainingTicks--

        // Cada segundo (20 ticks), actualizar BossBar y sincronizar clientes
        if (remainingTicks % 20 == 0) {
            updateBossBar(server)
            // Re-sincronizar cada 5 segundos para mantener el reloj mm:ss exacto en los clientes
            if (remainingTicks % 100 == 0) {
                syncToAllPlayers(server)
            }
        }

        // Si el contador llega a 0, el evento termina y desaparece el shiny
        if (remainingTicks <= 0) {
            val title = when (currentEffectType) {
                "vortex" -> "§5§l[VÓRTICE DIMENSIONAL]"
                "equilibrio" -> "§f§l[BESTIA EQUILIBRIO]"
                "dialga" -> "§9§l[DIALGA - TIEMPO]"
                "palkia" -> "§d§l[PALKIA - ESPACIO]"
                "giratina" -> "§4§l[GIRATINA - DISTORSIÓN]"
                "yveltal" -> "§4§l[YVELTAL - DESTRUCCIÓN]"
                "xerneas" -> "§d§l[XERNEAS - VIDA ETERNA]"
                "rayquaza" -> "§a§l[RAYQUAZA - VÓRTICE PRIMAL]"
                "kyogre" -> "§9§l[KYOGRE - DILUVIO PRIMAL]"
                "groudon" -> "§6§l[GROUDON - FURIA VOLCÁNICA]"
                "rayquaza2" -> "§a§l[RAYQUAZA II - SKY-HIGH MAELSTROM]"
                "kyogre2" -> "§9§l[KYOGRE II - ABYSSAL DELUGE]"
                "groudon2" -> "§6§l[GROUDON II - CATACLYSMIC DROUGHT]"
                "zygarde" -> "§2§l[ZYGARDE - EQUILIBRIO ECOLÓGICO]"
                "solgaleo" -> "§6§l[SOLGALEO - THE SOLAR CORONA RUPTURE]"
                "lunala" -> "§d§l[LUNALA - THE FULL MOON ECLIPSE]"
                else -> "§9§l[STAR TRAILS]"
            }
            stopVortex(
                server,
                broadcastMessage = Component.literal(
                    "$title §c¡El tiempo se ha agotado! El evento ha finalizado y el cielo vuelve a la normalidad."
                )
            )
        }
    }

    /**
     * Módulo 3.2, 3.3 & 3.4: Restricción de vuelo (Elytras, vuelo creativo/habilidad y monturas Cobblemon) + Salvavidas Slow Falling
     */
    fun enforceNoFlyOnPlayer(player: ServerPlayer) {
        if (!isVortexActive || !isNoFlyActive) return
        if (player.isCreative || player.isSpectator) return

        var dismountedOrStoppedFlying = false

        // 3.2 Restricción Vanilla: Elytras (isFallFlying)
        if (player.isFallFlying) {
            player.stopFallFlying()
            dismountedOrStoppedFlying = true
        }

        // Si algún otro mod activó vuelo de habilidad en survival
        if (player.abilities.flying) {
            player.abilities.flying = false
            player.onUpdateAbilities()
            dismountedOrStoppedFlying = true
        }

        // 3.3 Restricción Cobblemon: Desmontar si está sobre un PokemonEntity en el aire o con estilo AIR
        val vehicle = player.vehicle
        if (vehicle is PokemonEntity) {
            val ridingAirStyle = vehicle.ridingController?.context?.style == RidingStyle.AIR
            val canMountFly = vehicle.pokemon.riding.behaviours?.containsKey(RidingStyle.AIR) == true
            val isAirborne = !vehicle.onGround() || vehicle.isFlying()
            if (ridingAirStyle || isAirborne || canMountFly) {
                player.stopRiding()
                dismountedOrStoppedFlying = true
            }
        }

        // 3.4 Salvavidas: Aplicar SLOW_FALLING por 5 segundos al forzar el desmontaje en el aire
        if (dismountedOrStoppedFlying && !player.onGround()) {
            applySlowFallingSafeguard(player)
            player.displayClientMessage(
                com.desgraciamon.visuals.TextCatalog.component("vortex.nofly.dismount"),
                true
            )
        }
    }

    fun applySlowFallingSafeguard(player: ServerPlayer) {
        val seconds = VortexConfig.INSTANCE.slowFallingDurationSeconds.coerceAtLeast(1)
        player.addEffect(
            MobEffectInstance(
                MobEffects.SLOW_FALLING,
                seconds * 20,
                0,
                false,
                true,
                true
            )
        )
    }

    /**
     * Módulo 5.3 & 6.1: Resolución de la decisión del jugador ganador (Reclamar vs Vender al Mercado Negro)
     */
    fun handleRewardDecision(player: ServerPlayer, claimPokemon: Boolean) {
        val capturedPokemon = limbo.remove(player.uuid)
        if (capturedPokemon == null) {
            player.sendSystemMessage(
                com.desgraciamon.visuals.TextCatalog.component("vortex.limbo.empty")
            )
            return
        }

        val server = player.server
        val config = VortexConfig.INSTANCE
        val pokemonName = capturedPokemon.species.translatedName.string

        // Limpiar las marcas de limbo para que funcione como un Pokémon legítimo
        capturedPokemon.persistentData.remove(VORTEX_LIMBO_NBT)

        if (claimPokemon) {
            // Reclamar: Saca al Pokémon del Limbo y lo inyecta directamente al PC del jugador
            if (capturedPokemon.originalTrainerType == OriginalTrainerType.NONE) {
                capturedPokemon.setOriginalTrainer(player.uuid)
            }
            capturedPokemon.refreshOriginalTrainer()

            val pc = Cobblemon.storage.getPC(player)
            var added = pc.add(capturedPokemon)
            if (!added) {
                pc.relocateEvictedBoxPokemon(capturedPokemon)
                added = true
            }

            player.sendSystemMessage(
                com.desgraciamon.visuals.TextCatalog.component("vortex.limbo.claimed", "pokemon" to pokemonName)
            )

            // 6.1 Broadcast de Victoria
            val title = when (currentEffectType) {
                "vortex" -> "§5§l[VÓRTICE DIMENSIONAL]"
                "equilibrio" -> "§f§l[BESTIA EQUILIBRIO]"
                "dialga" -> "§9§l[DIALGA - TIEMPO]"
                "palkia" -> "§d§l[PALKIA - ESPACIO]"
                "giratina" -> "§4§l[GIRATINA - DISTORSIÓN]"
                else -> "§9§l[STAR TRAILS]"
            }
            val broadcast = Component.literal(
                "$title §d§l${player.scoreboardName} §fha reclamado la anomalía §e§l$pokemonName Shiny (6x31 IVs)§f y el fenómeno ha concluido!"
            )
            stopVortex(server, broadcast)
        } else {
            // Vender al Mercado Negro: Destruir la instancia y depositar los $150,000 en CobbleDollars
            val reward = config.blackMarketReward
            val formattedReward = NumberFormat.getIntegerInstance(Locale.US).format(reward)
            CobbleDollarsIntegration.depositCobbleDollars(player, reward)

            player.sendSystemMessage(
                com.desgraciamon.visuals.TextCatalog.component("vortex.limbo.sold", "pokemon" to pokemonName, "reward" to formattedReward)
            )

            // 6.1 Broadcast de Victoria
            val title = when (currentEffectType) {
                "vortex" -> "§5§l[VÓRTICE DIMENSIONAL]"
                "equilibrio" -> "§f§l[BESTIA EQUILIBRIO]"
                "dialga" -> "§9§l[DIALGA - TIEMPO]"
                "palkia" -> "§d§l[PALKIA - ESPACIO]"
                "giratina" -> "§4§l[GIRATINA - DISTORSIÓN]"
                else -> "§9§l[STAR TRAILS]"
            }
            val broadcast = Component.literal(
                "$title §d§l${player.scoreboardName} §fha vendido la anomalía §e§l$pokemonName §fal Mercado Negro por §a§l$$formattedReward§f!"
            )
            stopVortex(server, broadcast)
        }
    }

    /**
     * Módulo 6.2 & 6.3: Disipación y Cleanup Total (despawn del shiny si sigue vivo, cierre del shader, limpieza de BossBar y No-Fly).
     */
    fun clearForcedChunk() {
        if (forcedChunkLevel != null && forcedChunkPos != null) {
            runCatching { forcedChunkLevel?.setChunkForced(forcedChunkPos!!.x, forcedChunkPos!!.z, false) }
            forcedChunkPos = null
            forcedChunkLevel = null
        }
    }

    fun stopVortex(server: MinecraftServer, broadcastMessage: Component?, force: Boolean = false) {
        isVortexActive = false
        isNoFlyActive = false
        isAutoLegendaryActive = false
        trackedWildLegendaryUUID = null
        remainingTicks = 0

        if (forcedChunkPos != null && forcedChunkLevel != null) {
            try {
                forcedChunkLevel?.setChunkForced(forcedChunkPos!!.x, forcedChunkPos!!.z, false)
            } catch (ignored: Exception) {}
            forcedChunkPos = null
            forcedChunkLevel = null
        }

        // Despawnear la entidad shiny si aún sigue en el mundo sin capturar
        despawnActiveAnomaly(server)

        // Limpiar BossBar
        bossBar.removeAllPlayers()
        bossBar.isVisible = false

        // Mandar VortexSyncPacket (inactivo) a todos los jugadores
        val fade = if (force) 0.0f else VortexConfig.INSTANCE.shaderFadeSeconds
        for (player in server.playerList.players) {
            val packet = VortexSyncPacket(
                isActive = false,
                center = vortexCenter,
                noFly = false,
                remainingSeconds = 0,
                totalSeconds = totalDurationSeconds,
                fadeSeconds = fade,
                effectType = currentEffectType,
                cameraShake = 0.0f
            )
            ServerPlayNetworking.send(player, packet)
        }

        if (broadcastMessage != null) {
            server.playerList.broadcastSystemMessage(broadcastMessage, false)
        }
    }

    private fun despawnActiveAnomaly(server: MinecraftServer) {
        val targetUUID = activeAnomalyEntityUUID
        activeAnomalyEntityUUID = null

        for (level in server.allLevels) {
            if (targetUUID != null) {
                val entity = level.getEntity(targetUUID)
                if (entity != null && entity.tags.contains(VORTEX_TAG)) {
                    if (entity is PokemonEntity && entity.battleId != null) {
                        com.cobblemon.mod.common.battles.BattleRegistry.getBattle(entity.battleId!!)?.end()
                    }
                    entity.discard()
                }
            }
            // También descartar cualquier otra entidad que tenga el tag vortex_entity cargada
            for (entity in level.allEntities.toList()) {
                if (entity != null && entity.tags.contains(VORTEX_TAG)) {
                    if (entity is PokemonEntity && entity.battleId != null) {
                        com.cobblemon.mod.common.battles.BattleRegistry.getBattle(entity.battleId!!)?.end()
                    }
                    entity.discard()
                }
            }
        }
    }

    private fun updateBossBar(server: MinecraftServer) {
        if (!isVortexActive || isLegendaryEvent(currentEffectType)) {
            bossBar.removeAllPlayers()
            bossBar.isVisible = false
            return
        }
        bossBar.isVisible = true

        val remSec = (remainingTicks / 20).coerceAtLeast(0)
        val progress = (remSec.toFloat() / totalDurationSeconds.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        bossBar.progress = progress
        val noFlyLabel = if (isNoFlyActive) " §c[NO-FLY]" else ""
        val (titlePrefix, barColor) = when (currentEffectType) {
            "vortex" -> Pair("§5§lVórtice Dimensional", BossEvent.BossBarColor.PURPLE)
            "equilibrio" -> Pair("§f§lBestia Equilibrio", BossEvent.BossBarColor.WHITE)
            else -> Pair("§9§lStar Trails", BossEvent.BossBarColor.BLUE)
        }
        bossBar.color = barColor
        bossBar.name = Component.literal(
            "$titlePrefix §7| §fX:${vortexCenter.x} Y:${vortexCenter.y} Z:${vortexCenter.z}$noFlyLabel §7| §e${formatTime(remSec)}"
        )

        val currentPlayers = server.playerList.players.toSet()
        for (p in currentPlayers) {
            if (!bossBar.players.contains(p)) {
                bossBar.addPlayer(p)
            }
        }
        for (p in bossBar.players.toList()) {
            if (!currentPlayers.contains(p)) {
                bossBar.removePlayer(p)
            }
        }
    }

    fun syncToPlayer(player: ServerPlayer) {
        val remSec = (remainingTicks / 20).coerceAtLeast(0)
        val shake = if (currentEffectType.lowercase(Locale.ROOT) == "palkia") VortexConfig.INSTANCE.cameraShakeIntensity else 0.0f
        val packet = VortexSyncPacket(
            isActive = isVortexActive,
            center = vortexCenter,
            noFly = isNoFlyActive,
            remainingSeconds = remSec,
            totalSeconds = totalDurationSeconds,
            fadeSeconds = VortexConfig.INSTANCE.shaderFadeSeconds,
            effectType = currentEffectType,
            cameraShake = shake
        )
        ServerPlayNetworking.send(player, packet)
        if (isVortexActive && !isLegendaryEvent(currentEffectType)) {
            bossBar.addPlayer(player)
        } else {
            bossBar.removePlayer(player)
        }
    }

    fun syncToAllPlayers(server: MinecraftServer) {
        val remSec = (remainingTicks / 20).coerceAtLeast(0)
        val shake = if (currentEffectType.lowercase(Locale.ROOT) == "palkia") VortexConfig.INSTANCE.cameraShakeIntensity else 0.0f
        val packet = VortexSyncPacket(
            isActive = isVortexActive,
            center = vortexCenter,
            noFly = isNoFlyActive,
            remainingSeconds = remSec,
            totalSeconds = totalDurationSeconds,
            fadeSeconds = VortexConfig.INSTANCE.shaderFadeSeconds,
            effectType = currentEffectType,
            cameraShake = shake
        )
        for (player in server.playerList.players) {
            ServerPlayNetworking.send(player, packet)
        }
    }

    fun isAnomalyEntity(entity: net.minecraft.world.entity.Entity): Boolean {
        if (entity.tags.contains(VORTEX_TAG)) return true
        if (entity is PokemonEntity && entity.pokemon.persistentData.getBoolean(VORTEX_TAG)) return true
        return false
    }

    fun formatTime(totalSeconds: Int): String {
        val mins = (totalSeconds / 60).coerceAtLeast(0)
        val secs = (totalSeconds % 60).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d", mins, secs)
    }

    /**
     * Invoca relámpagos atmosféricos visuales y sonoros en lugares aleatorios cerca de los jugadores,
     * pero a al menos 20 bloques de distancia de ellos, sin prender fuego ni causar daño.
     */
    private fun triggerGiratinaAtmosphericLightning(server: MinecraftServer) {
        val players = server.playerList.players.filter { !it.isSpectator }
        if (players.isEmpty()) return

        val player = players.random()
        val level = player.serverLevel()
        val random = level.random

        // Distancia: al menos 20 bloques, hasta 45 bloques de distancia
        val distance = 20.0 + random.nextDouble() * 25.0
        val angle = random.nextDouble() * 2.0 * Math.PI
        val targetX = player.x + kotlin.math.cos(angle) * distance
        val targetZ = player.z + kotlin.math.sin(angle) * distance

        val blockX = targetX.toInt()
        val blockZ = targetZ.toInt()

        if (!level.hasChunk(blockX shr 4, blockZ shr 4)) return

        val strikePos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos(blockX, 0, blockZ))
        if (strikePos.y <= level.minBuildHeight) return

        val lightning = EntityType.LIGHTNING_BOLT.create(level) ?: return
        lightning.moveTo(Vec3.atBottomCenterOf(strikePos))
        lightning.setVisualOnly(true)
        level.addFreshEntity(lightning)
    }
}

