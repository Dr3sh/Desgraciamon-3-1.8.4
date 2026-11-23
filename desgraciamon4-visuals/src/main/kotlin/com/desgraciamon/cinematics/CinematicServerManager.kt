package com.desgraciamon.cinematics

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.Vec3
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PlayerInitialState(
    val pos: Vec3,
    val yaw: Float,
    val pitch: Float,
    val levelKey: ResourceKey<Level>,
    val wasInvisible: Boolean
)

data class PersistedRestoreEntry(
    val uuid: String = "",
    val x: Double = 0.0,
    val y: Double = 64.0,
    val z: Double = 0.0,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val dimension: String = "minecraft:overworld",
    val wasInvisible: Boolean = false
)

object CinematicServerManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    var activeRoute: CinematicRoute? = null
        private set

    var activeTargetPlayerUuid: UUID? = null
        private set

    var startTimestampMs: Long = 0L
        private set

    var isPreparing: Boolean = false
        private set

    var isPaused: Boolean = false
        private set

    private var prepareStartMs: Long = 0L
    private var pausedElapsedMs: Long = 0L
    private var lastHeartbeatMs: Long = 0L
    private var lastLoopCycle: Int = 0

    private val activeParticipants = ConcurrentHashMap.newKeySet<UUID>()
    private val readyPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val frozenPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val initialPlayerStates = ConcurrentHashMap<UUID, PlayerInitialState>()
    private val triggeredServerKeyframes = ConcurrentHashMap.newKeySet<Int>()
    private val forcedChunks = ConcurrentHashMap.newKeySet<Pair<ResourceKey<Level>, ChunkPos>>()
    private val sentChunksByPlayer = ConcurrentHashMap<UUID, MutableSet<Long>>()

    fun isPlayerFrozen(uuid: UUID): Boolean = frozenPlayers.contains(uuid)
    fun isParticipant(uuid: UUID): Boolean = activeParticipants.contains(uuid)

    private fun restoreFile(server: MinecraftServer): File {
        val dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data").toFile()
        dataDir.mkdirs()
        return File(dataDir, "desgraciamon_cinematics_restore.json")
    }

    private fun saveRestoreStatesToDisk(server: MinecraftServer) {
        runCatching {
            val entries = initialPlayerStates.map { (uuid, st) ->
                PersistedRestoreEntry(
                    uuid = uuid.toString(),
                    x = st.pos.x,
                    y = st.pos.y,
                    z = st.pos.z,
                    yaw = st.yaw,
                    pitch = st.pitch,
                    dimension = st.levelKey.location().toString(),
                    wasInvisible = st.wasInvisible
                )
            }
            val target = restoreFile(server)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(gson.toJson(entries), Charsets.UTF_8)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun clearRestoreFile(server: MinecraftServer) {
        runCatching {
            val f = restoreFile(server)
            if (f.exists()) f.delete()
        }
    }

    fun restorePendingOnStartupOrJoin(player: ServerPlayer) {
        val server = player.server ?: return
        val file = restoreFile(server)
        if (!file.exists()) return
        runCatching {
            val type = object : TypeToken<List<PersistedRestoreEntry>>() {}.type
            val list: MutableList<PersistedRestoreEntry> = gson.fromJson(file.readText(Charsets.UTF_8), type) ?: mutableListOf()
            val idx = list.indexOfFirst { it.uuid == player.uuid.toString() }
            if (idx >= 0) {
                val entry = list.removeAt(idx)
                val rl = ResourceLocation.tryParse(entry.dimension) ?: ResourceLocation.withDefaultNamespace("overworld")
                val key = ResourceKey.create(Registries.DIMENSION, rl)
                val level = server.getLevel(key) ?: server.overworld()
                player.teleportTo(level, entry.x, entry.y, entry.z, entry.yaw, entry.pitch)
                player.isInvisible = entry.wasInvisible
                player.isSilent = false
                frozenPlayers.remove(player.uuid)
                if (list.isEmpty()) {
                    file.delete()
                } else {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(gson.toJson(list), Charsets.UTF_8)
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun resolveRouteLevel(server: MinecraftServer, route: CinematicRoute): ServerLevel {
        val rl = ResourceLocation.tryParse(route.dimension) ?: ResourceLocation.withDefaultNamespace("overworld")
        val key = ResourceKey.create(Registries.DIMENSION, rl)
        return server.getLevel(key) ?: server.overworld()
    }

    fun playCinematic(
        server: MinecraftServer,
        route: CinematicRoute,
        targetPlayer: ServerPlayer? = null
    ) {
        if (activeRoute != null) {
            stopCinematic(server, "replaced")
        }

        val targetLevel = resolveRouteLevel(server, route)
        preloadRouteChunks(targetLevel, route)

        activeRoute = route
        activeTargetPlayerUuid = targetPlayer?.uuid
        isPreparing = true
        isPaused = false
        prepareStartMs = System.currentTimeMillis()
        startTimestampMs = 0L
        pausedElapsedMs = 0L
        lastHeartbeatMs = 0L
        lastLoopCycle = 0
        triggeredServerKeyframes.clear()
        readyPlayers.clear()
        activeParticipants.clear()
        sentChunksByPlayer.clear()

        val participants = if (targetPlayer != null) listOf(targetPlayer) else server.playerList.players.toList()
        if (participants.isEmpty()) {
            stopCinematic(server, "no_players")
            return
        }

        val routeJson = CinematicRouteStorage.toJson(route)
        val requiredAnims = route.collectRequiredAnimations()

        for (player in participants) {
            activeParticipants.add(player.uuid)
            initialPlayerStates[player.uuid] = PlayerInitialState(
                player.position(),
                player.yRot,
                player.xRot,
                player.level().dimension(),
                player.isInvisible
            )
            if (route.freezePlayers) {
                frozenPlayers.add(player.uuid)
            }
            CinematicPackets.prepare(player, route.name, routeJson, requiredAnims)
        }
        saveRestoreStatesToDisk(server)

        if (targetPlayer == null) {
            server.playerList.broadcastSystemMessage(
                Component.literal("§6§l[ESTUDIO CINEMÁTICO] §ePreparando transmisión: §f${route.description}"),
                false
            )
        }
    }

    fun onClientReady(server: MinecraftServer, player: ServerPlayer, routeName: String) {
        val route = activeRoute ?: return
        if (!isPreparing || !route.name.equals(routeName, ignoreCase = true)) return
        if (!activeParticipants.contains(player.uuid)) return
        readyPlayers.add(player.uuid)
        if (readyPlayers.size >= activeParticipants.size) {
            beginSynchronizedPlayback(server, route)
        }
    }

    private fun beginSynchronizedPlayback(server: MinecraftServer, route: CinematicRoute) {
        if (!isPreparing) return
        isPreparing = false
        val now = System.currentTimeMillis()
        startTimestampMs = now + 200L
        lastHeartbeatMs = now
        val routeJson = CinematicRouteStorage.toJson(route)

        for (uuid in activeParticipants) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            CinematicPackets.play(player, route.name, routeJson, startTimestampMs, now)
        }
    }

    fun pauseCinematic(server: MinecraftServer): Boolean {
        if (activeRoute == null || isPreparing || isPaused) return false
        pausedElapsedMs = (System.currentTimeMillis() - startTimestampMs).coerceAtLeast(0L)
        isPaused = true
        broadcastHeartbeat(server, pausedElapsedMs, true)
        return true
    }

    fun resumeCinematic(server: MinecraftServer): Boolean {
        if (activeRoute == null || isPreparing || !isPaused) return false
        val now = System.currentTimeMillis()
        startTimestampMs = now - pausedElapsedMs
        isPaused = false
        broadcastHeartbeat(server, pausedElapsedMs, false)
        return true
    }

    fun seekCinematic(server: MinecraftServer, seconds: Double): Boolean {
        val route = activeRoute ?: return false
        val targetMs = (seconds.coerceAtLeast(0.0) * 1000.0).toLong()
        if (isPaused) {
            pausedElapsedMs = targetMs
        } else {
            startTimestampMs = System.currentTimeMillis() - targetMs
        }
        triggeredServerKeyframes.clear()
        broadcastHeartbeat(server, targetMs, isPaused)
        return true
    }

    fun onPlayerSkip(server: MinecraftServer, player: ServerPlayer) {
        val route = activeRoute ?: return
        if (!route.skippable) return
        if (!activeParticipants.contains(player.uuid)) return

        if (activeTargetPlayerUuid == player.uuid || activeParticipants.size <= 1) {
            stopCinematic(server, "skipped")
        } else {
            removePlayerFromCinematic(server, player, "skipped")
        }
    }

    fun onPlayerDisconnect(server: MinecraftServer, player: ServerPlayer) {
        if (!activeParticipants.contains(player.uuid) && !initialPlayerStates.containsKey(player.uuid)) return
        removePlayerFromCinematic(server, player, "disconnect")
        if (activeParticipants.isEmpty() && activeRoute != null) {
            stopCinematic(server, "all_disconnected")
        }
    }

    fun onPlayerDeathOrDimensionChange(server: MinecraftServer, player: ServerPlayer, reason: String) {
        if (!activeParticipants.contains(player.uuid)) return
        removePlayerFromCinematic(server, player, reason)
        if (activeParticipants.isEmpty() && activeRoute != null) {
            stopCinematic(server, reason)
        }
    }

    private fun removePlayerFromCinematic(server: MinecraftServer, player: ServerPlayer, reason: String) {
        activeParticipants.remove(player.uuid)
        frozenPlayers.remove(player.uuid)
        readyPlayers.remove(player.uuid)
        sentChunksByPlayer.remove(player.uuid)
        val state = initialPlayerStates.remove(player.uuid)
        if (state != null) {
            val targetLevel = server.getLevel(state.levelKey) ?: server.overworld()
            if (player.level() != targetLevel || player.position().distanceToSqr(state.pos) > 0.25) {
                player.teleportTo(targetLevel, state.pos.x, state.pos.y, state.pos.z, state.yaw, state.pitch)
            }
            player.isInvisible = state.wasInvisible
            player.isSilent = false
        }
        val cp = player.chunkPosition()
        player.connection.send(ClientboundSetChunkCacheCenterPacket(cp.x, cp.z))
        CinematicPackets.stop(player, reason)
        saveRestoreStatesToDisk(server)
    }

    fun stopCinematic(server: MinecraftServer, reason: String = "finished") {
        val route = activeRoute
        activeRoute = null
        activeTargetPlayerUuid = null
        startTimestampMs = 0L
        isPreparing = false
        isPaused = false
        pausedElapsedMs = 0L
        triggeredServerKeyframes.clear()
        readyPlayers.clear()

        for (uuid in activeParticipants.toList()) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            val state = initialPlayerStates[uuid]
            if (state != null) {
                player.isInvisible = state.wasInvisible
                player.isSilent = false
            }
            val cp = player.chunkPosition()
            player.connection.send(ClientboundSetChunkCacheCenterPacket(cp.x, cp.z))
            CinematicPackets.stop(player, reason)
        }

        activeParticipants.clear()
        frozenPlayers.clear()
        initialPlayerStates.clear()
        sentChunksByPlayer.clear()
        clearRestoreFile(server)
        clearForcedChunks(server)

        if (route != null && reason == "completed") {
            server.playerList.broadcastSystemMessage(
                Component.literal("§a§l¡La cinemática ha concluido! El control vuelve a los jugadores."),
                false
            )
        }
    }

    fun onServerTick(server: MinecraftServer) {
        val route = activeRoute ?: return
        val now = System.currentTimeMillis()

        if (isPreparing) {
            if (now - prepareStartMs >= 2500L) {
                beginSynchronizedPlayback(server, route)
            }
            return
        }

        val elapsedMs = if (isPaused) pausedElapsedMs else (now - startTimestampMs).coerceAtLeast(0L)
        val elapsedSec = elapsedMs / 1000.0
        val totalSec = route.totalDurationSeconds()

        // Heartbeat cada 500ms para mantener sincronía contra lag de ping/FPS
        if (now - lastHeartbeatMs >= 500L) {
            lastHeartbeatMs = now
            broadcastHeartbeat(server, elapsedMs, isPaused)
        }

        if (!route.loop && elapsedSec >= totalSec + 0.6) {
            stopCinematic(server, "completed")
            return
        }

        val cycleSec = if (route.loop && totalSec > 0.0) {
            val cycle = (elapsedSec / totalSec).toInt()
            if (cycle != lastLoopCycle) {
                lastLoopCycle = cycle
                triggeredServerKeyframes.clear()
            }
            elapsedSec % totalSec
        } else {
            elapsedSec
        }

        if (!isPaused) {
            var accumulated = 0.0
            for ((idx, kf) in route.keyframes.withIndex()) {
                if (cycleSec >= accumulated && !triggeredServerKeyframes.contains(idx)) {
                    triggeredServerKeyframes.add(idx)
                    kf.animationTrigger?.trim()?.takeIf { it.isNotEmpty() }?.let { animName ->
                        for (uuid in activeParticipants) {
                            val player = server.playerList.getPlayer(uuid) ?: continue
                            server.commands.performPrefixedCommand(
                                server.createCommandSourceStack(),
                                "animacion play $animName ${player.scoreboardName}"
                            )
                        }
                    }
                }
                accumulated += kf.durationSeconds
            }
        }

        // Mantener chunks cargados y enviados a los participantes alrededor de camPos sin teletransportar ni ocultar al jugador
        val targetLevel = resolveRouteLevel(server, route)
        val camPos = route.evaluatePosition(elapsedSec)
        val camCp = ChunkPos(BlockPos(camPos.x.toInt(), camPos.y.toInt(), camPos.z.toInt()))
        forceChunkRadius(targetLevel, camCp, 1)

        for (uuid in activeParticipants) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            if (player.level() != targetLevel) continue
            val sent = sentChunksByPlayer.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }
            player.connection.send(ClientboundSetChunkCacheCenterPacket(camCp.x, camCp.z))
            for (dx in -2..2) {
                for (dz in -2..2) {
                    val cx = camCp.x + dx
                    val cz = camCp.z + dz
                    val key = ChunkPos.asLong(cx, cz)
                    if (sent.add(key)) {
                        val chunk = targetLevel.getChunk(cx, cz)
                        player.connection.send(ClientboundLevelChunkWithLightPacket(chunk, targetLevel.lightEngine, null, null))
                    }
                }
            }
        }
    }

    private fun broadcastHeartbeat(server: MinecraftServer, elapsedMs: Long, paused: Boolean) {
        for (uuid in activeParticipants) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            CinematicPackets.heartbeat(player, elapsedMs, paused)
        }
    }

    private fun preloadRouteChunks(level: ServerLevel, route: CinematicRoute) {
        clearForcedChunks(level.server)
        val total = route.totalDurationSeconds()
        if (total <= 0.0 || route.keyframes.isEmpty()) {
            route.keyframes.forEach { kf ->
                val cp = ChunkPos(BlockPos(kf.x.toInt(), kf.y.toInt(), kf.z.toInt()))
                forceChunkRadius(level, cp, 1)
            }
            return
        }
        val step = 0.5
        var t = 0.0
        while (t <= total) {
            val p = route.evaluatePosition(t)
            val cp = ChunkPos(BlockPos(p.x.toInt(), p.y.toInt(), p.z.toInt()))
            forceChunkRadius(level, cp, 1)
            t += step
        }
    }

    private fun forceChunkRadius(level: ServerLevel, center: ChunkPos, radius: Int) {
        val dimKey = level.dimension()
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val cp = ChunkPos(center.x + dx, center.z + dz)
                if (forcedChunks.add(Pair(dimKey, cp))) {
                    level.setChunkForced(cp.x, cp.z, true)
                }
            }
        }
    }

    private fun clearForcedChunks(server: MinecraftServer) {
        for ((dimKey, cp) in forcedChunks) {
            val level = server.getLevel(dimKey) ?: continue
            runCatching { level.setChunkForced(cp.x, cp.z, false) }
        }
        forcedChunks.clear()
    }
}
