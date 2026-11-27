package com.desgraciamon.afk

import com.desgraciamon.badges.RichText
import com.desgraciamon.customnames.CustomNamesMod
import com.desgraciamon.joinmessages.JoinMessagesMod
import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object AfkManager {
    private val LOGGER = LoggerFactory.getLogger("DesgraciamonAFK")
    private val configPath: Path = Path.of("config", "desgraciamon4-afk", "afk.properties")

    private data class AfkState(
        var x: Double,
        var y: Double,
        var z: Double,
        var yRot: Float,
        var xRot: Float,
        var lastActiveMs: Long,
        var isAfk: Boolean = false,
        var afkSinceMs: Long = 0L,
        var lastDisplayedMinutes: Long = -1L
    )

    private val states = ConcurrentHashMap<UUID, AfkState>()
    private var tickCounter = 0

    @Volatile var timeoutSeconds: Long = 300L
    @Volatile var afkMessageTemplate: String = "<color=#9E9E9E>{player} ahora está AFK.</color>"
    @Volatile var returnMessageTemplate: String = "<color=#68D391>{player} ya no está AFK.</color>"
    @Volatile var tablistFormat: String = "<color=#9E9E9E> AFK{time}</color>"

    fun init() {
        loadConfig()

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val p = handler.player
            val now = System.currentTimeMillis()
            states[p.uuid] = AfkState(p.x, p.y, p.z, p.yRot, p.xRot, now)
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            states.remove(handler.player.uuid)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            states.clear()
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            tickCounter++
            if (tickCounter >= 20) {
                tickCounter = 0
                tickPlayers(server)
            }
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerCommands(dispatcher)
        }

        LOGGER.info("Desgraciamon AFK & /played inicializado (timeout=${timeoutSeconds}s).")
    }

    fun loadConfig() {
        try {
            Files.createDirectories(configPath.parent)
            val props = Properties()
            if (Files.exists(configPath)) {
                Files.newBufferedReader(configPath, StandardCharsets.UTF_8).use(props::load)
            }

            timeoutSeconds = props.getProperty("afk.timeout_seconds", timeoutSeconds.toString())
                .trim().toLongOrNull()?.coerceAtLeast(10L) ?: 300L
            afkMessageTemplate = props.getProperty("afk.message", afkMessageTemplate)
            returnMessageTemplate = props.getProperty("afk.return_message", returnMessageTemplate)
            tablistFormat = props.getProperty("afk.tablist_format", tablistFormat)

            props.setProperty("afk.timeout_seconds", timeoutSeconds.toString())
            props.setProperty("afk.message", afkMessageTemplate)
            props.setProperty("afk.return_message", returnMessageTemplate)
            props.setProperty("afk.tablist_format", tablistFormat)

            Files.newBufferedWriter(configPath, StandardCharsets.UTF_8).use { writer ->
                props.store(
                    writer,
                    "Configuracion AFK. Soporta #HEX (<color=#RRGGBB>...</color>), <prism=#col1,#col2>...</prism>, <bold>, <italic>. Variables: {player}, {time}"
                )
            }
        } catch (e: Exception) {
            LOGGER.error("Error cargando configuracion AFK", e)
        }
    }

    private fun tickPlayers(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000L

        for (player in server.playerList.players) {
            val st = states.computeIfAbsent(player.uuid) {
                AfkState(player.x, player.y, player.z, player.yRot, player.xRot, now)
            }

            val moved = abs(player.x - st.x) > 0.05 ||
                abs(player.y - st.y) > 0.05 ||
                abs(player.z - st.z) > 0.05 ||
                abs(player.yRot - st.yRot) > 0.5f ||
                abs(player.xRot - st.xRot) > 0.5f

            if (moved) {
                st.x = player.x
                st.y = player.y
                st.z = player.z
                st.yRot = player.yRot
                st.xRot = player.xRot
                st.lastActiveMs = now

                if (st.isAfk) {
                    st.isAfk = false
                    st.afkSinceMs = 0L
                    st.lastDisplayedMinutes = -1L
                    broadcastDisplayNameUpdate(server, player)
                    if (returnMessageTemplate.isNotBlank()) {
                        broadcastMessage(server, returnMessageTemplate, player)
                    }
                }
                continue
            }

            if (!st.isAfk) {
                if (now - st.lastActiveMs >= timeoutMs) {
                    st.isAfk = true
                    st.afkSinceMs = now
                    st.lastDisplayedMinutes = 0L
                    broadcastDisplayNameUpdate(server, player)
                    if (afkMessageTemplate.isNotBlank()) {
                        broadcastMessage(server, afkMessageTemplate, player)
                    }
                }
            } else {
                val mins = ((now - st.afkSinceMs) / 60_000L).coerceAtLeast(0L)
                if (mins != st.lastDisplayedMinutes) {
                    st.lastDisplayedMinutes = mins
                    broadcastDisplayNameUpdate(server, player)
                }
            }
        }
    }

    private fun broadcastDisplayNameUpdate(server: MinecraftServer, target: ServerPlayer) {
        val packet = ClientboundPlayerInfoUpdatePacket(
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
            target
        )
        for (viewer in server.playerList.players) {
            viewer.connection.send(packet)
        }
    }

    private fun broadcastMessage(server: MinecraftServer, template: String, player: ServerPlayer) {
        val playerNameComp = CustomNamesMod.getNameComponent(player.uuid, false)
            ?: Component.literal(player.gameProfile.name)
        val comp = RichText.parse(template, mapOf("player" to playerNameComp))
        server.playerList.broadcastSystemMessage(comp, false)
    }

    fun getTabListSuffix(uuid: UUID): Component? {
        val st = states[uuid] ?: return null
        if (!st.isAfk) return null
        val mins = ((System.currentTimeMillis() - st.afkSinceMs) / 60_000L).coerceAtLeast(0L)
        val timeStr = formatAfkTime(mins)
        return RichText.parse(tablistFormat, mapOf("time" to timeStr))
    }

    private fun formatAfkTime(totalMinutes: Long): String {
        if (totalMinutes <= 0L) return ""
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        return when {
            hours > 0L && mins > 0L -> " ${hours}h${mins}m"
            hours > 0L -> " ${hours}h"
            else -> " ${mins}m"
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val sec = totalSeconds.coerceAtLeast(0L)
        val days = sec / 86400L
        val hours = (sec % 86400L) / 3600L
        val minutes = (sec % 3600L) / 60L
        val seconds = sec % 60L
        return buildString {
            if (days > 0L) append("${days}d ")
            if (hours > 0L || days > 0L) append("${hours}h ")
            if (minutes > 0L || hours > 0L || days > 0L) append("${minutes}m ")
            append("${seconds}s")
        }.trim()
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("played")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    sendPlayedInfo(ctx.source, player)
                    1
                }
                .then(
                    Commands.argument("jugador", EntityArgument.player())
                        .executes { ctx ->
                            val target = EntityArgument.getPlayer(ctx, "jugador")
                            sendPlayedInfo(ctx.source, target)
                            1
                        }
                )
        )

        dispatcher.register(
            Commands.literal("afk")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    toggleManualAfk(ctx.source.server, player)
                    1
                }
                .then(
                    Commands.literal("reload")
                        .requires { it.hasPermission(2) }
                        .executes { ctx ->
                            loadConfig()
                            ctx.source.sendSuccess(
                                { RichText.parse("<color=#68D391>Configuración AFK recargada.</color>") },
                                false
                            )
                            1
                        }
                )
        )
    }

    private fun toggleManualAfk(server: MinecraftServer, player: ServerPlayer) {
        val now = System.currentTimeMillis()
        val st = states.computeIfAbsent(player.uuid) {
            AfkState(player.x, player.y, player.z, player.yRot, player.xRot, now)
        }
        st.x = player.x
        st.y = player.y
        st.z = player.z
        st.yRot = player.yRot
        st.xRot = player.xRot

        if (!st.isAfk) {
            st.isAfk = true
            st.afkSinceMs = now
            st.lastDisplayedMinutes = 0L
            broadcastDisplayNameUpdate(server, player)
            if (afkMessageTemplate.isNotBlank()) {
                broadcastMessage(server, afkMessageTemplate, player)
            }
        } else {
            st.isAfk = false
            st.afkSinceMs = 0L
            st.lastDisplayedMinutes = -1L
            st.lastActiveMs = now
            broadcastDisplayNameUpdate(server, player)
            if (returnMessageTemplate.isNotBlank()) {
                broadcastMessage(server, returnMessageTemplate, player)
            }
        }
    }

    private fun sendPlayedInfo(source: CommandSourceStack, target: ServerPlayer) {
        val totalTicks = target.stats.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)).toLong()
        val totalSeconds = totalTicks / 20L
        val sessionSeconds = JoinMessagesMod.getSessionDurationSeconds(target.uuid)

        val nameComp = CustomNamesMod.getNameComponent(target.uuid, false)
            ?: Component.literal(target.gameProfile.name)

        val msg = RichText.parse(
            "<color=#7DD3FC><bold>Tiempo jugado de </bold></color>{player}<color=#7DD3FC>:</color>\n" +
                "<color=#A0AEC0>- Total en el servidor: </color><color=#E0F2FE>{total}</color>\n" +
                "<color=#A0AEC0>- Sesión actual: </color><color=#68D391>{session}</color>",
            mapOf(
                "player" to nameComp,
                "total" to formatDuration(totalSeconds),
                "session" to formatDuration(sessionSeconds)
            )
        )
        source.sendSuccess({ msg }, false)
    }
}
