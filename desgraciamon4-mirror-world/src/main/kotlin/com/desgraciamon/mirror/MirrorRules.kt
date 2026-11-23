package com.desgraciamon.mirror

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.entity.Despawner
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.FlintAndSteelItem
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reglas del Mundo Espejo:
 * - Identificador de la dimensión (`desgraciamon4_mirror:mirror_world`).
 * - Protección total de bloques, ítems, daño, capturas y batallas.
 * - Spawneo y rotación inteligente sin repetición de los Pokémon-recuerdo cuando el jugador
 *   no los está mirando y está a más de 20 bloques de distancia.
 */
object MirrorRules {
    const val MOD_ID = "desgraciamon4_mirror"
    const val MEMORY_TAG = "desgraciamon4_mirror_memory"

    private val logger = LoggerFactory.getLogger("desgraciamon4-mirror")

    @JvmField
    val DIMENSION_ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, "mirror_world")

    @JvmField
    val DIMENSION_KEY: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, DIMENSION_ID)

    @JvmField
    val RECUERDOS_DIMENSION_ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, "dimension_recuerdos")

    @JvmField
    val RECUERDOS_DIMENSION_KEY: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, RECUERDOS_DIMENSION_ID)

    @JvmStatic
    fun isRecuerdos(level: Level?): Boolean = level != null && level.dimension() == RECUERDOS_DIMENSION_KEY

    @JvmField
    val MEMORY_FADE_SOUND_ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, "memory_fade")

    @JvmField
    val MEMORY_FADE_SOUND: net.minecraft.sounds.SoundEvent = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(MEMORY_FADE_SOUND_ID)

    @JvmField
    val MEMORY_ARRIVE_SOUND_ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, "memory_arrive")

    @JvmField
    val MEMORY_ARRIVE_SOUND: net.minecraft.sounds.SoundEvent = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(MEMORY_ARRIVE_SOUND_ID)

    /** Metadatos en memoria de cada entidad Pokémon-recuerdo activa (por UUID de entidad). */
    data class ActiveMemoryMeta(
        val plotIndex: Int,
        val ownerUuid: UUID,
        val entry: MemoryEntry,
        val displayName: String,
        val speciesName: String,
        val spawnedAtMillis: Long,
    )

    /**
     * Estado de rotación de una parcela:
     * Mantiene una baraja barajada (`deck`) que garantiza que todos los Pokémon del dueño
     * salgan antes de repetir alguno, y que al re-barajarse nunca sigan el mismo orden.
     */
    private class PlotRotationState(
        val plotIndex: Int,
        val ownerUuid: UUID,
        val allPool: List<MemoryEntry>,
    ) {
        val activeEntities = mutableListOf<PokemonEntity>()
        val deck = ArrayDeque<MemoryEntry>()
        var lastPicked: MemoryEntry? = null

        init {
            refillDeck(emptySet())
        }

        fun refillDeck(currentlyActiveEntries: Set<MemoryEntry>) {
            val candidates = allPool.filter { it !in currentlyActiveEntries }.shuffled().toMutableList()
            if (candidates.size > 1 && lastPicked != null && candidates.first() === lastPicked) {
                // Evitar que el último de la baraja anterior sea el primero de la nueva
                val first = candidates.removeAt(0)
                candidates.add(first)
            }
            deck.addAll(candidates)
        }

        fun nextEntry(currentlyActiveEntries: Set<MemoryEntry>): MemoryEntry? {
            if (allPool.isEmpty()) return null
            // Limpiar entradas que ya estén activas
            while (deck.isNotEmpty() && deck.first() in currentlyActiveEntries) {
                deck.removeFirst()
            }
            if (deck.isEmpty()) {
                refillDeck(currentlyActiveEntries)
            }
            val picked = deck.removeFirstOrNull() ?: return null
            lastPicked = picked
            return picked
        }
    }

    private val activeMemories = ConcurrentHashMap<UUID, ActiveMemoryMeta>()
    private val plotStates = ConcurrentHashMap<Int, PlotRotationState>()
    /** Cooldown por jugador para el mensaje de protección. */
    private val lastWarnMillis = ConcurrentHashMap<UUID, Long>()
    /** Cooldown por jugador para el mensaje de montañas inescalables. */
    private val lastClimbWarnMillis = ConcurrentHashMap<UUID, Long>()
    /** Cooldown por jugador para clic derecho sobre Pokémon. */
    private val lastInspectMillis = ConcurrentHashMap<UUID, Long>()

    /** Jugadores con /modoadmin activo para poder construir/destruir y pasar las montañas. */
    val adminModePlayers = ConcurrentHashMap.newKeySet<UUID>()

    @JvmStatic
    fun isAdminMode(player: Player?): Boolean =
        player != null && (adminModePlayers.contains(player.uuid) || player.tags.contains("modoadmin"))

    @JvmStatic
    fun toggleAdminMode(player: ServerPlayer): Boolean {
        return if (player.tags.contains("modoadmin") || adminModePlayers.contains(player.uuid)) {
            player.tags.remove("modoadmin")
            adminModePlayers.remove(player.uuid)
            false
        } else {
            player.tags.add("modoadmin")
            adminModePlayers.add(player.uuid)
            true
        }
    }

    @JvmStatic
    fun isProtected(world: Level?, player: Player? = null): Boolean {
        if (world == null) return false
        if (!isMirror(world) && !isRecuerdos(world)) return false
        if (player != null && isAdminMode(player)) return false
        return true
    }

    @JvmStatic
    fun isMirror(level: Level?): Boolean = level != null && level.dimension() == DIMENSION_KEY

    @JvmStatic
    fun allowSpawn(entity: Entity): Boolean {
        if (entity is Player) return true
        if (entity is PokemonEntity) {
            if (MEMORY_TAG in entity.tags) return true
            if (entity.ownerUUID != null) return true
        }
        return false
    }

    private val NEVER_DESPAWN = object : Despawner<PokemonEntity> {
        override fun beginTracking(entity: PokemonEntity) {}
        override fun shouldDespawn(entity: PokemonEntity): Boolean = false
    }

    fun registerEvents() {
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT, MEMORY_FADE_SOUND_ID, MEMORY_FADE_SOUND)
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT, MEMORY_ARRIVE_SOUND_ID, MEMORY_ARRIVE_SOUND)

        // 1. Prohibir romper bloques (a menos que tenga /modoadmin)
        PlayerBlockBreakEvents.BEFORE.register { world, player, _, _, _ ->
            if (isProtected(world, player)) {
                notifyCannotManipulate(player)
                false
            } else true
        }

        AttackBlockCallback.EVENT.register { player, world, _, _, _ ->
            if (world.isClientSide) return@register InteractionResult.PASS
            if (isProtected(world, player) && !player.isSpectator) {
                notifyCannotManipulate(player)
                InteractionResult.FAIL
            } else InteractionResult.PASS
        }

        // 2. Prohibir colocar o interactuar con bloques del mundo (a menos que tenga /modoadmin)
        UseBlockCallback.EVENT.register { player, world, _, _ ->
            if (world.isClientSide) return@register InteractionResult.PASS
            if (isProtected(world, player) && !player.isSpectator) {
                notifyCannotManipulate(player)
                InteractionResult.FAIL
            } else InteractionResult.PASS
        }

        // 3. Prohibir usar cubos, mecheros, bloques o lanzar Poké Balls (a menos que tenga /modoadmin)
        UseItemCallback.EVENT.register { player, world, hand ->
            if (world.isClientSide) return@register InteractionResultHolder.pass(player.getItemInHand(hand))
            val stack = player.getItemInHand(hand)
            if (isProtected(world, player) && !player.isSpectator) {
                val id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString()
                if (stack.item is BlockItem || stack.item is BucketItem || stack.item is FlintAndSteelItem || id.contains("poke_ball") || id.contains("_ball")) {
                    notifyCannotManipulate(player)
                    return@register InteractionResultHolder.fail(stack)
                }
            }
            InteractionResultHolder.pass(stack)
        }

        // 4. Prohibir atacar entidades en dimensiones protegidas (a menos que tenga /modoadmin)
        AttackEntityCallback.EVENT.register { player, world, _, _, _ ->
            if (world.isClientSide) return@register InteractionResult.PASS
            if (isProtected(world, player)) {
                notifyCannotManipulate(player)
                InteractionResult.FAIL
            } else InteractionResult.PASS
        }

        // 5. Clic derecho sobre un Pokémon-recuerdo muestra su información nostálgica y hace su grito
        UseEntityCallback.EVENT.register { player, world, hand, entity, _ ->
            if (!isMirror(world)) return@register InteractionResult.PASS
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.CONSUME
            if (entity is PokemonEntity && MEMORY_TAG in entity.tags) {
                if (!world.isClientSide && player is ServerPlayer) {
                    inspectMemoryPokemon(player, entity)
                }
                return@register InteractionResult.SUCCESS
            }
            InteractionResult.PASS
        }

        // 6. Inmunidad total al dano y muerte en Mundo Espejo y Dimension Recuerdos
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            val lvl = entity.level()
            !isMirror(lvl) && !isRecuerdos(lvl)
        }

        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            val lvl = entity.level()
            if (isMirror(lvl) || isRecuerdos(lvl)) {
                if (entity is ServerPlayer) {
                    entity.health = entity.maxHealth
                }
                false
            } else true
        }

        // 7. Eventos de Cobblemon: no guardar en disco los Pokémon-recuerdo, ni permitir batallas/capturas
        CobblemonEvents.POKEMON_ENTITY_SAVE_TO_WORLD.subscribe(Priority.HIGHEST) { event ->
            if (isMirror(event.pokemonEntity.level()) || MEMORY_TAG in event.pokemonEntity.tags) {
                event.cancel()
            }
        }

        // Bloqueo total de generación salvaje de Cobblemon en dimension_recuerdos
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.HIGHEST) { event ->
            if (isRecuerdos(event.entity.level())) {
                event.cancel()
            }
        }

        CobblemonEvents.THROWN_POKEBALL_HIT.subscribe(Priority.HIGHEST) { event ->
            if (isMirror(event.pokemon.level()) || isRecuerdos(event.pokemon.level()) || MEMORY_TAG in event.pokemon.tags) {
                event.cancel()
            }
        }

        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.HIGHEST) { event ->
            val inMirror = event.battle.players.any { isMirror(it.level()) || isRecuerdos(it.level()) }
            if (inMirror) event.cancel()
        }

        // Eliminar colmenas/nidos de abejas al cargar chunks en dimension_recuerdos
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
            if (isRecuerdos(world)) {
                for (pos in chunk.blockEntitiesPos.toList()) {
                    val state = chunk.getBlockState(pos)
                    if (state.`is`(net.minecraft.world.level.block.Blocks.BEE_NEST) || state.`is`(net.minecraft.world.level.block.Blocks.BEEHIVE)) {
                        chunk.setBlockState(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false)
                    }
                }
            }
        }

        // Eliminar cualquier abeja en dimension_recuerdos
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register { entity, world ->
            if (isRecuerdos(world) && entity is net.minecraft.world.entity.animal.Bee) {
                entity.discard()
            }
        }


        // 8. Tick del servidor: mantener los Pokémon dentro del prado, rotarlos y evitar escalar montañas
        var tickCounter = 0
        ServerTickEvents.END_SERVER_TICK.register { server ->
            tickCounter++
            if (tickCounter % 20 == 0) {
                tickMirrorWorld(server, shouldRotate = true)
            }

            // Inmortalidad, prevencion de caida al vacio y montanas inescalables en dimension_recuerdos
            val recuerdosLevel = server.getLevel(RECUERDOS_DIMENSION_KEY)
            if (recuerdosLevel != null) {
                for (player in recuerdosLevel.players()) {
                    if (player !is ServerPlayer) continue

                    // Inmortalidad total: salud maxima y sin inanicion
                    if (player.health < player.maxHealth) {
                        player.health = player.maxHealth
                    }
                    if (player.foodData.foodLevel < 20) {
                        player.foodData.foodLevel = 20
                    }

                    // Proteccion contra caida al vacio: devolver a altura segura
                    if (player.y < -10.0) {
                        player.teleportTo(recuerdosLevel, 0.0, 75.0, 0.0, player.yRot, player.xRot)
                        player.deltaMovement = Vec3.ZERO
                        player.fallDistance = 0f
                    }

                    if (player.isSpectator || isAdminMode(player)) continue
                    val x = player.x
                    val z = player.z
                    val y = player.y
                    val dist = kotlin.math.sqrt(x * x + z * z)

                    // Bloqueo inescalable:
                    // Si intenta escalar las montanas altas (dist >= 210 y y >= 135) o salirse del radio (dist >= 242)
                    if (dist >= 242.0 || (dist >= 210.0 && y >= 135.0)) {
                        val nx = if (dist > 0.001) -x / dist else 0.0
                        val nz = if (dist > 0.001) -z / dist else 0.0
                        player.deltaMovement = Vec3(nx * 0.85, -0.2, nz * 0.85)
                        player.hurtMarked = true
                        notifyCannotClimb(player)
                    }
                }
            }
        }
    }

    fun notifyCannotClimb(player: Player) {
        if (player.level().isClientSide) return
        val now = System.currentTimeMillis()
        val prev = lastClimbWarnMillis[player.uuid] ?: 0L
        if (now - prev >= 2000L) {
            lastClimbWarnMillis[player.uuid] = now
            if (player is ServerPlayer) {
                player.displayClientMessage(Component.literal("§cLas montañas del recuerdo son inescalables."), true)
            }
        }
    }

    fun notifyCannotManipulate(player: Player) {
        if (player.level().isClientSide) return
        val now = System.currentTimeMillis()
        val cooldownMs = (MirrorSettings.config.protectMessageCooldownSeconds * 1000.0).toLong().coerceAtLeast(250L)
        val prev = lastWarnMillis[player.uuid] ?: 0L
        if (now - prev >= cooldownMs) {
            lastWarnMillis[player.uuid] = now
            player.sendSystemMessage(MirrorSettings.text("protect.blocked"))
        }
    }

    private fun inspectMemoryPokemon(player: ServerPlayer, entity: PokemonEntity) {
        val now = System.currentTimeMillis()
        val prev = lastInspectMillis[player.uuid] ?: 0L
        if (now - prev < 600L) return
        lastInspectMillis[player.uuid] = now

        val level = player.serverLevel()
        val meta = activeMemories[entity.uuid]
        val pokemon = entity.pokemon
        val speciesName = meta?.speciesName ?: pokemon.species.translatedName.string
        val displayName = meta?.displayName ?: pokemon.getDisplayName().string
        val pokeLevel = meta?.entry?.level ?: pokemon.level
        val shiny = meta?.entry?.shiny ?: pokemon.shiny
        val legendary = meta?.entry?.legendary ?: ((pokemon.isLegendary() || pokemon.isMythical()) && !pokemon.isUltraBeast())
        val season = meta?.entry?.season ?: "Temporada anterior"
        val ballRaw = (meta?.entry?.ball ?: pokemon.caughtBall.name.toString()).substringAfter(':')
            .replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        val natureName = pokemon.nature.name.path.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }.ifBlank { "Serena" }
        val abilityName = pokemon.ability.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }.ifBlank { "Vínculo" }

        // Partículas suaves de reminiscencia
        level.sendParticles(
            net.minecraft.core.particles.ParticleTypes.END_ROD,
            entity.x, entity.y + entity.bbHeight * 0.6, entity.z,
            8, 0.3, 0.3, 0.3, 0.02
        )
        // Carillón sutil y relajante de amatista
        level.playSound(
            null, entity.x, entity.y, entity.z,
            net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
            net.minecraft.sounds.SoundSource.PLAYERS,
            0.35f, 1.25f
        )
        runCatching { entity.cry() }

        val quote = when {
            shiny && legendary -> "Un resplandor mítico que grabó su leyenda en tu historia."
            shiny -> "Su brillo eterno refleja los días más memorables de tu viaje."
            legendary -> "Un guardián del mito que eligió acompañar tus pasos."
            else -> "Te observa con calma y afecto, recordando cada camino recorrido juntos."
        }

        val tag = when {
            shiny && legendary -> " §6§d[Legendario Shiny]"
            shiny -> " §e§6[Shiny]"
            legendary -> " §d[Legendario]"
            else -> ""
        }

        player.displayClientMessage(Component.literal("§dRecuerdo revivido: §f$displayName §7($speciesName) §bNv. $pokeLevel$tag"), true)
    }

    /**
     * Garantiza que la parcela del dueño esté generada y con su tanda activa de Pokémon-recuerdo spawneada.
     */
    fun ensurePlotReady(level: ServerLevel, ownerUuid: UUID, forceRegenerate: Boolean = false, explicitIndex: Int? = null): Int {
        val index = explicitIndex ?: if (forceRegenerate) PlotManager.reassign(ownerUuid) else PlotManager.indexOf(ownerUuid)
        if (!PlotManager.isGenerated(index) || forceRegenerate) {
            TerrainGenerator.generate(level, index)
            PlotManager.markGenerated(index)
        }
        spawnOrFillPlotMemories(level, index, ownerUuid)
        return index
    }

    fun respawnPlotMemories(level: ServerLevel, ownerUuid: UUID) {
        val index = PlotManager.indexOf(ownerUuid)
        clearPlotEntities(level, index)
        spawnOrFillPlotMemories(level, index, ownerUuid)
    }

    private fun spawnOrFillPlotMemories(
        level: ServerLevel,
        index: Int,
        ownerUuid: UUID,
        playersInPlot: List<ServerPlayer> = emptyList(),
    ) {
        val memories = MemoryLoader.get(ownerUuid).selected
        if (memories.isEmpty()) return

        val state = plotStates.getOrPut(index) {
            // Limpiar cualquier entidad huérfana previa en el área de la parcela
            purgeOrphanEntitiesInPlot(level, index)
            PlotRotationState(index, ownerUuid, memories)
        }

        // Purgar de la lista las que ya no estén vivas
        state.activeEntities.removeIf { entity ->
            val dead = !entity.isAlive || entity.isRemoved
            if (dead) activeMemories.remove(entity.uuid)
            dead
        }

        val maxActive = MirrorSettings.config.maxActivePokemon.coerceIn(1, 80).coerceAtMost(memories.size)
        if (state.activeEntities.size >= maxActive) return

        val existingPositions = state.activeEntities.map { it.position() }.toMutableList()
        val activeEntrySet = state.activeEntities.mapNotNullTo(HashSet()) { activeMemories[it.uuid]?.entry }

        while (state.activeEntities.size < maxActive) {
            val entry = state.nextEntry(activeEntrySet) ?: break
            activeEntrySet += entry
            val spawned = spawnSingleMemoryEntity(level, index, ownerUuid, entry, existingPositions, playersInPlot)
            if (spawned != null) {
                state.activeEntities += spawned
                existingPositions += spawned.position()
            }
        }
    }

    private fun spawnSingleMemoryEntity(
        level: ServerLevel,
        index: Int,
        ownerUuid: UUID,
        entry: MemoryEntry,
        existingPositions: List<Vec3>,
        preferOutOfViewFrom: List<ServerPlayer>,
    ): PokemonEntity? {
        return runCatching {
            val nbtCopy = entry.tag.copy()
            // IMPORTANTE: eliminar referencias a entrenador/store para que PokemonServerDelegate
            // nunca descarte la entidad pensando que es un Pokémon de party huérfano.
            nbtCopy.remove("PokemonOriginalTrainer")
            nbtCopy.remove("PokemonOriginalTrainerType")
            nbtCopy.remove("StoreCoordinates")
            nbtCopy.remove("TetheringId")

            val pokemon = Pokemon.loadFromNBT(level.registryAccess(), nbtCopy)
            pokemon.uuid = UUID.randomUUID()
            if (entry.nickname.isNotBlank()) {
                pokemon.nickname = Component.literal(entry.nickname)
            }
            pokemon.heal()
            UncatchableProperty.uncatchable().apply(pokemon)

            val aquatic = pokemon.types.any { it.showdownId() == "water" } &&
                pokemon.types.none { it.showdownId() == "ground" || it.showdownId() == "grass" || it.showdownId() == "normal" || it.showdownId() == "fighting" }
            val pos = TerrainGenerator.randomSpawn(level, index, level.random, aquatic, existingPositions, preferOutOfViewFrom)

            val entity = PokemonEntity(level, pokemon)
            entity.addTag(MEMORY_TAG)
            entity.ownerUUID = null // Mantiene la entidad estable sin que Cobblemon la descarte
            entity.despawner = NEVER_DESPAWN
            entity.countsTowardsSpawnCap = false
            entity.setPersistenceRequired()
            entity.isInvulnerable = true
            entity.isSilent = true // Silencia efectos salvajes de shiny
            entity.entityData.set(PokemonEntity.UNBATTLEABLE, true)
            entity.entityData.set(PokemonEntity.NICKNAME_VISIBLE, true)
            entity.entityData.set(PokemonEntity.SHOULD_RENDER_NAME, true)
            entity.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0f)

            if (level.addFreshEntity(entity)) {
                val speciesName = pokemon.species.translatedName.string
                val displayName = entry.nickname.ifBlank { speciesName }
                activeMemories[entity.uuid] = ActiveMemoryMeta(
                    plotIndex = index,
                    ownerUuid = ownerUuid,
                    entry = entry,
                    displayName = displayName,
                    speciesName = speciesName,
                    spawnedAtMillis = System.currentTimeMillis(),
                )
                entity
            } else null
        }.onFailure {
            logger.warn("No se pudo instanciar el recuerdo ${entry.speciesId} de $ownerUuid", it)
        }.getOrNull()
    }

    private fun purgeOrphanEntitiesInPlot(level: ServerLevel, index: Int) {
        val cx = PlotManager.centerX(index).toDouble()
        val cz = PlotManager.centerZ(index).toDouble()
        val box = AABB(cx - 80.0, 0.0, cz - 80.0, cx + 80.0, 256.0, cz + 80.0)
        for (entity in level.getEntitiesOfClass(PokemonEntity::class.java, box)) {
            if (MEMORY_TAG in entity.tags || entity.ownerUUID == null) {
                activeMemories.remove(entity.uuid)
                entity.discard()
            }
        }
    }

    fun clearPlotEntities(level: ServerLevel?, index: Int) {
        val state = plotStates.remove(index)
        if (state != null) {
            for (entity in state.activeEntities) {
                activeMemories.remove(entity.uuid)
                if (!entity.isRemoved) entity.discard()
            }
        }
        if (level != null) {
            purgeOrphanEntitiesInPlot(level, index)
        }
    }

    fun clearAllEntities(server: MinecraftServer? = null) {
        val level = server?.getLevel(DIMENSION_KEY)
        for (index in plotStates.keys.toList()) {
            clearPlotEntities(level, index)
        }
        activeMemories.clear()
    }

    private fun tickMirrorWorld(server: MinecraftServer, shouldRotate: Boolean) {
        val level = server.getLevel(DIMENSION_KEY) ?: return
        val playersInMirror = level.players()

        // Agrupar jugadores por parcela
        val playersByPlot = HashMap<Int, MutableList<ServerPlayer>>()
        for (player in playersInMirror) {
            val plot = PlotManager.plotAt(player.x)
            playersByPlot.getOrPut(plot) { mutableListOf() }.add(player)
            // Si un jugador se cae por accidente o atraviesa el borde, devolverlo al centro de la parcela
            if (player.y < TerrainGenerator.BASE - 14 || !TerrainGenerator.isInsideMeadow(plot, player.x, player.z)) {
                val safe = TerrainGenerator.spawnPoint(level, plot)
                player.teleportTo(level, safe.x, safe.y, safe.z, player.yRot, player.xRot)
            }
        }

        // Despawnear los Pokémon de parcelas donde ya no queda ningún jugador
        for (plotIndex in plotStates.keys.toList()) {
            if (plotIndex !in playersByPlot) {
                clearPlotEntities(level, plotIndex)
            }
        }

        // Gestionar cada parcela ocupada
        for ((plotIndex, playersInPlot) in playersByPlot) {
            val owner = PlotManager.ownerOf(plotIndex) ?: continue
            spawnOrFillPlotMemories(level, plotIndex, owner, playersInPlot)
            val state = plotStates[plotIndex] ?: continue

            // Mantener todos los Pokémon dentro del santuario flotante
            for (entity in state.activeEntities) {
                if (!entity.isAlive || entity.isRemoved) continue
                if (entity.y < TerrainGenerator.BASE - 12 || !TerrainGenerator.isInsideMeadow(plotIndex, entity.x, entity.z)) {
                    val safe = TerrainGenerator.randomSpawn(level, plotIndex, level.random, false)
                    entity.teleportTo(safe.x, safe.y, safe.z)
                }
            }

            // Vínculo y afecto de los Pokémon cuando el jugador se acerca
            for (player in playersInPlot) {
                for (entity in state.activeEntities) {
                    if (!entity.isAlive || entity.isRemoved) continue
                    if (entity.distanceToSqr(player) <= 36.0) {
                        entity.lookControl.setLookAt(player, 15.0f, 15.0f)
                        if (level.gameTime % 50L == 0L && level.random.nextFloat() < 0.35f) {
                            level.sendParticles(
                                net.minecraft.core.particles.ParticleTypes.HEART,
                                entity.x, entity.y + entity.bbHeight + 0.3, entity.z,
                                1, 0.1, 0.1, 0.1, 0.0
                            )
                        }
                    }
                }
            }

            // Rotación de Pokémon cuando están a > 20 bloques y fuera de la vista del jugador
            if (shouldRotate) {
                rotateOutOfSightPokemon(level, plotIndex, state, playersInPlot)
            }
        }
    }

    /**
     * Si el jugador tiene más Pokémon en su pool total que los actualmente spawneados,
     * busca hasta 2 Pokémon que lleven un tiempo prudencial en escena, estén a más de 20 bloques
     * y ningún jugador los esté mirando, y los rota por los siguientes de la baraja barajada.
     */
    private fun rotateOutOfSightPokemon(
        level: ServerLevel,
        plotIndex: Int,
        state: PlotRotationState,
        playersInPlot: List<ServerPlayer>,
    ) {
        val maxActive = MirrorSettings.config.maxActivePokemon.coerceIn(1, 80)
        if (state.allPool.size <= maxActive) return // Todos caben a la vez, no hace falta rotar

        val now = System.currentTimeMillis()
        val minAgeMs = MirrorSettings.config.rotationMinAgeSeconds.coerceAtLeast(1) * 1000L
        val minDist = MirrorSettings.config.rotationDistanceBlocks.coerceAtLeast(4.0)
        val minDistSq = minDist * minDist

        // Buscar candidatos que ningún jugador esté mirando y estén a la distancia configurada
        val candidatesToDespawn = state.activeEntities.filter { entity ->
            if (!entity.isAlive || entity.isRemoved) return@filter false
            val meta = activeMemories[entity.uuid] ?: return@filter false
            if (now - meta.spawnedAtMillis < minAgeMs) return@filter false

            playersInPlot.all { player ->
                val dx = entity.x - player.x
                val dz = entity.z - player.z
                val distSq = dx * dx + dz * dz
                if (distSq < minDistSq) return@all false // Está a menos de la distancia mínima

                // Vector horizontal de mirada del jugador vs dirección hacia el Pokémon
                val look = Vec3(player.lookAngle.x, 0.0, player.lookAngle.z).normalize()
                val toEntity = Vec3(dx, 0.0, dz).normalize()
                val dot = look.dot(toEntity)
                // dot < 0.15 significa que el jugador no está mirando hacia la dirección del Pokémon
                dot < 0.15
            }
        }

        if (candidatesToDespawn.isEmpty()) return

        // Rotar hasta 3 Pokémon por ciclo para que la transición sea suave y continua
        val toRotate = candidatesToDespawn.shuffled().take(3)
        for (oldEntity in toRotate) {
            state.activeEntities.remove(oldEntity)
            activeMemories.remove(oldEntity.uuid)
            if (!oldEntity.isRemoved) oldEntity.discard()
        }

        // Rellenar los huecos con los siguientes Pokémon de la baraja (sin repetir hasta completar vuelta)
        spawnOrFillPlotMemories(level, plotIndex, state.ownerUuid, playersInPlot)
    }
}

