package com.addoncobblemon.legendarycooldown

import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokeball.ThrownPokeballHitEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import java.util.concurrent.TimeUnit
import java.util.UUID

class LegendaryCooldownMod : ModInitializer {
    private val COOLDOWN_MILLIS = 48L * 60L * 60L * 1000L
    private val HOUR_MILLIS = TimeUnit.HOURS.toMillis(1)
    private val bossBars = mutableMapOf<UUID, ServerBossEvent>()

    override fun onInitialize() {
        TextCatalog.load()
        CobblemonEvents.THROWN_POKEBALL_HIT.subscribe(::onPokeballHit)
        CobblemonEvents.POKEMON_CAPTURED.subscribe(::onPokemonCaptured)

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> registerCommands(dispatcher) }

        ServerTickEvents.END_SERVER_TICK.register(::onServerTick)
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> onPlayerJoin(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            bossBars.remove(handler.player.uuid)?.removePlayer(handler.player)
        }
    }

    private fun registerCommands(dispatcher: com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("cooldown")
                .executes { context ->
                    val player = context.source.playerOrException
                    val data = CooldownData.get(player.server)
                    val playerData = data.player(player.uuid)
                    playerData.bossBarEnabled = !playerData.bossBarEnabled
                    data.changed()
                    updateBossBar(player, playerData, System.currentTimeMillis())
                    player.sendSystemMessage(TextCatalog.component(if (playerData.bossBarEnabled) "bossbar.enabled" else "bossbar.disabled"))
                    1
                }
                .then(Commands.literal("aviso").executes { context ->
                    val player = context.source.playerOrException
                    val data = CooldownData.get(player.server)
                    val playerData = data.player(player.uuid)
                    playerData.joinNoticeEnabled = !playerData.joinNoticeEnabled
                    data.changed()
                    player.sendSystemMessage(TextCatalog.component(if (playerData.joinNoticeEnabled) "notice.enabled" else "notice.disabled"))
                    1
                })
                .then(
                    Commands.literal("admin")
                        .requires { it.hasPermission(2) }
                        .then(
                            Commands.argument("jugador", EntityArgument.player())
                                .then(Commands.literal("quitar").executes { context ->
                                    val target = EntityArgument.getPlayer(context, "jugador")
                                    clearCooldown(target)
                                    context.source.sendSuccess({ TextCatalog.component("cooldown.cleared.admin", "player" to target.scoreboardName) }, true)
                                    1
                                })
                                .then(Commands.literal("poner")
                                    .then(Commands.argument("horas", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("minutos", IntegerArgumentType.integer(0, 59))
                                            .executes { context ->
                                                val target = EntityArgument.getPlayer(context, "jugador")
                                                val hours = IntegerArgumentType.getInteger(context, "horas")
                                                val minutes = IntegerArgumentType.getInteger(context, "minutos")
                                                val duration = hours.toLong() * HOUR_MILLIS + TimeUnit.MINUTES.toMillis(minutes.toLong())
                                                if (duration == 0L) {
                                                    clearCooldown(target)
                                                } else {
                                                    setCooldown(target, duration)
                                                }
                                                context.source.sendSuccess({
                                                    TextCatalog.component("cooldown.set.admin", "player" to target.scoreboardName, "time" to formatTime(duration))
                                                }, true)
                                                1
                                            }
                                        )
                                    )
                                )
                                .then(Commands.literal("restar")
                                    .then(Commands.argument("horas", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("minutos", IntegerArgumentType.integer(0, 59))
                                            .executes { context ->
                                                val target = EntityArgument.getPlayer(context, "jugador")
                                                val hours = IntegerArgumentType.getInteger(context, "horas")
                                                val minutes = IntegerArgumentType.getInteger(context, "minutos")
                                                val reduction = hours.toLong() * HOUR_MILLIS + TimeUnit.MINUTES.toMillis(minutes.toLong())
                                                reduceCooldown(target, reduction)
                                                context.source.sendSuccess({
                                                    TextCatalog.component("cooldown.reduced.admin", "time" to formatTime(reduction), "player" to target.scoreboardName)
                                                }, true)
                                                1
                                            }
                                        )
                                    )
                                )
                        )
                )
        )
    }

    private fun clearCooldown(player: ServerPlayer) {
        val data = CooldownData.get(player.server)
        val playerData = data.player(player.uuid)
        playerData.cooldownUntil = 0L
        playerData.readyMessageSent = true
        data.changed()
        updateBossBar(player, playerData, System.currentTimeMillis())
        player.sendSystemMessage(TextCatalog.component("cooldown.cleared"))
    }

    private fun setCooldown(player: ServerPlayer, duration: Long) {
        val data = CooldownData.get(player.server)
        val playerData = data.player(player.uuid)
        playerData.cooldownUntil = System.currentTimeMillis() + duration
        playerData.readyMessageSent = false
        data.changed()
        updateBossBar(player, playerData, System.currentTimeMillis())
        player.sendSystemMessage(TextCatalog.component("cooldown.set", "time" to formatTime(duration)))
    }

    private fun reduceCooldown(player: ServerPlayer, reduction: Long) {
        val data = CooldownData.get(player.server)
        val playerData = data.player(player.uuid)
        val now = System.currentTimeMillis()
        val remaining = playerData.cooldownUntil - now
        if (remaining <= 0L) {
            player.sendSystemMessage(TextCatalog.component("cooldown.none_to_reduce"))
            return
        }

        val updatedRemaining = remaining - reduction
        if (updatedRemaining <= 0L) {
            clearCooldown(player)
            player.sendSystemMessage(TextCatalog.component("cooldown.ready"))
        } else {
            playerData.cooldownUntil = now + updatedRemaining
            data.changed()
            updateBossBar(player, playerData, now)
            player.sendSystemMessage(TextCatalog.component("cooldown.remaining", "time" to formatTime(updatedRemaining)))
        }
    }

    private fun onPokeballHit(event: ThrownPokeballHitEvent) {
        val player = event.pokeBall.owner as? ServerPlayer ?: return
        val pokemon = event.pokemon.pokemon
        if (!isTrackedLegendary(pokemon)) return

        val playerData = CooldownData.get(player.server).player(player.uuid)
        val remaining = playerData.cooldownUntil - System.currentTimeMillis()
        if (remaining <= 0L) return

        RefundTracker.mark(event.pokeBall.uuid, event.pokeBall.pokeBall.item().defaultInstance)
        event.cancel()
        player.sendSystemMessage(TextCatalog.component("cooldown.blocked", "time" to formatTime(remaining)))
    }

    private fun onPokemonCaptured(event: PokemonCapturedEvent) {
        if (!isTrackedLegendary(event.pokemon)) return

        val data = CooldownData.get(event.player.server)
        val playerData = data.player(event.player.uuid)
        playerData.cooldownUntil = System.currentTimeMillis() + COOLDOWN_MILLIS
        playerData.readyMessageSent = false
        data.changed()
        updateBossBar(event.player, playerData, System.currentTimeMillis())
    }

    companion object {
        private val ULTRA_BEAST_NAMES = setOf(
            "nihilego", "buzzwole", "pheromosa", "xurkitree", "celesteela",
            "kartana", "guzzlord", "poipole", "naganadel", "stakataka", "blacephalon"
        )

        fun isTrackedLegendary(pokemon: com.cobblemon.mod.common.pokemon.Pokemon): Boolean {
            if (pokemon.isUltraBeast()) return false
            val labels = pokemon.species.labels
            if (labels.any { it.equals(com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels.ULTRA_BEAST, ignoreCase = true) || it.equals("ultra_beast", ignoreCase = true) }) {
                return false
            }
            val path = pokemon.species.resourceIdentifier.path.lowercase()
            val name = pokemon.species.name.lowercase()
            if (ULTRA_BEAST_NAMES.contains(path) || ULTRA_BEAST_NAMES.contains(name)) {
                return false
            }
            return pokemon.isLegendary() || pokemon.isMythical()
        }
    }

    private fun onServerTick(server: MinecraftServer) {
        if (server.tickCount % 20 != 0) return
        val now = System.currentTimeMillis()
        val data = CooldownData.get(server)
        server.playerList.players.forEach { player ->
            val playerData = data.player(player.uuid)
            val remaining = playerData.cooldownUntil - now
            if (playerData.cooldownUntil > 0L && remaining <= 0L && !playerData.readyMessageSent) {
                player.sendSystemMessage(TextCatalog.component("cooldown.ready"))
                playerData.readyMessageSent = true
                data.changed()
            }
            updateBossBar(player, playerData, now)
        }
    }

    private fun onPlayerJoin(player: ServerPlayer) {
        val data = CooldownData.get(player.server)
        val playerData = data.player(player.uuid)
        val now = System.currentTimeMillis()
        val remaining = playerData.cooldownUntil - now

        if (playerData.cooldownUntil > 0L && remaining <= 0L && !playerData.readyMessageSent) {
            player.sendSystemMessage(TextCatalog.component("cooldown.ready"))
            playerData.readyMessageSent = true
            data.changed()
        } else if (playerData.joinNoticeEnabled) {
            val message = if (remaining > 0L) {
                TextCatalog.component("cooldown.join_wait", "time" to formatTime(remaining))
            } else {
                TextCatalog.component("cooldown.ready")
            }
            player.sendSystemMessage(message)
        }

        updateBossBar(player, playerData, now)
    }

    private fun updateBossBar(player: ServerPlayer, data: CooldownData.PlayerData, now: Long) {
        val remaining = data.cooldownUntil - now
        val shouldShow = data.bossBarEnabled && remaining > 0L
        val bar = bossBars.getOrPut(player.uuid) {
            ServerBossEvent(
                Component.empty(),
                BossEvent.BossBarColor.BLUE,
                BossEvent.BossBarOverlay.PROGRESS
            )
        }
        if (shouldShow) {
            bar.name = TextCatalog.component("bossbar.title", "time" to formatTime(remaining))
            bar.progress = (remaining.toFloat() / COOLDOWN_MILLIS).coerceIn(0F, 1F)
            bar.addPlayer(player)
        } else {
            bar.removePlayer(player)
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}