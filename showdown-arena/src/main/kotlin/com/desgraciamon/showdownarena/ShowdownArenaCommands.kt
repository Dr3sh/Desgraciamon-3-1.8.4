package com.desgraciamon.showdownarena

import com.cobblemon.mod.common.api.npc.NPCClasses
import com.cobblemon.mod.common.battles.BattleBuilder
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

object ShowdownArenaCommands {
    private data class ActiveTestBattle(val battleId: UUID, val trainer: NPCEntity)

    private data class PendingChallenge(
        val challengerId: UUID,
        val challengedId: UUID,
        val format: ArenaFormat,
        val level: Int,
        val expiresAt: Long
    )

    data class ActiveShowdownBattle(
        val participants: Set<UUID>,
        val formatLabel: String,
        val isPvp: Boolean,
        var announced: Boolean = false
    )

    private val pendingByChallenged = mutableMapOf<UUID, PendingChallenge>()
    private val activeTestBattles = mutableMapOf<UUID, ActiveTestBattle>()
    private val activeShowdownBattles = mutableMapOf<UUID, ActiveShowdownBattle>()

    fun getBattleMeta(battleId: UUID): ActiveShowdownBattle? = activeShowdownBattles[battleId]
    private const val CHALLENGE_LIFETIME_MS = 60_000L
    private const val DEFAULT_LEVEL = 50

    val GENERATION_OPTIONS = listOf(
        "gen1", "gen2", "gen3", "gen4", "gen5", "gen6", "gen7", "gen8", "gen9", "global"
    )

    val CATEGORY_OPTIONS = listOf(
        "random", "chaos", "ou", "ubers", "vgc", "monotype"
    )

    private val generationSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        val rem = builder.remaining.lowercase()
        GENERATION_OPTIONS.forEach { opt -> if (opt.startsWith(rem)) builder.suggest(opt) }
        builder.buildFuture()
    }

    private val categorySuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        val rem = builder.remaining.lowercase()
        CATEGORY_OPTIONS.forEach { opt -> if (opt.startsWith(rem)) builder.suggest(opt) }
        builder.buildFuture()
    }

    private val levelSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        val rem = builder.remaining.lowercase()
        listOf("50", "100", "0").forEach { opt -> if (opt.startsWith(rem)) builder.suggest(opt) }
        builder.buildFuture()
    }

    private val teamSizeSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        val rem = builder.remaining.lowercase()
        listOf("6", "4", "3", "2", "1").forEach { opt -> if (opt.startsWith(rem)) builder.suggest(opt) }
        builder.buildFuture()
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val combatesRoot = Commands.literal("combates")
            .executes { ctx ->
                val player = ctx.source.player
                if (player != null) {
                    if (!ArenaZoneManager.isAllowedToBattle(player)) {
                        ctx.source.sendFailure(Component.literal("§cSolo puedes usar /combates dentro de la Zona de Combate."))
                        return@executes 0
                    }
                    ServerPlayNetworking.send(player, OpenShowdownGuiPayload(""))
                } else {
                    ctx.source.sendSuccess({ Component.literal("§bUsa /combates desde el juego para abrir Desgraciamon 3 Combats.") }, false)
                }
                1
            }
            .then(Commands.literal("retar")
                .then(Commands.argument("jugador", EntityArgument.player())
                    .executes { ctx ->
                        val player = ctx.source.playerOrException
                        val target = EntityArgument.getPlayer(ctx, "jugador")
                        if (!ArenaZoneManager.isAllowedToBattle(player)) {
                            ctx.source.sendFailure(Component.literal("§cDebes estar dentro de la Zona de Combate para retar a otros jugadores."))
                            return@executes 0
                        }
                        if (!ArenaZoneManager.isAllowedToBattle(target)) {
                            ctx.source.sendFailure(Component.literal("§c${target.name.string} esta fuera de la Zona de Combate."))
                            return@executes 0
                        }
                        ServerPlayNetworking.send(player, OpenShowdownGuiPayload(target.gameProfile.name))
                        1
                    }
                    .then(Commands.argument("generacion", StringArgumentType.word())
                        .suggests(generationSuggestions)
                        .then(Commands.argument("categoria", StringArgumentType.word())
                            .suggests(categorySuggestions)
                            .executes { ctx ->
                                val target = EntityArgument.getPlayer(ctx, "jugador")
                                val gen = StringArgumentType.getString(ctx, "generacion")
                                val cat = StringArgumentType.getString(ctx, "categoria")
                                challengeByParams(ctx.source, gen, cat, target, DEFAULT_LEVEL, null)
                            }
                            .then(Commands.argument("nivel", IntegerArgumentType.integer(0, 100))
                                .suggests(levelSuggestions)
                                .executes { ctx ->
                                    val target = EntityArgument.getPlayer(ctx, "jugador")
                                    val gen = StringArgumentType.getString(ctx, "generacion")
                                    val cat = StringArgumentType.getString(ctx, "categoria")
                                    val lvl = IntegerArgumentType.getInteger(ctx, "nivel")
                                    challengeByParams(ctx.source, gen, cat, target, lvl, null)
                                }
                                .then(Commands.argument("tamano", IntegerArgumentType.integer(1, 6))
                                    .suggests(teamSizeSuggestions)
                                    .executes { ctx ->
                                        val target = EntityArgument.getPlayer(ctx, "jugador")
                                        val gen = StringArgumentType.getString(ctx, "generacion")
                                        val cat = StringArgumentType.getString(ctx, "categoria")
                                        val lvl = IntegerArgumentType.getInteger(ctx, "nivel")
                                        val size = IntegerArgumentType.getInteger(ctx, "tamano")
                                        challengeByParams(ctx.source, gen, cat, target, lvl, size)
                                    }
                                )
                            )
                        )
                    )
                )
            )
            .then(Commands.literal("aceptar")
                .then(Commands.argument("jugador", EntityArgument.player())
                    .executes { ctx -> accept(ctx.source, EntityArgument.getPlayer(ctx, "jugador")) }
                )
            )
            .then(Commands.literal("rechazar")
                .then(Commands.argument("jugador", EntityArgument.player())
                    .executes { ctx -> decline(ctx.source, EntityArgument.getPlayer(ctx, "jugador")) }
                )
            )
            .then(Commands.literal("practica")
                .executes { ctx ->
                    val player = ctx.source.player
                    if (player != null) {
                        if (!ArenaZoneManager.isAllowedToBattle(player)) {
                            ctx.source.sendFailure(Component.literal("§cSolo puedes practicar combates dentro de la Zona de Combate."))
                            return@executes 0
                        }
                        ServerPlayNetworking.send(player, OpenShowdownGuiPayload(""))
                    }
                    1
                }
                .then(Commands.argument("generacion", StringArgumentType.word())
                    .suggests(generationSuggestions)
                    .executes { ctx ->
                        startTestByParams(ctx.source, StringArgumentType.getString(ctx, "generacion"), "random", DEFAULT_LEVEL, null)
                    }
                    .then(Commands.argument("categoria", StringArgumentType.word())
                        .suggests(categorySuggestions)
                        .executes { ctx ->
                            startTestByParams(
                                ctx.source,
                                StringArgumentType.getString(ctx, "generacion"),
                                StringArgumentType.getString(ctx, "categoria"),
                                DEFAULT_LEVEL,
                                null
                            )
                        }
                        .then(Commands.argument("nivel", IntegerArgumentType.integer(0, 100))
                            .suggests(levelSuggestions)
                            .executes { ctx ->
                                startTestByParams(
                                    ctx.source,
                                    StringArgumentType.getString(ctx, "generacion"),
                                    StringArgumentType.getString(ctx, "categoria"),
                                    IntegerArgumentType.getInteger(ctx, "nivel"),
                                    null
                                )
                            }
                            .then(Commands.argument("tamano", IntegerArgumentType.integer(1, 6))
                                .suggests(teamSizeSuggestions)
                                .executes { ctx ->
                                    startTestByParams(
                                        ctx.source,
                                        StringArgumentType.getString(ctx, "generacion"),
                                        StringArgumentType.getString(ctx, "categoria"),
                                        IntegerArgumentType.getInteger(ctx, "nivel"),
                                        IntegerArgumentType.getInteger(ctx, "tamano")
                                    )
                                }
                            )
                        )
                    )
                )
            )
            .then(Commands.literal("admin")
                .requires { it.hasPermission(2) }
                .then(Commands.literal("zona")
                    .then(Commands.literal("fijar")
                        .then(Commands.argument("radio", IntegerArgumentType.integer(3, 200))
                            .executes { ctx ->
                                val player = ctx.source.playerOrException
                                val radio = IntegerArgumentType.getInteger(ctx, "radio")
                                val dim = player.level().dimension().location().toString()
                                val px = player.x
                                val py = player.y
                                val pz = player.z
                                val newZone = ArenaZone(
                                    name = "Arena Desgraciamon 3",
                                    dimension = dim,
                                    minX = kotlin.math.floor(px - radio),
                                    minY = kotlin.math.floor(py),
                                    minZ = kotlin.math.floor(pz - radio),
                                    maxX = kotlin.math.ceil(px + radio),
                                    maxY = kotlin.math.ceil(py + 10),
                                    maxZ = kotlin.math.ceil(pz + radio)
                                )
                                ArenaZoneManager.setZone(newZone, true)
                                ArenaZoneManager.syncToAll(player.server)
                                ctx.source.sendSuccess({
                                    Component.literal("§aArena de Combates establecida con radio $radio bloques en $dim.")
                                }, true)
                                1
                            }
                        )
                    )
                    .then(Commands.literal("alternar").executes { ctx ->
                        val newState = ArenaZoneManager.toggleEnabled()
                        ctx.source.server?.let { ArenaZoneManager.syncToAll(it) }
                        val status = if (newState) "§aACTIVADA (solo dentro de la Arena)" else "§eDESACTIVADA (combates libres en todo el mundo)"
                        ctx.source.sendSuccess({ Component.literal("§bRestriccion de Arena: $status") }, true)
                        1
                    })
                    .then(Commands.literal("info").executes { ctx ->
                        val cfg = ArenaZoneManager.config
                        val z = cfg.zone
                        if (z != null) {
                            ctx.source.sendSuccess({
                                Component.literal("§bZona actual: §f${z.name} [${z.dimension}] (${z.minX.toInt()}, ${z.minY.toInt()}, ${z.minZ.toInt()}) a (${z.maxX.toInt()}, ${z.maxY.toInt()}, ${z.maxZ.toInt()}) - Estado: ${if (cfg.enabled) "§aACTIVA" else "§cINACTIVA"}")
                            }, false)
                        } else {
                            ctx.source.sendSuccess({ Component.literal("§eNo hay zona configurada. Usa /combates admin zona fijar <radio>.") }, false)
                        }
                        1
                    })
                )
                .then(Commands.literal("gts")
                    .then(Commands.literal("fijar")
                        .then(Commands.argument("radio", IntegerArgumentType.integer(3, 200))
                            .executes { ctx ->
                                val player = ctx.source.playerOrException
                                val radio = IntegerArgumentType.getInteger(ctx, "radio")
                                val dim = player.level().dimension().location().toString()
                                val px = player.x
                                val py = player.y
                                val pz = player.z
                                val newZone = GtsZone(
                                    name = "Mercado GTS",
                                    dimension = dim,
                                    minX = kotlin.math.floor(px - radio),
                                    minY = kotlin.math.floor(py - 2),
                                    minZ = kotlin.math.floor(pz - radio),
                                    maxX = kotlin.math.ceil(px + radio),
                                    maxY = kotlin.math.ceil(py + 15),
                                    maxZ = kotlin.math.ceil(pz + radio)
                                )
                                GtsZoneManager.setZone(newZone, true)
                                GtsZoneManager.syncToAll(player.server)
                                ctx.source.sendSuccess({
                                    Component.literal("§aZona GTS establecida con radio $radio bloques en $dim.")
                                }, true)
                                1
                            }
                        )
                    )
                    .then(Commands.literal("alternar").executes { ctx ->
                        val newState = GtsZoneManager.toggleEnabled()
                        ctx.source.server?.let { GtsZoneManager.syncToAll(it) }
                        val status = if (newState) "§aACTIVADA (/gts solo dentro de la zona)" else "§eDESACTIVADA (/gts libre en todo el mundo)"
                        ctx.source.sendSuccess({ Component.literal("§bRestriccion GTS: $status") }, true)
                        1
                    })
                    .then(Commands.literal("info").executes { ctx ->
                        val cfg = GtsZoneManager.config
                        val z = cfg.zone
                        if (z != null) {
                            ctx.source.sendSuccess({
                                Component.literal("§bZona GTS actual: §f${z.name} [${z.dimension}] (${z.minX.toInt()}, ${z.minY.toInt()}, ${z.minZ.toInt()}) a (${z.maxX.toInt()}, ${z.maxY.toInt()}, ${z.maxZ.toInt()}) - Estado: ${if (cfg.enabled) "§aACTIVA" else "§cINACTIVA"}")
                            }, false)
                        } else {
                            ctx.source.sendSuccess({ Component.literal("§eNo hay zona GTS configurada. Usa /combates admin gts fijar <radio>.") }, false)
                        }
                        1
                    })
                )
            )

        dispatcher.register(combatesRoot)
    }

    private fun resolveGenAndCategoryFormatId(genRaw: String, categoryRaw: String, teamSize: Int?): String {
        val genClean = genRaw.lowercase().trim()
        val genPrefix = when {
            genClean in listOf("gen1", "1", "kanto") -> "gen1"
            genClean in listOf("gen2", "2", "johto") -> "gen2"
            genClean in listOf("gen3", "3", "hoenn") -> "gen3"
            genClean in listOf("gen4", "4", "sinnoh") -> "gen4"
            genClean in listOf("gen5", "5", "unova", "teselia") -> "gen5"
            genClean in listOf("gen6", "6", "kalos") -> "gen6"
            genClean in listOf("gen7", "7", "alola") -> "gen7"
            genClean in listOf("gen8", "8", "galar") -> "gen8"
            genClean in listOf("gen9", "9", "paldea") -> "gen9"
            genClean in listOf("global", "todas", "all", "gen0", "0", "nacional") -> "global"
            else -> genClean
        }

        val catClean = categoryRaw.lowercase().trim()
        val catSlug = when {
            catClean in listOf("random", "randombattle", "competitivo", "random_competitive") -> "random"
            catClean in listOf("chaos", "caos", "randompuro", "puro", "random_chaos") -> "chaos"
            catClean in listOf("ou", "overused") -> "ou"
            catClean in listOf("ubers", "uber", "legendarios", "leyendas") -> "ubers"
            catClean in listOf("vgc", "dobles", "doubles", "vgc_doubles", "2v2") -> "vgc"
            catClean in listOf("monotype", "mono", "tipo") -> "monotype"
            else -> catClean
        }

        val base = "$genPrefix-$catSlug"
        return if (teamSize != null) "$base-$teamSize" else base
    }

    private fun challengeByParams(
        source: CommandSourceStack,
        genRaw: String,
        categoryRaw: String,
        target: ServerPlayer,
        level: Int,
        teamSize: Int?
    ): Int {
        val formatId = resolveGenAndCategoryFormatId(genRaw, categoryRaw, teamSize)
        return challenge(source, target, formatId, level)
    }

    private fun startTestByParams(
        source: CommandSourceStack,
        genRaw: String,
        categoryRaw: String,
        level: Int,
        teamSize: Int?
    ): Int {
        val formatId = resolveGenAndCategoryFormatId(genRaw, categoryRaw, teamSize)
        return startTestBattle(source, formatId, level)
    }

    fun tickTestBattles(server: MinecraftServer) {
        activeTestBattles.toMap().forEach { (playerId, activeBattle) ->
            val player = server.playerList.getPlayer(playerId)
            val battle = BattleRegistry.getBattle(activeBattle.battleId)
            if (battle == null || battle.ended || player == null) {
                removeTestBattle(playerId)
            }
        }
        activeShowdownBattles.toMap().forEach { (battleId, meta) ->
            val battle = BattleRegistry.getBattle(battleId)
            if (battle == null || battle.ended) {
                activeShowdownBattles.remove(battleId)
                for (uuid in meta.participants) {
                    server.playerList.getPlayer(uuid)?.let { ShowdownBattlePartySync.clearParty(it) }
                }
            } else {
                for (actor in battle.actors) {
                    for (bp in actor.pokemonList) {
                        val entity = bp.entity ?: continue
                        if (!entity.entityData.get(PokemonEntity.UNBATTLEABLE)) {
                            entity.entityData.set(PokemonEntity.UNBATTLEABLE, true)
                        }
                        if (!entity.isInvulnerable) {
                            entity.isInvulnerable = true
                        }
                    }
                }
            }
        }
    }

    fun onPlayerDisconnect(player: ServerPlayer) {
        removeTestBattle(player.uuid)
        activeShowdownBattles.entries.forEach { (battleId, meta) ->
            if (player.uuid in meta.participants) {
                activeShowdownBattles[battleId] = meta.copy(participants = meta.participants - player.uuid)
            }
        }
    }

    fun removeTestBattle(playerId: UUID) {
        activeTestBattles.remove(playerId)?.let { activeBattle ->
            runCatching { activeBattle.trainer.discard() }
        }
    }

    fun removeAllTestBattles() {
        activeTestBattles.keys.toList().forEach(::removeTestBattle)
        activeShowdownBattles.clear()
    }

    private fun formatSummaryColored(format: ArenaFormat, level: Int): Component {
        val levelLabel = if (level == 0) "Balanceado" else "Nv. $level"
        return Component.empty()
            .append(Component.literal("[${format.genDisplay}]").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" "))
            .append(Component.literal("${format.category.displayName} ${format.teamSize}v${format.teamSize}").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" "))
            .append(Component.literal("($levelLabel)").withStyle(ChatFormatting.GOLD))
    }

    private fun startTestBattle(source: CommandSourceStack, formatName: String, level: Int): Int {
        val player = try {
            source.playerOrException
        } catch (_: Exception) {
            source.sendFailure(TextCatalog.component("error.players_only"))
            return 0
        }
        val format = findFormat(formatName)
        if (format == null || !isSupportedLevel(level)) {
            source.sendFailure(Component.literal("§cFormato no valido. Usa /combates para configurar tu combate."))
            return 0
        }
        val battleLevel = if (level in 1..100) level else -1
        if (activeTestBattles.containsKey(player.uuid) || isBusy(player)) {
            source.sendFailure(TextCatalog.component("error.in_battle"))
            return 0
        }
        if (!ArenaZoneManager.isAllowedToBattle(player)) {
            source.sendFailure(Component.literal("§cLos combates de Desgraciamon 3 Combats solo estan permitidos dentro de la Arena designada."))
            return 0
        }

        val world = player.level() as? ServerLevel ?: return 0
        val playerTeam = try {
            ArenaTeamFactory.createTeam(player.uuid, format, battleLevel)
        } catch (exception: Exception) {
            source.sendFailure(TextCatalog.component("error.player_team", "error" to exception.message))
            return 0
        }

        val playerSpeciesIds = ArenaTeamFactory.extractSpeciesIds(playerTeam)

        val trainer = NPCEntity(world)
        val npcClass = NPCClasses.getByName("standard")
            ?: NPCClasses.classes.firstOrNull()
            ?: NPCClasses.dummy()
        trainer.npc = npcClass
        trainer.party = try {
            ArenaTeamFactory.createNpcParty(trainer, format, battleLevel, excludedSpecies = playerSpeciesIds)
        } catch (exception: Exception) {
            source.sendFailure(TextCatalog.component("error.team_generation", "error" to exception.message))
            return 0
        }
        trainer.customName = TextCatalog.component("trainer.name")
        trainer.hideNameTag = true
        trainer.isMovable = false
        trainer.isInvulnerable = true

        val lookAngle = player.lookAngle
        trainer.moveTo(player.x + lookAngle.x * 2.0, player.y, player.z + lookAngle.z * 2.0, player.yRot + 180f, 0f)

        if (!world.addFreshEntity(trainer)) {
            source.sendFailure(TextCatalog.component("error.trainer_creation"))
            return 0
        }

        var started = false
        try {
            BattleBuilder.pvn(
                player = player,
                npcEntity = trainer,
                battleFormat = battleFormat(format),
                cloneParties = true,
                healFirst = true,
                party = playerTeam
            ).ifSuccessful { battle ->
                activeTestBattles[player.uuid] = ActiveTestBattle(battle.battleId, trainer)
                activeShowdownBattles[battle.battleId] = ActiveShowdownBattle(
                    participants = setOf(player.uuid),
                    formatLabel = "${format.category.displayName} (${format.genDisplay})",
                    isPvp = false
                )
                ShowdownBattlePartySync.sendParty(player, battle)
                val playerSummary = playerTeam.joinToString(", ") { "${it.species.name} Nv.${it.level}" }
                val npcSummary = trainer.party?.joinToString(", ") { "${it.species.name} Nv.${it.level}" }.orEmpty()
                player.sendSystemMessage(
                    Component.literal("[Desgraciamon 3 Combats] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Practica iniciada: ").withStyle(ChatFormatting.GRAY))
                        .append(formatSummaryColored(format, level))
                )
                player.sendSystemMessage(Component.literal("§aTu equipo: §f$playerSummary"))
                player.sendSystemMessage(Component.literal("§cEquipo rival: §f$npcSummary"))
                started = true
            }.ifErrored {
                trainer.discard()
                it.sendTo(player) { message -> message.withStyle(ChatFormatting.RED) }
            }
        } catch (exception: Exception) {
            trainer.discard()
            source.sendFailure(TextCatalog.component("error.test_battle_exception", "error" to exception.message))
        }
        return if (started) 1 else 0
    }

    private fun challenge(source: CommandSourceStack, target: ServerPlayer, formatName: String, level: Int): Int {
        val challenger = source.playerOrException
        val format = findFormat(formatName)
        if (format == null || !isSupportedLevel(level)) {
            source.sendFailure(Component.literal("§cFormato no valido. Usa /combates retar <jugador>."))
            return 0
        }
        if (challenger.uuid == target.uuid) {
            source.sendFailure(TextCatalog.component("error.self_challenge"))
            return 0
        }
        if (pendingByChallenged.containsKey(target.uuid)) {
            source.sendFailure(TextCatalog.component("error.challenge_pending"))
            return 0
        }
        if (isBusy(challenger) || isBusy(target)) {
            source.sendFailure(TextCatalog.component("error.in_battle"))
            return 0
        }
        if (!ArenaZoneManager.isAllowedToBattle(challenger)) {
            source.sendFailure(Component.literal("§cDebes estar dentro de la Arena de Combate para desafiar a otros jugadores."))
            return 0
        }
        if (!ArenaZoneManager.isAllowedToBattle(target)) {
            source.sendFailure(Component.literal("§c${target.name.string} esta fuera de la Arena de Combate."))
            return 0
        }

        pendingByChallenged[target.uuid] = PendingChallenge(
            challenger.uuid,
            target.uuid,
            format,
            level,
            System.currentTimeMillis() + CHALLENGE_LIFETIME_MS
        )
        target.sendSystemMessage(challengeMessage(challenger.gameProfile.name, format, level))
        source.sendSuccess({
            Component.literal("[Desgraciamon 3 Combats] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Desafio enviado a ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(target.name.string).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" en ").withStyle(ChatFormatting.GRAY))
                .append(formatSummaryColored(format, level))
        }, false)
        return 1
    }

    private fun accept(source: CommandSourceStack, challenger: ServerPlayer): Int {
        val challenged = source.playerOrException
        if (!ArenaZoneManager.isAllowedToBattle(challenged)) {
            source.sendFailure(Component.literal("§cDebes estar dentro de la Zona de Combate para aceptar un desafio."))
            return 0
        }
        val pending = pendingByChallenged[challenged.uuid]
        if (pending == null || pending.challengerId != challenger.uuid || pending.expiresAt < System.currentTimeMillis()) {
            pendingByChallenged.remove(challenged.uuid)
            source.sendFailure(TextCatalog.component("error.challenge_expired"))
            return 0
        }
        if (isBusy(challenged) || isBusy(challenger)) {
            pendingByChallenged.remove(challenged.uuid)
            val message = TextCatalog.component("error.in_battle")
            challenged.sendSystemMessage(message)
            challenger.sendSystemMessage(message)
            return 0
        }
        if (!ArenaZoneManager.isAllowedToBattle(challenged) || !ArenaZoneManager.isAllowedToBattle(challenger)) {
            val msg = Component.literal("§cAmbos jugadores deben estar dentro de la Arena para combatir.")
            challenged.sendSystemMessage(msg)
            challenger.sendSystemMessage(msg)
            return 0
        }

        pendingByChallenged.remove(challenged.uuid)
        val effectiveLevel = if (pending.level in 1..100) pending.level else -1
        val temporaryTeams = try {
            val team1 = ArenaTeamFactory.createTeam(challenger.uuid, pending.format, effectiveLevel)
            val team1Species = ArenaTeamFactory.extractSpeciesIds(team1)
            val team2 = ArenaTeamFactory.createTeam(challenged.uuid, pending.format, effectiveLevel, excludedSpecies = team1Species)
            mapOf(
                challenger.uuid to team1,
                challenged.uuid to team2
            )
        } catch (exception: Exception) {
            source.sendFailure(TextCatalog.component("error.team_generation.generic", "error" to exception.message))
            return 0
        }

        var started = false
        try {
            BattleBuilder.pvp1v1(
                player1 = challenger,
                player2 = challenged,
                battleFormat = battleFormat(pending.format),
                cloneParties = true,
                healFirst = true,
                partyAccessor = { player -> temporaryTeams.getValue(player.uuid) }
            ).ifSuccessful { battle ->
                activeShowdownBattles[battle.battleId] = ActiveShowdownBattle(
                    participants = setOf(challenger.uuid, challenged.uuid),
                    formatLabel = "${pending.format.category.displayName} (${pending.format.genDisplay})",
                    isPvp = true
                )
                ShowdownBattlePartySync.sendParty(challenger, battle)
                ShowdownBattlePartySync.sendParty(challenged, battle)
                val p1Summary = temporaryTeams.getValue(challenger.uuid).joinToString(", ") { "${it.species.name} Nv.${it.level}" }
                val p2Summary = temporaryTeams.getValue(challenged.uuid).joinToString(", ") { "${it.species.name} Nv.${it.level}" }
                val coloredFormat = formatSummaryColored(pending.format, pending.level)

                challenger.sendSystemMessage(
                    Component.literal("[Desgraciamon 3 Combats] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Combate iniciado contra ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(challenged.name.string).withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(coloredFormat)
                )
                challenged.sendSystemMessage(
                    Component.literal("[Desgraciamon 3 Combats] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Combate iniciado contra ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(challenger.name.string).withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(coloredFormat)
                )
                challenger.sendSystemMessage(Component.literal("§aTu equipo: §f$p1Summary"))
                challenged.sendSystemMessage(Component.literal("§aTu equipo: §f$p2Summary"))
                started = true
            }.ifErrored { errors ->
                errors.sendTo(listOf(challenger, challenged)) { it.withStyle(ChatFormatting.RED) }
            }
        } catch (exception: Exception) {
            val message = TextCatalog.component("error.battle_start.exception", "error" to (exception.message ?: exception.javaClass.simpleName))
            challenger.sendSystemMessage(message)
            challenged.sendSystemMessage(message)
        }
        return if (started) 1 else 0
    }

    private fun decline(source: CommandSourceStack, challenger: ServerPlayer): Int {
        val challenged = source.playerOrException
        val pending = pendingByChallenged[challenged.uuid]
        if (pending == null || pending.challengerId != challenger.uuid) {
            source.sendFailure(TextCatalog.component("error.no_challenge"))
            return 0
        }
        pendingByChallenged.remove(challenged.uuid)
        challenged.sendSystemMessage(TextCatalog.component("challenge.declined", "player" to challenger.gameProfile.name))
        challenger.sendSystemMessage(TextCatalog.component("challenge.not_accepted", "player" to challenged.name.string))
        return 1
    }

    private fun challengeMessage(challengerName: String, format: ArenaFormat, level: Int): Component {
        val acceptCommand = "/combates aceptar $challengerName"
        val declineCommand = "/combates rechazar $challengerName"
        return Component.empty()
            .append(Component.literal("[Desgraciamon 3 Combats] ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(challengerName).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
            .append(Component.literal(" te desafia en ").withStyle(ChatFormatting.GRAY))
            .append(formatSummaryColored(format, level))
            .append(Component.literal(" "))
            .append(
                Component.literal("[ACEPTAR]").withStyle {
                    it.withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, acceptCommand))
                        .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, TextCatalog.component("challenge.accept.hover")))
                }
            )
            .append(Component.literal(" "))
            .append(
                Component.literal("[RECHAZAR]").withStyle {
                    it.withColor(ChatFormatting.RED)
                        .withBold(true)
                        .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, declineCommand))
                        .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, TextCatalog.component("challenge.decline.hover")))
                }
            )
    }

    private fun isBusy(player: ServerPlayer): Boolean =
        BattleRegistry.getBattleByParticipatingPlayer(player) != null

    private fun findFormat(name: String): ArenaFormat? {
        return when (name.lowercase()) {
            "random" -> ArenaFormatCatalog.find("gen9-random")
            "ou" -> ArenaFormatCatalog.find("gen9-ou")
            "ubers" -> ArenaFormatCatalog.find("gen9-ubers")
            "vgc" -> ArenaFormatCatalog.find("global-vgc")
            "monotype" -> ArenaFormatCatalog.find("global-monotype")
            "chaos" -> ArenaFormatCatalog.find("global-chaos")
            else -> ArenaFormatCatalog.find(name)
        }
    }

    private fun battleFormat(format: ArenaFormat): BattleFormat {
        val base = when (format.battleType) {
            ArenaBattleType.SINGLES -> BattleFormat.GEN_9_SINGLES
            ArenaBattleType.DOUBLES -> BattleFormat.GEN_9_DOUBLES
        }
        return base.copy(
            mod = "cobblemon",
            ruleSet = base.ruleSet,
            gen = 9,
            adjustLevel = -1
        )
    }

    private fun isSupportedLevel(level: Int): Boolean = level in 0..100
}
