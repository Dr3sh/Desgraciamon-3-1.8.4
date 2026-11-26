package com.desgraciamon.serverassets

import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.resources.ResourceLocation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class ServerAssetsClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ServerAssetsManifestPayload.TYPE) { payload, context ->
            context.client().execute {
                ServerAssetsClientState.handleManifest(payload.entries)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(ServerAssetChunkPayload.TYPE) { payload, context ->
            context.client().execute {
                ServerAssetsClientState.handleChunk(payload)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(PlayServerSoundPayload.TYPE) { payload, context ->
            context.client().execute {
                ServerAssetsClientState.handlePlaySound(payload.id, payload.volume)
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ServerAssetsClientState.reset()
            ServerAssetsAudioPlayer.stopAll()
        }
    }
}

object ServerAssetsClientState {
    private const val PUA_BASE = 0xE900
    private const val PUA_MAX = 0xEFFF
    private val EMOJI_TOKEN_REGEX = Regex(":([a-zA-Z0-9_/-]{1,64}):")

    private data class PendingTransfer(
        val kind: String,
        val id: String,
        val ext: String,
        val sha256: String,
        val totalChunks: Int,
        val chunks: Array<ByteArray?> = arrayOfNulls(totalChunks)
    ) {
        fun receivedCount(): Int = chunks.count { it != null }
    }

    private val activeSounds = ConcurrentHashMap<String, ServerAssetManifestEntry>()
    private val activeEmojis = ConcurrentHashMap<String, ServerAssetManifestEntry>()
    private val pendingTransfers = ConcurrentHashMap<String, PendingTransfer>()
    private val pendingSoundPlays = ConcurrentHashMap<String, Float>()

    private val emojiNameToCodepoint = ConcurrentHashMap<String, Int>()
    private val codepointToTexture = ConcurrentHashMap<Int, ResourceLocation>()
    private val emojiNameToTexture = ConcurrentHashMap<String, ResourceLocation>()
    private val registeredTextures = ConcurrentHashMap<String, DynamicTexture>()

    private fun cacheRoot(): Path =
        FabricLoader.getInstance().configDir.resolve("desgraciamon4_core").resolve("server_cache")

    private fun cacheFile(kind: String, sha256: String, ext: String): Path {
        val sub = if (kind == ServerAssetsManager.KIND_SOUND) "sounds" else "emojis"
        val safeSha = sha256.lowercase().replace(Regex("[^a-f0-9]"), "").take(64)
        val safeExt = ext.lowercase().replace(Regex("[^a-z0-9]"), "").take(8)
        return cacheRoot().resolve(sub).resolve("$safeSha.$safeExt")
    }

    fun handleManifest(entries: List<ServerAssetManifestEntry>) {
        val nextSounds = LinkedHashMap<String, ServerAssetManifestEntry>()
        val nextEmojis = LinkedHashMap<String, ServerAssetManifestEntry>()
        for (entry in entries) {
            when (entry.kind) {
                ServerAssetsManager.KIND_SOUND -> nextSounds[entry.id.lowercase()] = entry
                ServerAssetsManager.KIND_EMOJI -> nextEmojis[entry.id.lowercase()] = entry
            }
        }

        activeSounds.clear()
        activeSounds.putAll(nextSounds)
        activeEmojis.clear()
        activeEmojis.putAll(nextEmojis)
        pendingTransfers.clear()

        // Release emoji textures that are no longer in the manifest
        val staleEmojis = emojiNameToTexture.keys.filter { it !in nextEmojis.keys }
        for (stale in staleEmojis) {
            releaseEmojiTexture(stale)
        }

        val missingKeys = ArrayList<String>()
        for (entry in entries) {
            val cached = cacheFile(entry.kind, entry.sha256, entry.ext)
            val valid = isCachedFileValid(cached, entry.sha256)
            if (valid) {
                if (entry.kind == ServerAssetsManager.KIND_EMOJI) {
                    runCatching {
                        val bytes = Files.readAllBytes(cached)
                        registerEmojiTexture(entry.id.lowercase(), bytes)
                    }
                }
            } else {
                missingKeys.add(entry.key())
            }
        }

        if (missingKeys.isNotEmpty() && ClientPlayNetworking.canSend(ServerAssetRequestPayload.TYPE)) {
            missingKeys.chunked(ServerAssetRequestPayload.MAX_KEYS).forEach { batch ->
                ClientPlayNetworking.send(ServerAssetRequestPayload(batch))
            }
        }
    }

    fun handleChunk(payload: ServerAssetChunkPayload) {
        if (payload.chunkIndex < 0 || payload.chunkIndex >= payload.totalChunks || payload.totalChunks > 64) return
        val transferKey = "${payload.kind}:${payload.id}:${payload.sha256}"
        val transfer = pendingTransfers.computeIfAbsent(transferKey) {
            PendingTransfer(payload.kind, payload.id, payload.ext, payload.sha256, payload.totalChunks)
        }
        transfer.chunks[payload.chunkIndex] = payload.data

        if (transfer.receivedCount() < transfer.totalChunks) return
        pendingTransfers.remove(transferKey)

        val out = ByteArrayOutputStream()
        for (chunk in transfer.chunks) {
            if (chunk == null) return
            out.write(chunk)
        }
        val fullBytes = out.toByteArray()
        val actualSha = ServerAssetsManager.sha256Hex(fullBytes)
        if (!actualSha.equals(transfer.sha256, ignoreCase = true)) {
            ServerAssetsManager.LOGGER.warn("[ServerAssets] SHA-256 invalido para {}:{}, descartando.", transfer.kind, transfer.id)
            return
        }

        val target = cacheFile(transfer.kind, transfer.sha256, transfer.ext)
        runCatching {
            Files.createDirectories(target.parent)
            val tmp = target.resolveSibling("${target.fileName}.tmp")
            Files.write(tmp, fullBytes)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            ServerAssetsManager.LOGGER.warn("[ServerAssets] No se pudo guardar cache {}: {}", target, it.message)
        }

        if (transfer.kind == ServerAssetsManager.KIND_EMOJI) {
            registerEmojiTexture(transfer.id.lowercase(), fullBytes)
        } else if (transfer.kind == ServerAssetsManager.KIND_SOUND) {
            val pendingVol = pendingSoundPlays.remove(transfer.id.lowercase())
            if (pendingVol != null) {
                ServerAssetsAudioPlayer.play(target, transfer.ext, pendingVol)
            }
        }
    }

    fun handlePlaySound(rawId: String, volume: Float) {
        val id = ServerAssetsManager.sanitizeId(rawId)
        val entry = activeSounds[id] ?: return
        val file = cacheFile(entry.kind, entry.sha256, entry.ext)
        if (isCachedFileValid(file, entry.sha256)) {
            ServerAssetsAudioPlayer.play(file, entry.ext, volume)
        } else if (ClientPlayNetworking.canSend(ServerAssetRequestPayload.TYPE)) {
            pendingSoundPlays[id] = volume
            ClientPlayNetworking.send(ServerAssetRequestPayload(listOf(entry.key())))
        }
    }

    private fun isCachedFileValid(path: Path, expectedSha256: String): Boolean {
        if (!Files.isRegularFile(path)) return false
        return runCatching {
            val bytes = Files.readAllBytes(path)
            ServerAssetsManager.sha256Hex(bytes).equals(expectedSha256, ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun registerEmojiTexture(id: String, pngBytes: ByteArray) {
        if (!ServerAssetsManager.isValidPng(pngBytes)) return
        val client = Minecraft.getInstance()
        val texManager = client.textureManager
        runCatching {
            val image = NativeImage.read(ByteArrayInputStream(pngBytes))
            val loc = ResourceLocation.fromNamespaceAndPath("desgraciamon_core", "server_emoji/$id")
            registeredTextures.remove(id)?.let { old -> runCatching { old.close() } }
            val dynTex = DynamicTexture(image)
            texManager.register(loc, dynTex)
            registeredTextures[id] = dynTex
            emojiNameToTexture[id] = loc

            val codepoint = emojiNameToCodepoint.computeIfAbsent(id) { allocateCodepoint() }
            if (codepoint in PUA_BASE..PUA_MAX) {
                codepointToTexture[codepoint] = loc
            }
        }.onFailure {
            ServerAssetsManager.LOGGER.warn("[ServerAssets] Error decodificando emoji PNG '{}': {}", id, it.message)
        }
    }

    private fun allocateCodepoint(): Int {
        val used = emojiNameToCodepoint.values.toHashSet()
        for (cp in PUA_BASE..PUA_MAX) {
            if (cp !in used) return cp
        }
        return -1
    }

    private fun releaseEmojiTexture(id: String) {
        val texManager = Minecraft.getInstance().textureManager
        emojiNameToTexture.remove(id)?.let { loc -> runCatching { texManager.release(loc) } }
        registeredTextures.remove(id)?.let { tex -> runCatching { tex.close() } }
        emojiNameToCodepoint.remove(id)?.let { cp -> codepointToTexture.remove(cp) }
    }

    fun reset() {
        activeSounds.clear()
        activeEmojis.clear()
        pendingTransfers.clear()
        pendingSoundPlays.clear()
        for (id in emojiNameToTexture.keys.toList()) {
            releaseEmojiTexture(id)
        }
        emojiNameToCodepoint.clear()
        codepointToTexture.clear()
    }

    private val dynamicBadgeIds = ConcurrentHashMap.newKeySet<String>()

    fun syncDynamicBadgeEmojis(textures: Map<String, ResourceLocation>) {
        val normalized = textures.entries.associate { it.key.lowercase().trim() to it.value }
        for (oldId in dynamicBadgeIds.toList()) {
            if (!normalized.containsKey(oldId)) {
                dynamicBadgeIds.remove(oldId)
                if (!emojiNameToTexture.containsKey(oldId)) {
                    emojiNameToCodepoint.remove(oldId)?.let { cp -> codepointToTexture.remove(cp) }
                }
            }
        }
        for ((id, loc) in normalized) {
            if (id.isEmpty()) continue
            dynamicBadgeIds.add(id)
            val cp = emojiNameToCodepoint.computeIfAbsent(id) { allocateCodepoint() }
            codepointToTexture[cp] = loc
        }
    }

    @JvmStatic
    fun getEmojiTextureForCodepoint(codePoint: Int): ResourceLocation? {
        if (codePoint < PUA_BASE || codePoint > PUA_MAX) return null
        return codepointToTexture[codePoint]
    }

    @JvmStatic
    fun hasAnyEmojis(): Boolean = emojiNameToCodepoint.isNotEmpty()

    @JvmStatic
    fun processEmojis(component: Component): Component {
        if (emojiNameToCodepoint.isEmpty()) return component
        if (!EMOJI_TOKEN_REGEX.containsMatchIn(component.string)) return component
        return transformComponent(component)
    }

    private fun transformComponent(component: Component): MutableComponent {
        val hover = component.style.hoverEvent
        val updatedStyle = if (hover != null && hover.action == net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT) {
            val hoverComp = hover.getValue(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT)
            if (hoverComp != null) {
                component.style.withHoverEvent(
                    net.minecraft.network.chat.HoverEvent(
                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                        transformComponent(hoverComp)
                    )
                )
            } else {
                component.style
            }
        } else {
            component.style
        }
        val contents = component.contents
        val result: MutableComponent = if (contents is PlainTextContents.LiteralContents) {
            replaceTokensInText(contents.text(), updatedStyle)
        } else {
            component.plainCopy().withStyle(updatedStyle)
        }
        for (sibling in component.siblings) {
            result.append(transformComponent(sibling))
        }
        return result
    }

    private fun replaceTokensInText(text: String, style: Style): MutableComponent {
        if (!text.contains(':')) {
            return Component.literal(text).withStyle(style)
        }
        val matches = EMOJI_TOKEN_REGEX.findAll(text).toList()
        if (matches.isEmpty()) {
            return Component.literal(text).withStyle(style)
        }
        var changed = false
        val out = Component.empty().withStyle(style)
        var cursor = 0
        for (match in matches) {
            val name = match.groupValues[1].lowercase()
            val cp = emojiNameToCodepoint[name]
            if (cp != null) {
                changed = true
                if (match.range.first > cursor) {
                    out.append(Component.literal(text.substring(cursor, match.range.first)).withStyle(style))
                }
                val glyphStr = String(Character.toChars(cp))
                out.append(Component.literal(glyphStr).withStyle(style.withColor(0xFFFFFF)))
                cursor = match.range.last + 1
            }
        }
        if (!changed) {
            return Component.literal(text).withStyle(style)
        }
        if (cursor < text.length) {
            out.append(Component.literal(text.substring(cursor)).withStyle(style))
        }
        return out
    }
}
