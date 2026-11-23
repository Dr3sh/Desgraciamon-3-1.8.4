package com.desgraciamon.serverassets

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ServerAssetsManager {
    const val KIND_SOUND = "sound"
    const val KIND_EMOJI = "emoji"

    private const val MAX_SOUND_FILE_BYTES = 512 * 1024
    private const val MAX_TOTAL_SOUND_BYTES = 8 * 1024 * 1024
    private const val MAX_EMOJI_FILE_BYTES = 32 * 1024
    private const val MAX_TOTAL_EMOJI_BYTES = 4 * 1024 * 1024
    private const val CHUNKS_PER_PLAYER_PER_TICK = 4

    private val ALLOWED_SOUND_EXTS = setOf("ogg", "wav", "mp3")
    private val ID_CLEAN_REGEX = Regex("[^a-z0-9_-]")
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    val LOGGER = LoggerFactory.getLogger("desgraciamon-server-assets")

    data class LoadedAsset(
        val entry: ServerAssetManifestEntry,
        val bytes: ByteArray
    )

    private val sounds = ConcurrentHashMap<String, LoadedAsset>()
    private val emojis = ConcurrentHashMap<String, LoadedAsset>()
    private val pendingOutbound = ConcurrentHashMap<UUID, ArrayDeque<ServerAssetChunkPayload>>()

    fun baseConfigDir(): Path = FabricLoader.getInstance().configDir.resolve("desgraciamon4_core")
    fun soundsDir(): Path = baseConfigDir().resolve("sounds")
    fun emojisDir(): Path = baseConfigDir().resolve("emojis")

    @Synchronized
    fun load() {
        val sDir = soundsDir()
        val eDir = emojisDir()
        runCatching { Files.createDirectories(sDir) }
        runCatching { Files.createDirectories(eDir) }

        val nextSounds = LinkedHashMap<String, LoadedAsset>()
        var totalSoundBytes = 0
        if (Files.isDirectory(sDir)) {
            Files.list(sDir).use { stream ->
                val files = stream.filter { Files.isRegularFile(it) }.sorted().toList()
                for (file in files) {
                    val fileName = file.fileName.toString()
                    val dot = fileName.lastIndexOf('.')
                    if (dot <= 0) continue
                    val ext = fileName.substring(dot + 1).lowercase()
                    if (ext !in ALLOWED_SOUND_EXTS) continue
                    val id = sanitizeId(fileName.substring(0, dot))
                    if (id.isEmpty()) continue

                    val size = runCatching { Files.size(file) }.getOrDefault(-1L)
                    if (size <= 0L || size > MAX_SOUND_FILE_BYTES) {
                        LOGGER.warn("[ServerAssets] Sonido omitido (tamano invalido {} bytes, max {}): {}", size, MAX_SOUND_FILE_BYTES, fileName)
                        continue
                    }
                    if (totalSoundBytes + size > MAX_TOTAL_SOUND_BYTES) {
                        LOGGER.warn("[ServerAssets] Limite total de sonidos alcanzado ({} MB), omitiendo {}", MAX_TOTAL_SOUND_BYTES / (1024 * 1024), fileName)
                        break
                    }
                    val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: continue
                    val sha = sha256Hex(bytes)
                    val entry = ServerAssetManifestEntry(KIND_SOUND, id, ext, sha, bytes.size)
                    nextSounds[id] = LoadedAsset(entry, bytes)
                    totalSoundBytes += bytes.size
                }
            }
        }

        val nextEmojis = LinkedHashMap<String, LoadedAsset>()
        var totalEmojiBytes = 0
        if (Files.isDirectory(eDir)) {
            Files.list(eDir).use { stream ->
                val files = stream.filter { Files.isRegularFile(it) }.sorted().toList()
                for (file in files) {
                    val fileName = file.fileName.toString()
                    val dot = fileName.lastIndexOf('.')
                    if (dot <= 0) continue
                    val ext = fileName.substring(dot + 1).lowercase()
                    if (ext != "png") continue
                    val id = sanitizeId(fileName.substring(0, dot))
                    if (id.isEmpty()) continue

                    val size = runCatching { Files.size(file) }.getOrDefault(-1L)
                    if (size <= 0L || size > MAX_EMOJI_FILE_BYTES) {
                        LOGGER.warn("[ServerAssets] Emoji omitido (tamano invalido {} bytes, max {}): {}", size, MAX_EMOJI_FILE_BYTES, fileName)
                        continue
                    }
                    if (totalEmojiBytes + size > MAX_TOTAL_EMOJI_BYTES) {
                        LOGGER.warn("[ServerAssets] Limite total de emojis alcanzado, omitiendo {}", fileName)
                        break
                    }
                    val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: continue
                    if (!isValidPng(bytes)) {
                        LOGGER.warn("[ServerAssets] Emoji omitido (cabecera PNG invalida): {}", fileName)
                        continue
                    }
                    val sha = sha256Hex(bytes)
                    val entry = ServerAssetManifestEntry(KIND_EMOJI, id, "png", sha, bytes.size)
                    nextEmojis[id] = LoadedAsset(entry, bytes)
                    totalEmojiBytes += bytes.size
                }
            }
        }

        sounds.clear()
        sounds.putAll(nextSounds)
        emojis.clear()
        emojis.putAll(nextEmojis)
        pendingOutbound.clear()

        LOGGER.info("[ServerAssets] Cargados {} sonidos ({} KB) y {} emojis ({} KB).", sounds.size, totalSoundBytes / 1024, emojis.size, totalEmojiBytes / 1024)
    }

    fun listSounds(): List<ServerAssetManifestEntry> = sounds.values.map { it.entry }.sortedBy { it.id }

    fun listEmojis(): List<ServerAssetManifestEntry> = emojis.values.map { it.entry }.sortedBy { it.id }

    fun hasSound(id: String): Boolean = sounds.containsKey(sanitizeId(id))

    fun manifestPayload(): ServerAssetsManifestPayload {
        val entries = ArrayList<ServerAssetManifestEntry>(sounds.size + emojis.size)
        sounds.values.forEach { entries.add(it.entry) }
        emojis.values.forEach { entries.add(it.entry) }
        entries.sortWith(compareBy({ it.kind }, { it.id }))
        return ServerAssetsManifestPayload(entries)
    }

    fun sendManifest(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, ServerAssetsManifestPayload.TYPE)) return
        ServerPlayNetworking.send(player, manifestPayload())
    }

    fun reloadAndSync(server: MinecraftServer): Pair<Int, Int> {
        load()
        val payload = manifestPayload()
        for (player in server.playerList.players) {
            if (ServerPlayNetworking.canSend(player, ServerAssetsManifestPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
        return sounds.size to emojis.size
    }

    fun handleAssetRequest(player: ServerPlayer, keys: List<String>) {
        if (!ServerPlayNetworking.canSend(player, ServerAssetChunkPayload.TYPE)) return
        val queue = pendingOutbound.computeIfAbsent(player.uuid) { ArrayDeque() }
        synchronized(queue) {
            for (rawKey in keys.take(ServerAssetRequestPayload.MAX_KEYS)) {
                val sep = rawKey.indexOf(':')
                if (sep <= 0) continue
                val kind = rawKey.substring(0, sep).lowercase()
                val id = sanitizeId(rawKey.substring(sep + 1))
                val asset = when (kind) {
                    KIND_SOUND -> sounds[id]
                    KIND_EMOJI -> emojis[id]
                    else -> null
                } ?: continue

                val bytes = asset.bytes
                val chunkSize = ServerAssetChunkPayload.CHUNK_SIZE
                val totalChunks = ((bytes.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
                for (idx in 0 until totalChunks) {
                    val start = idx * chunkSize
                    val end = minOf(start + chunkSize, bytes.size)
                    val slice = bytes.copyOfRange(start, end)
                    queue.addLast(
                        ServerAssetChunkPayload(
                            kind = asset.entry.kind,
                            id = asset.entry.id,
                            ext = asset.entry.ext,
                            sha256 = asset.entry.sha256,
                            chunkIndex = idx,
                            totalChunks = totalChunks,
                            data = slice
                        )
                    )
                }
            }
        }
    }

    fun tick(server: MinecraftServer) {
        if (pendingOutbound.isEmpty()) return
        val iterator = pendingOutbound.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, queue) = iterator.next()
            val player = server.playerList.getPlayer(uuid)
            if (player == null || !ServerPlayNetworking.canSend(player, ServerAssetChunkPayload.TYPE)) {
                iterator.remove()
                continue
            }
            var sent = 0
            synchronized(queue) {
                while (sent < CHUNKS_PER_PLAYER_PER_TICK && queue.isNotEmpty()) {
                    val chunk = queue.removeFirst()
                    ServerPlayNetworking.send(player, chunk)
                    sent++
                }
                if (queue.isEmpty()) {
                    iterator.remove()
                }
            }
        }
    }

    fun onDisconnect(playerUuid: UUID) {
        pendingOutbound.remove(playerUuid)
    }

    fun playSound(server: MinecraftServer, rawId: String, volume: Float = 1.0f, target: ServerPlayer? = null): Int {
        val id = sanitizeId(rawId)
        if (!sounds.containsKey(id)) return 0
        val payload = PlayServerSoundPayload(id, volume.coerceIn(0.0f, 2.0f))
        if (target != null) {
            if (ServerPlayNetworking.canSend(target, PlayServerSoundPayload.TYPE)) {
                ServerPlayNetworking.send(target, payload)
                return 1
            }
            return 0
        }
        var count = 0
        for (player in server.playerList.players) {
            if (ServerPlayNetworking.canSend(player, PlayServerSoundPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload)
                count++
            }
        }
        return count
    }

    fun sanitizeId(raw: String): String =
        raw.lowercase().trim().replace(ID_CLEAN_REGEX, "").take(64)

    fun isValidPng(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_MAGIC.size) return false
        for (i in PNG_MAGIC.indices) {
            if (bytes[i] != PNG_MAGIC[i]) return false
        }
        return true
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
