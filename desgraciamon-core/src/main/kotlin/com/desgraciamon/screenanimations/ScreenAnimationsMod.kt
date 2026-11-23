package com.desgraciamon.screenanimations

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ScreenAnimationsMod : ModInitializer {
    override fun onInitialize() {
        PayloadTypeRegistry.playS2C().register(AnimationTransferStartPayload.TYPE, AnimationTransferStartPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(AnimationTransferChunkPayload.TYPE, AnimationTransferChunkPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(AnimationStopPayload.TYPE, AnimationStopPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(AnimationPlayTriggerPayload.TYPE, AnimationPlayTriggerPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(AnimationPlayInstantPayload.TYPE, AnimationPlayInstantPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(AnimationClearCachePayload.TYPE, AnimationClearCachePayload.CODEC)

        PayloadTypeRegistry.playC2S().register(AnimationReadyPayload.TYPE, AnimationReadyPayload.CODEC)

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            ScreenAnimationStorage.ensureCreated()
            ScreenAnimationStorage.syncRemoteOnServer(server)
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            ScreenAnimationStorage.onPlayerJoin(server, handler.player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            ScreenAnimationStorage.clearPrewarmedFor(handler.player.uuid)
        }

        ServerPlayNetworking.registerGlobalReceiver(AnimationReadyPayload.TYPE) { payload, context ->
            context.server().execute {
                ScreenAnimationStorage.onPlayerReady(context.server(), context.player().uuid, payload)
            }
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            ScreenAnimationStorage.tick(server)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("animacion")
                    .requires { com.desgraciamon.badges.AdminAuth.isAdminSource(it) }
                    .then(Commands.literal("reproducir")
                        .then(Commands.argument("nombre", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .suggests { _, builder ->
                                net.minecraft.commands.SharedSuggestionProvider.suggest(ScreenAnimationStorage.listAnimations(), builder)
                            }
                            .executes { context ->
                                play(
                                    context.source,
                                    context.source.server.playerList.players,
                                    com.mojang.brigadier.arguments.StringArgumentType.getString(context, "nombre")
                                )
                            }
                            .then(Commands.argument("jugadores", EntityArgument.players())
                                .executes { context ->
                                    play(
                                        context.source,
                                        EntityArgument.getPlayers(context, "jugadores").toList(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "nombre")
                                    )
                                }
                                .then(Commands.argument("modo", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .suggests { _, builder ->
                                        net.minecraft.commands.SharedSuggestionProvider.suggest(listOf("rellenar", "ajustar"), builder)
                                    }
                                    .executes { context ->
                                        play(
                                            context.source,
                                            EntityArgument.getPlayers(context, "jugadores").toList(),
                                            com.mojang.brigadier.arguments.StringArgumentType.getString(context, "nombre"),
                                            com.mojang.brigadier.arguments.StringArgumentType.getString(context, "modo")
                                        )
                                    }
                                )
                            )
                        )
                    )
                    .then(Commands.literal("precargar")
                        .then(Commands.argument("nombre", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .suggests { _, builder ->
                                net.minecraft.commands.SharedSuggestionProvider.suggest(ScreenAnimationStorage.listAnimations(), builder)
                            }
                            .executes { context ->
                                prewarm(
                                    context.source,
                                    context.source.server.playerList.players,
                                    com.mojang.brigadier.arguments.StringArgumentType.getString(context, "nombre")
                                )
                            }
                            .then(Commands.argument("jugadores", EntityArgument.players())
                                .executes { context ->
                                    prewarm(
                                        context.source,
                                        EntityArgument.getPlayers(context, "jugadores").toList(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "nombre")
                                    )
                                }
                            )
                        )
                    )
                    .then(Commands.literal("limpiar_cache")
                        .executes { context ->
                            clearCache(context.source, context.source.server.playerList.players)
                        }
                        .then(Commands.argument("jugadores", EntityArgument.players())
                            .executes { context ->
                                clearCache(context.source, EntityArgument.getPlayers(context, "jugadores").toList())
                            }
                        )
                    )
                    .then(Commands.literal("detener")
                        .executes { context ->
                            stop(context.source, context.source.server.playerList.players)
                        }
                        .then(Commands.argument("jugadores", EntityArgument.players())
                            .executes { context ->
                                stop(context.source, EntityArgument.getPlayers(context, "jugadores").toList())
                            }
                        )
                    )
                    .then(Commands.literal("lista").executes { context ->
                        val names = ScreenAnimationStorage.listAnimations()
                        context.source.sendSuccess({
                            Component.literal(if (names.isEmpty()) "No hay animaciones disponibles." else "Animaciones: ${names.joinToString(", ")}")
                        }, false)
                        1
                    })
                    .then(Commands.literal("sincronizar")
                        .executes { context ->
                            syncAndReloadForPlayers(context.source, context.source.server.playerList.players, "__sync_github__")
                        }
                        .then(Commands.argument("jugadores", EntityArgument.players())
                            .executes { context ->
                                syncAndReloadForPlayers(context.source, EntityArgument.getPlayers(context, "jugadores").toList(), "__sync_github__")
                            }
                        )
                    )
            )
        }
    }

    private fun syncAndReloadForPlayers(
        source: net.minecraft.commands.CommandSourceStack,
        targets: List<ServerPlayer>,
        mode: String
    ): Int {
        ScreenAnimationStorage.ensureCreated()
        ScreenAnimationStorage.syncRemoteOnServer(source.server)
        val payload = AnimationClearCachePayload(mode)
        var notified = 0
        targets.distinctBy { it.uuid }.forEach { player ->
            ScreenAnimationStorage.clearPrewarmedFor(player.uuid)
            if (ServerPlayNetworking.canSend(player, AnimationClearCachePayload.TYPE)) {
                ServerPlayNetworking.send(player, payload)
                notified++
            }
        }
        val actionLabel = if (mode == "__sync_github__") {
            "Sincronizacion desde GitHub y recarga de animaciones enviada"
        } else {
            "Recarga de animaciones locales y remotas enviada"
        }
        source.sendSuccess({
            Component.literal("[Animaciones] $actionLabel en el servidor y para $notified jugador(es) conectados.")
        }, true)
        return 1
    }

    private fun play(
        source: net.minecraft.commands.CommandSourceStack,
        targets: List<ServerPlayer>,
        name: String,
        overrideFit: String? = null
    ): Int {
        val eligibleTargets = targets.distinctBy { it.uuid }
        val effectiveFit = ScreenAnimationStorage.normalizeFitMode(overrideFit ?: "rellenar")
        if (name.startsWith("http://", ignoreCase = true) || name.startsWith("https://", ignoreCase = true)) {
            val instantPayload = AnimationPlayInstantPayload("stream", effectiveFit, 1.0f, name.trim())
            var count = 0
            for (p in eligibleTargets) {
                if (ServerPlayNetworking.canSend(p, AnimationPlayInstantPayload.TYPE)) {
                    ServerPlayNetworking.send(p, instantPayload)
                    count++
                }
            }
            source.sendSuccess({ Component.literal("Stream directo enviado para $count jugador(es). (modo: $effectiveFit)") }, false)
            return count
        }

        val remoteUrl = ScreenAnimationStorage.remoteStreamUrl(name)
        if (remoteUrl != null && remoteUrl.substringBefore('?').substringBefore('#').lowercase().endsWith(".mp4")) {
            val instantPayload = AnimationPlayInstantPayload(name, effectiveFit, 1.0f, remoteUrl)
            var count = 0
            for (p in eligibleTargets) {
                if (ServerPlayNetworking.canSend(p, AnimationPlayInstantPayload.TYPE)) {
                    ServerPlayNetworking.send(p, instantPayload)
                    count++
                }
            }
            source.sendSuccess({
                Component.literal("Animacion '$name' (streaming directo GitHub) enviada para $count jugador(es). (modo: $effectiveFit)")
            }, false)
            return count
        }

        val loadedResult = runCatching { ScreenAnimationStorage.load(name, overrideFit) }
        val animation = loadedResult.getOrNull()
        if (animation == null) {
            if (eligibleTargets.isNotEmpty() && ScreenAnimationStorage.isRemoteOrPrewarmed(name, eligibleTargets)) {
                val instantPayload = AnimationPlayInstantPayload(name, effectiveFit, 1.0f, remoteUrl.orEmpty())
                var count = 0
                for (p in eligibleTargets) {
                    if (ServerPlayNetworking.canSend(p, AnimationPlayInstantPayload.TYPE)) {
                        ServerPlayNetworking.send(p, instantPayload)
                        count++
                    }
                }
                source.sendSuccess({
                    Component.literal("Animacion '$name' enviada para $count jugador(es). (modo: $effectiveFit)")
                }, false)
                return count
            }
            source.sendFailure(Component.literal(loadedResult.exceptionOrNull()?.message ?: "No se pudo cargar la animacion."))
            return 0
        }

        val (instantTargets, streamTargets) = eligibleTargets.partition {
            ScreenAnimationStorage.isPlayerPrewarmed(it.uuid, animation.name)
        }

        var count = 0
        if (instantTargets.isNotEmpty()) {
            val instantPayload = AnimationPlayInstantPayload(animation.name, animation.fit, animation.scale)
            for (p in instantTargets) {
                if (ServerPlayNetworking.canSend(p, AnimationPlayInstantPayload.TYPE)) {
                    ServerPlayNetworking.send(p, instantPayload)
                    count++
                }
            }
        }

        if (streamTargets.isNotEmpty()) {
            count += ScreenAnimationStorage.send(source.server, streamTargets, animation, prewarmOnly = false)
        }

        val instantMsg = if (instantTargets.isNotEmpty()) " (${instantTargets.size} al instante desde cache precalentada)" else ""
        val summary = if (animation.isVideo) {
            "Animacion MP4 (WaterMedia) '${animation.name}' sincronizada para $count jugador(es)$instantMsg. (modo: ${animation.fit})"
        } else {
            "Animacion '${animation.name}' sincronizada para $count jugador(es)$instantMsg. (${animation.frameCount} frames a ${animation.fps} FPS, modo: ${animation.fit})"
        }
        source.sendSuccess({ Component.literal(summary) }, false)
        return count
    }

    private fun prewarm(
        source: net.minecraft.commands.CommandSourceStack,
        targets: List<ServerPlayer>,
        name: String,
        overrideFit: String? = null
    ): Int = runCatching { ScreenAnimationStorage.load(name, overrideFit) }
        .onFailure { source.sendFailure(Component.literal(it.message ?: "No se pudo cargar la animación.")) }
        .getOrNull()?.let { animation ->
            val sent = ScreenAnimationStorage.send(source.server, targets, animation, prewarmOnly = true)
            source.sendSuccess({ Component.literal("§a[Animaciones] Precalentando '${animation.name}' en memoria para $sent jugador(es)... Al reproducirse aparecerá al instante.") }, false)
            if (sent == 0) 0 else sent
        } ?: 0

    private fun clearCache(source: net.minecraft.commands.CommandSourceStack, targets: List<ServerPlayer>): Int {
        val payload = AnimationClearCachePayload("")
        targets.forEach { player ->
            ScreenAnimationStorage.clearPrewarmedFor(player.uuid)
            if (ServerPlayNetworking.canSend(player, AnimationClearCachePayload.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
        source.sendSuccess({ Component.literal("§a[Animaciones] Memoria y caché de texturas liberadas para ${targets.size} jugador(es).") }, false)
        return targets.size
    }

    private fun stop(source: net.minecraft.commands.CommandSourceStack, targets: List<ServerPlayer>): Int {
        ScreenAnimationStorage.cancelFor(targets)
        targets.forEach { player ->
            if (ServerPlayNetworking.canSend(player, AnimationStopPayload.TYPE)) {
                ServerPlayNetworking.send(player, AnimationStopPayload)
            }
        }
        source.sendSuccess({ Component.literal("Animación detenida para ${targets.size} jugador(es).") }, false)
        return targets.size
    }

    companion object {
        const val MOD_ID = "desgraciamon4_screen_animations"
        internal val logger = LoggerFactory.getLogger(MOD_ID)
    }
}

internal data class PreparedAnimation(
    val name: String,
    val fps: Int,
    val scale: Float,
    val fit: String,
    val soundsJson: String,
    val frameCount: Int,
    val archive: ByteArray,
    val isVideo: Boolean = false
)

data class SoundCue(
    var sound: String = "",
    var frame: Int = 0,
    var volume: Float = 1.0f,
    var pitch: Float = 1.0f
)

internal data class ServerAnimationSession(
    val transferId: UUID,
    val animationName: String,
    val targetUuids: Set<UUID>,
    val archive: ByteArray,
    val chunkSize: Int,
    val chunkCount: Int,
    val prewarmOnly: Boolean = false,
    var nextChunkIndex: Int = 0,
    val readyUuids: MutableSet<UUID> = mutableSetOf(),
    val expiresAt: Long = System.currentTimeMillis() + 60_000L
)

data class AnimationDefinition(
    var fps: Int = 30,
    var scale: Float? = null,
    var fit: String? = null,
    var modo: String? = null,
    var sound: String? = null,
    var soundVolume: Float = 1.0f,
    var soundPitch: Float = 1.0f,
    var soundFrame: Int = 0,
    var sounds: List<SoundCue>? = null,
    var precalentar: Boolean = false,
    var preload: Boolean = false,
    var precargar: Boolean = false
)

internal object ScreenAnimationStorage {
    private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
    private const val MAX_FRAMES = 1000
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
    private const val MAX_DIMENSION = 4096
    private const val MAX_TOTAL_PIXELS = 600_000_000L
    private val namePattern = Regex("[a-z0-9_-]{1,32}")

    private val activeSessions = ConcurrentHashMap<UUID, ServerAnimationSession>()
    private val prewarmedPlayers = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val remoteAnimationNames = ConcurrentHashMap.newKeySet<String>()
    private val remoteAnimationUrls = ConcurrentHashMap<String, String>()
    internal val remoteIoLock = Any()
    private val remoteSyncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ScreenAnimations-ServerSync").apply { isDaemon = true }
    }
    private val httpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .build()

    private const val DEFAULT_REMOTE_MANIFEST_URL =
        "https://raw.githubusercontent.com/Dr3sh/desgraciamon-assets/main/animations/manifest.json"

    private data class ServerRemoteConfig(
        var enabled: Boolean = true,
        var manifestUrl: String = DEFAULT_REMOTE_MANIFEST_URL,
        var autoSyncOnJoin: Boolean = true
    )

    private data class ServerManifestEntry(
        var name: String = "",
        var url: String = "",
        var hash: String = "",
        var sizeBytes: Long = 0L
    )

    private data class ServerRemoteManifest(
        var version: Int = 1,
        var animations: List<ServerManifestEntry> = emptyList()
    )

    private data class ServerCacheState(
        var downloadedAnimations: MutableMap<String, String> = mutableMapOf()
    )

    fun isPlayerPrewarmed(playerUuid: UUID, animName: String): Boolean {
        return prewarmedPlayers[animName]?.contains(playerUuid) == true
    }

    fun isRemoteOrPrewarmed(name: String, targets: List<ServerPlayer>): Boolean {
        return remoteAnimationNames.contains(name) || targets.any { isPlayerPrewarmed(it.uuid, name) }
    }

    fun remoteStreamUrl(name: String): String? = remoteAnimationUrls[name]

    fun markPrewarmed(playerUuid: UUID, animName: String) {
        prewarmedPlayers.computeIfAbsent(animName) { ConcurrentHashMap.newKeySet() }.add(playerUuid)
    }

    fun clearPrewarmedFor(playerUuid: UUID) {
        prewarmedPlayers.values.forEach { it.remove(playerUuid) }
    }

    fun clearAllPrewarmed() {
        prewarmedPlayers.clear()
    }

    private fun withCacheBuster(url: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        return "${url}${sep}cb=${System.currentTimeMillis()}"
    }

    fun syncRemoteOnServer(server: MinecraftServer? = null) {
        remoteSyncExecutor.execute {
            runCatching {
                synchronized(remoteIoLock) {
                    val baseDir = net.fabricmc.loader.api.FabricLoader.getInstance().configDir
                        .resolve(ScreenAnimationsMod.MOD_ID)
                    java.nio.file.Files.createDirectories(baseDir)
                    val remoteFile = baseDir.resolve("remote.json")
                    val cfg = if (java.nio.file.Files.isRegularFile(remoteFile)) {
                        java.nio.file.Files.newBufferedReader(remoteFile).use {
                            gson.fromJson(it, ServerRemoteConfig::class.java)
                        } ?: ServerRemoteConfig()
                    } else {
                        ServerRemoteConfig().also { java.nio.file.Files.writeString(remoteFile, gson.toJson(it)) }
                    }
                    if (cfg.manifestUrl.isBlank() || cfg.manifestUrl.contains("usuario/repo")) {
                        cfg.manifestUrl = DEFAULT_REMOTE_MANIFEST_URL
                        java.nio.file.Files.writeString(remoteFile, gson.toJson(cfg))
                    }
                    val manifestUrl = cfg.manifestUrl.trim()
                    if (!cfg.enabled || manifestUrl.isBlank()) return@synchronized

                    val request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(withCacheBuster(manifestUrl)))
                        .timeout(java.time.Duration.ofSeconds(15))
                        .header("User-Agent", "DesgraciaMon4-ScreenAnimations/1.0")
                        .header("Cache-Control", "no-cache, no-store")
                        .header("Pragma", "no-cache")
                        .GET()
                        .build()
                    val response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() !in 200..299) {
                        ScreenAnimationsMod.logger.warn("[ServerRemoteSync] HTTP ${response.statusCode()} al consultar manifest")
                        return@synchronized
                    }

                    val manifest = gson.fromJson(response.body(), ServerRemoteManifest::class.java) ?: ServerRemoteManifest()
                    val remoteEntries = manifest.animations.filter { it.name.isNotBlank() && it.url.isNotBlank() && namePattern.matches(it.name) }
                    remoteAnimationNames.clear()
                    remoteAnimationUrls.clear()
                    remoteEntries.forEach {
                        remoteAnimationNames.add(it.name)
                        remoteAnimationUrls[it.name] = it.url.trim()
                    }

                    val cacheFile = baseDir.resolve("remote_cache.json")
                    val cacheState = if (java.nio.file.Files.isRegularFile(cacheFile)) {
                        runCatching {
                            java.nio.file.Files.newBufferedReader(cacheFile).use { gson.fromJson(it, ServerCacheState::class.java) }
                        }.getOrNull() ?: ServerCacheState()
                    } else ServerCacheState()

                    val animDir = animationPath()
                    java.nio.file.Files.createDirectories(animDir)
                    val remoteNamesSet = remoteEntries.map { it.name }.toSet()

                    for (oldName in cacheState.downloadedAnimations.keys.toList()) {
                        if (!remoteNamesSet.contains(oldName)) {
                            val oldFolder = animDir.resolve(oldName)
                            if (java.nio.file.Files.exists(oldFolder)) {
                                java.nio.file.Files.walk(oldFolder)
                                    .sorted(Comparator.reverseOrder())
                                    .forEach { java.nio.file.Files.deleteIfExists(it) }
                            }
                            cacheState.downloadedAnimations.remove(oldName)
                        }
                    }

                    for (entry in remoteEntries) {
                        val cleanUrl = entry.url.substringBefore('?').substringBefore('#').lowercase()
                        if (cleanUrl.endsWith(".mp4")) {
                            // MP4s from GitHub use direct WaterMedia HTTP streaming as primary method
                            continue
                        }
                        val targetFolder = animDir.resolve(entry.name)
                        val hasConfig = java.nio.file.Files.isRegularFile(targetFolder.resolve("animation.json"))
                        val hasMp4 = findVideoFile(entry.name) != null
                        val cachedHash = cacheState.downloadedAnimations[entry.name]
                        if ((hasConfig || hasMp4) && cachedHash == entry.hash) {
                            continue
                        }
                        runCatching {
                            val assetReq = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(withCacheBuster(entry.url)))
                                .timeout(java.time.Duration.ofSeconds(60))
                                .header("User-Agent", "DesgraciaMon4-ScreenAnimations/1.0")
                                .header("Cache-Control", "no-cache, no-store")
                                .header("Pragma", "no-cache")
                                .GET()
                                .build()
                            val assetResp = httpClient.send(assetReq, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
                            if (assetResp.statusCode() !in 200..299) {
                                ScreenAnimationsMod.logger.warn("[ServerRemoteSync] HTTP ${assetResp.statusCode()} al descargar '${entry.name}'")
                                return@runCatching
                            }
                            val bytes = java.io.BufferedInputStream(assetResp.body()).use { it.readAllBytes() }
                            if (java.nio.file.Files.exists(targetFolder)) {
                                java.nio.file.Files.walk(targetFolder)
                                    .sorted(Comparator.reverseOrder())
                                    .forEach { java.nio.file.Files.deleteIfExists(it) }
                            }
                            java.nio.file.Files.createDirectories(targetFolder)

                            val cleanUrl = entry.url.substringBefore('?').substringBefore('#').lowercase()
                            val isMp4Header = bytes.size >= 8 &&
                                bytes[4] == 'f'.code.toByte() &&
                                bytes[5] == 't'.code.toByte() &&
                                bytes[6] == 'y'.code.toByte() &&
                                bytes[7] == 'p'.code.toByte()
                            val isZipHeader = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                            if (cleanUrl.endsWith(".mp4") || (isMp4Header && !isZipHeader)) {
                                java.nio.file.Files.write(targetFolder.resolve("video.mp4"), bytes)
                                val settingsFile = targetFolder.resolve("animation.json")
                                if (!java.nio.file.Files.exists(settingsFile)) {
                                    java.nio.file.Files.writeString(
                                        settingsFile,
                                        "{\n  \"fps\": 30,\n  \"scale\": 1.0,\n  \"fit\": \"rellenar\",\n  \"preload\": true\n}\n"
                                    )
                                }
                            } else {
                                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                                    while (true) {
                                        val zipEntry = zis.nextEntry ?: break
                                        if (!zipEntry.isDirectory) {
                                            val outFile = targetFolder.resolve(zipEntry.name).normalize()
                                            if (outFile.startsWith(targetFolder)) {
                                                java.nio.file.Files.createDirectories(outFile.parent)
                                                java.nio.file.Files.write(outFile, zis.readBytes())
                                            }
                                        }
                                        zis.closeEntry()
                                    }
                                }
                            }
                            cacheState.downloadedAnimations[entry.name] = entry.hash
                            java.nio.file.Files.writeString(cacheFile, gson.toJson(cacheState))
                            ScreenAnimationsMod.logger.info("[ServerRemoteSync] Sincronizada animacion '${entry.name}' en servidor.")
                        }.onFailure { err ->
                            ScreenAnimationsMod.logger.warn("[ServerRemoteSync] Error descargando '${entry.name}': ${err.message}")
                        }
                    }
                    java.nio.file.Files.writeString(cacheFile, gson.toJson(cacheState))
                }
            }.onFailure { err ->
                ScreenAnimationsMod.logger.warn("[ServerRemoteSync] Error en sincronizacion remota: ${err.message}")
            }
        }
    }

    fun onPlayerJoin(server: MinecraftServer, player: ServerPlayer) {
        val names = listAnimations()
        for (name in names) {
            runCatching {
                val folder = animationPath().resolve(name)
                val settingsFile = folder.resolve("animation.json")
                val hasMp4 = findVideoFile(name) != null
                if (java.nio.file.Files.isRegularFile(settingsFile)) {
                    val def = java.nio.file.Files.newBufferedReader(settingsFile).use { gson.fromJson(it, AnimationDefinition::class.java) }
                    if (def?.precalentar == true || def?.preload == true || def?.precargar == true || hasMp4) {
                        val anim = load(name)
                        if (anim.isVideo) {
                            // Las animaciones MP4 se sincronizan desde GitHub/caché local sin enviar el video por red al entrar
                            markPrewarmed(player.uuid, anim.name)
                        } else {
                            send(server, listOf(player), anim, prewarmOnly = true)
                        }
                    }
                } else if (hasMp4) {
                    markPrewarmed(player.uuid, name)
                }
            }
        }
    }

    private fun findVideoFile(name: String): Path? {
        val root = animationPath()
        val folder = root.resolve(name).normalize()
        if (folder.startsWith(root) && java.nio.file.Files.isDirectory(folder)) {
            val preferred = folder.resolve("video.mp4")
            if (java.nio.file.Files.isRegularFile(preferred)) return preferred
            java.nio.file.Files.list(folder).use { paths ->
                val found = paths.filter { java.nio.file.Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".mp4") }
                    .sorted()
                    .findFirst()
                    .orElse(null)
                if (found != null) return found
            }
        }
        val directMp4 = root.resolve("$name.mp4").normalize()
        if (directMp4.startsWith(root) && java.nio.file.Files.isRegularFile(directMp4)) {
            return directMp4
        }
        return null
    }

    fun animationPath(): Path = net.fabricmc.loader.api.FabricLoader.getInstance().configDir
        .resolve(ScreenAnimationsMod.MOD_ID)
        .resolve("animations")

    fun ensureCreated() {
        runCatching {
            val root = animationPath()
            java.nio.file.Files.createDirectories(root)
            val readme = root.resolve("README.txt")
            if (java.nio.file.Files.notExists(readme)) {
                java.nio.file.Files.writeString(
                    readme,
                    "Create one folder per animation, for example animations/welcome/.\n" +
                        "Inside it add animation.json and frame_000.png, frame_001.png, ...\n\n" +
                        "Optional audio:\n" +
                        "- Drop sound.ogg or audio.ogg directly in the folder for custom audio!\n" +
                        "- Or specify Minecraft/mod sound IDs in animation.json.\n\n" +
                        "animation.json example:\n" +
                        "{\n" +
                        "  \"fps\": 30,\n" +
                        "  \"scale\": 1.0,\n" +
                        "  \"fit\": \"rellenar\",\n" +
                        "  \"sound\": \"minecraft:entity.ender_dragon.growl\",\n" +
                        "  \"soundVolume\": 1.0,\n" +
                        "  \"soundPitch\": 1.0\n" +
                        "}\n\n" +
                        "Modos de ajuste ('fit' o 'modo'):\n" +
                        "- 'rellenar' o 'cover': Cubre el 100% de la pantalla adaptándose a cualquier resolución sin bordes negros.\n" +
                        "- 'ajustar' o 'contain': Ajusta la imagen dentro de los límites de la pantalla manteniendo proporción.\n" +
                        "- 'fill' o 'estirar': Estira la imagen para cubrir la pantalla exactamente.\n\n" +
                        "Supports frames 800x800 and higher (up to 4096x4096). All frames must have equal dimensions.\n" +
                        "Animations automatically scale and center to the player's screen without repeating or tiling.\n" +
                        "Use /animacion reproducir <nombre> [jugadores] [modo], or /animacion detener [@a].\n"
                )
            }
        }.onFailure { ScreenAnimationsMod.logger.error("Could not create screen animation directory", it) }
    }

    fun listAnimations(): List<String> {
        ensureCreated()
        return runCatching {
            val found = sortedSetOf<String>()
            found.addAll(remoteAnimationNames)
            java.nio.file.Files.list(animationPath()).use { paths ->
                paths.forEach { entry ->
                    val fileName = entry.fileName.toString()
                    if (java.nio.file.Files.isDirectory(entry) && namePattern.matches(fileName)) {
                        found.add(fileName)
                    } else if (java.nio.file.Files.isRegularFile(entry) && fileName.lowercase().endsWith(".mp4")) {
                        val base = fileName.dropLast(4)
                        if (namePattern.matches(base)) found.add(base)
                    }
                }
            }
            found.toList()
        }.getOrDefault(remoteAnimationNames.sorted())
    }

    fun normalizeFitMode(rawFit: String): String {
        return when (rawFit.lowercase().trim()) {
            "rellenar", "cover", "fill_screen", "pantalla_completa", "fullscreen" -> "rellenar"
            "fill", "stretch", "estirar" -> "fill"
            "ajustar", "contain", "fit" -> "contain"
            else -> "contain"
        }
    }

    fun load(name: String, overrideFit: String? = null): PreparedAnimation {
        require(namePattern.matches(name)) { "Nombre invalido. Usa letras minusculas, numeros, guion o guion bajo." }
        ensureCreated()
        val folder = animationPath().resolve(name).normalize()
        val videoFile = findVideoFile(name)
        val hasFolder = folder.startsWith(animationPath()) && java.nio.file.Files.isDirectory(folder)
        require(hasFolder || videoFile != null) { "No existe la animacion '$name'." }
        val settingsFile = if (hasFolder) folder.resolve("animation.json") else animationPath().resolve("$name.json")
        if (videoFile == null) {
            require(java.nio.file.Files.isRegularFile(settingsFile)) { "Falta animation.json en la carpeta '$name'." }
        }
        val settings = if (java.nio.file.Files.isRegularFile(settingsFile)) {
            java.nio.file.Files.newBufferedReader(settingsFile).use { gson.fromJson(it, AnimationDefinition::class.java) ?: AnimationDefinition() }
        } else {
            AnimationDefinition(fps = 30, fit = "rellenar", preload = true)
        }
        require(settings.fps in 1..120) { "El FPS debe estar entre 1 y 120." }
        val rawFit = (overrideFit ?: settings.fit ?: settings.modo ?: if (videoFile != null) "rellenar" else "contain").lowercase().trim()
        val fit = normalizeFitMode(rawFit)
        val defaultScale = if (fit == "rellenar") 1.0f else (settings.scale ?: 1.0f)
        val scale = (settings.scale ?: defaultScale).coerceIn(0.05f, 2.0f)

        val soundCues = mutableListOf<SoundCue>()
        if (!settings.sound.isNullOrBlank()) {
            soundCues += SoundCue(
                sound = settings.sound!!.trim(),
                frame = settings.soundFrame.coerceAtLeast(0),
                volume = settings.soundVolume.coerceIn(0.0f, 2.0f),
                pitch = settings.soundPitch.coerceIn(0.1f, 2.0f)
            )
        }
        settings.sounds?.forEach { cue ->
            if (cue.sound.isNotBlank()) {
                soundCues += SoundCue(
                    sound = cue.sound.trim(),
                    frame = cue.frame.coerceAtLeast(0),
                    volume = cue.volume.coerceIn(0.0f, 2.0f),
                    pitch = cue.pitch.coerceIn(0.1f, 2.0f)
                )
            }
        }
        val soundsJson = if (soundCues.isNotEmpty()) gson.toJson(soundCues) else ""

        val audioFile = if (hasFolder) {
            listOf("sound.ogg", "audio.ogg").map { folder.resolve(it) }.firstOrNull { java.nio.file.Files.isRegularFile(it) }
        } else null

        if (videoFile != null) {
            val archiveBytes = java.io.ByteArrayOutputStream()
            val videoBytes = java.nio.file.Files.readAllBytes(videoFile)
            require(videoBytes.size <= MAX_ARCHIVE_BYTES) { "El archivo MP4 supera el límite de 128 MB." }
            java.util.zip.ZipOutputStream(archiveBytes).use { zip ->
                if (audioFile != null) {
                    val audioBytes = java.nio.file.Files.readAllBytes(audioFile)
                    require(audioBytes.size <= 16 * 1024 * 1024) { "El archivo de audio supera los 16 MB." }
                    zip.putNextEntry(java.util.zip.ZipEntry("audio.ogg"))
                    zip.write(audioBytes)
                    zip.closeEntry()
                }
                zip.putNextEntry(java.util.zip.ZipEntry("video.mp4"))
                zip.write(videoBytes)
                zip.closeEntry()
            }
            val archive = archiveBytes.toByteArray()
            return PreparedAnimation(name, settings.fps, scale, fit, soundsJson, 0, archive, isVideo = true)
        }

        val frames = java.nio.file.Files.list(folder).use { paths ->
            paths.filter { java.nio.file.Files.isRegularFile(it) && it.fileName.toString().matches(Regex("frame_\\d{3,6}\\.png")) }
                .sorted()
                .toList()
        }
        require(frames.isNotEmpty()) { "No se encontraron frames frame_000.png ni video.mp4." }
        require(frames.size <= MAX_FRAMES) { "La animación supera el límite de $MAX_FRAMES frames." }

        val archiveBytes = java.io.ByteArrayOutputStream()
        var totalPixels = 0L
        var expectedWidth = -1
        var expectedHeight = -1
        var rawBytes = 0L
        java.util.zip.ZipOutputStream(archiveBytes).use { zip ->
            if (audioFile != null) {
                val audioBytes = java.nio.file.Files.readAllBytes(audioFile)
                require(audioBytes.size <= 16 * 1024 * 1024) { "El archivo de audio supera los 16 MB." }
                rawBytes += audioBytes.size
                zip.putNextEntry(java.util.zip.ZipEntry("audio.ogg"))
                zip.write(audioBytes)
                zip.closeEntry()
            }

            frames.forEach { frame ->
                val bytes = java.nio.file.Files.readAllBytes(frame)
                require(bytes.size <= MAX_FRAME_BYTES) { "${frame.fileName} supera el máximo de 8 MB." }
                rawBytes += bytes.size
                require(rawBytes <= MAX_ARCHIVE_BYTES) { "La secuencia supera el límite total de 64 MB." }
                val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                    ?: throw IllegalArgumentException("${frame.fileName} no es un PNG válido.")
                try {
                    require(image.width in 1..MAX_DIMENSION && image.height in 1..MAX_DIMENSION) { "Cada frame debe medir como máximo ${MAX_DIMENSION}x$MAX_DIMENSION." }
                    if (expectedWidth < 0) {
                        expectedWidth = image.width
                        expectedHeight = image.height
                    }
                    require(image.width == expectedWidth && image.height == expectedHeight) { "Todos los frames deben tener las mismas dimensiones." }
                    totalPixels += image.width.toLong() * image.height
                    require(totalPixels <= MAX_TOTAL_PIXELS) { "La animación excede el límite de píxeles totales." }
                } finally {
                    image.flush()
                }
                zip.putNextEntry(java.util.zip.ZipEntry(frame.fileName.toString()))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val archive = archiveBytes.toByteArray()
        require(archive.size <= MAX_ARCHIVE_BYTES) { "La secuencia comprimida supera el límite de 64 MB." }
        return PreparedAnimation(name, settings.fps, scale, fit, soundsJson, frames.size, archive)
    }

    fun send(server: MinecraftServer, targets: List<ServerPlayer>, animation: PreparedAnimation, prewarmOnly: Boolean = false): Int {
        val eligibleTargets = targets.distinctBy { it.uuid }.filter { player ->
            ServerPlayNetworking.canSend(player, AnimationTransferStartPayload.TYPE) &&
            ServerPlayNetworking.canSend(player, AnimationTransferChunkPayload.TYPE)
        }
        if (eligibleTargets.isEmpty()) return 0

        val chunkSize = AnimationTransferChunkPayload.MAX_CHUNK_BYTES
        val chunkCount = (animation.archive.size + chunkSize - 1) / chunkSize
        val transferId = UUID.randomUUID()

        val session = ServerAnimationSession(
            transferId = transferId,
            animationName = animation.name,
            targetUuids = eligibleTargets.map { it.uuid }.toSet(),
            archive = animation.archive,
            chunkSize = chunkSize,
            chunkCount = chunkCount,
            prewarmOnly = prewarmOnly
        )
        activeSessions[transferId] = session

        val startPayload = AnimationTransferStartPayload(
            transferId,
            animation.name,
            animation.fps,
            animation.frameCount,
            chunkCount,
            animation.archive.size,
            animation.scale,
            animation.fit,
            animation.soundsJson,
            prewarmOnly
        )

        for (player in eligibleTargets) {
            ServerPlayNetworking.send(player, startPayload)
        }
        return eligibleTargets.size
    }

    fun onPlayerReady(server: MinecraftServer, playerUuid: UUID, payload: AnimationReadyPayload) {
        val session = activeSessions[payload.transferId]
        if (session != null && session.targetUuids.contains(playerUuid)) {
            session.readyUuids.add(playerUuid)
            if (payload.prewarmed) {
                markPrewarmed(playerUuid, session.animationName)
            }
            if (session.prewarmOnly || payload.prewarmed) {
                if (session.readyUuids.containsAll(session.targetUuids)) {
                    activeSessions.remove(session.transferId)
                }
            } else {
                checkAndTrigger(server, session)
            }
        } else if (payload.animationName.isNotBlank() && payload.prewarmed) {
            markPrewarmed(playerUuid, payload.animationName)
        }
    }

    fun tick(server: MinecraftServer) {
        if (activeSessions.isEmpty()) return
        val now = System.currentTimeMillis()
        val iterator = activeSessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            val onlineTargets = session.targetUuids.mapNotNull { server.playerList.getPlayer(it) }
            if (onlineTargets.isEmpty()) {
                iterator.remove()
                continue
            }

            // Stream chunks progressively per tick
            if (session.nextChunkIndex < session.chunkCount) {
                // When pre-warming: 1 chunk per tick (256KB/tick = 5MB/s) - ZERO network lag!
                // When playing directly: 3 chunks per tick (768KB/tick = 15MB/s)
                val chunksThisTick = if (session.prewarmOnly) 1 else 3
                var sentThisTick = 0
                while (sentThisTick < chunksThisTick && session.nextChunkIndex < session.chunkCount) {
                    val index = session.nextChunkIndex
                    val start = index * session.chunkSize
                    val end = minOf(start + session.chunkSize, session.archive.size)
                    val chunkPayload = AnimationTransferChunkPayload(
                        session.transferId,
                        index,
                        session.archive.copyOfRange(start, end)
                    )
                    for (player in onlineTargets) {
                        ServerPlayNetworking.send(player, chunkPayload)
                    }
                    session.nextChunkIndex++
                    sentThisTick++
                }
            } else {
                if (session.readyUuids.containsAll(session.targetUuids) || now >= session.expiresAt) {
                    iterator.remove()
                    if (!session.prewarmOnly) {
                        triggerSession(server, session)
                    }
                }
            }
        }
    }

    private fun checkAndTrigger(server: MinecraftServer, session: ServerAnimationSession) {
        val onlineTargets = session.targetUuids.filter { server.playerList.getPlayer(it) != null }.toSet()
        if (onlineTargets.isNotEmpty() && session.readyUuids.containsAll(onlineTargets)) {
            activeSessions.remove(session.transferId)
            triggerSession(server, session)
        }
    }

    private fun triggerSession(server: MinecraftServer, session: ServerAnimationSession) {
        val payload = AnimationPlayTriggerPayload(session.transferId)
        for (uuid in session.targetUuids) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            if (ServerPlayNetworking.canSend(player, AnimationPlayTriggerPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
    }

    fun cancelFor(targets: List<ServerPlayer>) {
        val targetSet = targets.map { it.uuid }.toSet()
        val iterator = activeSessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            if (session.targetUuids.any { targetSet.contains(it) }) {
                iterator.remove()
            }
        }
    }
}
