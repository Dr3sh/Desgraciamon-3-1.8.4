package com.desgraciamon.screenanimations

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.sounds.SoundEvent
import org.joml.Matrix4f
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

private fun isPauseMenu(screen: Screen?): Boolean {
    if (screen == null) return false
    return screen is PauseScreen ||
           screen.javaClass.name.startsWith("net.minecraft.client.gui.screens.options.")
}

class ScreenAnimationsClient : ClientModInitializer {
    override fun onInitializeClient() {
        val registeredScreens = Collections.newSetFromMap(WeakHashMap<Screen, Boolean>())

        ClientPlayNetworking.registerGlobalReceiver(AnimationTransferStartPayload.TYPE) { payload, context ->
            context.client().execute { ScreenAnimationClientState.begin(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(AnimationTransferChunkPayload.TYPE) { payload, context ->
            context.client().execute { ScreenAnimationClientState.accept(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(AnimationPlayTriggerPayload.TYPE) { payload, context ->
            context.client().execute { ScreenAnimationClientState.onTrigger(payload.transferId) }
        }
        ClientPlayNetworking.registerGlobalReceiver(AnimationPlayInstantPayload.TYPE) { payload, context ->
            context.client().execute { ScreenAnimationClientState.onInstantPlay(payload.animationName, payload.fitMode, payload.scale, payload.streamUrl) }
        }
        ClientPlayNetworking.registerGlobalReceiver(AnimationClearCachePayload.TYPE) { payload, context ->
            context.client().execute { ScreenAnimationClientState.clearCache(payload.animationName) }
        }
        ClientPlayNetworking.registerGlobalReceiver(AnimationStopPayload.TYPE) { _, context ->
            context.client().execute { ScreenAnimationClientState.stop() }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> ScreenAnimationClientState.clearAll() }
        RemoteAnimationsDownloader.onSyncComplete = {
            ScreenAnimationClientState.onRemoteSyncCompleted()
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            RemoteAnimationsDownloader.startSync(forced = false)
        }
        val share = FabricLoader.getInstance().objectShare
        share.put(
            "desgraciamon:prewarm_animations",
            java.util.function.BiConsumer<List<String>, Runnable> { names, cb ->
                ScreenAnimationClientState.prewarmAnimations(names) { cb.run() }
            }
        )
        share.put(
            "desgraciamon:stop_animations",
            Runnable {
                Minecraft.getInstance().execute {
                    ScreenAnimationClientState.stop()
                    ScreenAnimationClientState.unpinCinematicAnimations()
                }
            }
        )
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            ScreenAnimationClientState.tick(client)
        }
        HudRenderCallback.EVENT.register { graphics, _ ->
            if (!visualsHookActive) {
                if (Minecraft.getInstance().screen == null) {
                    ScreenAnimationClientState.render(graphics, isOnTop = true)
                }
                RemoteAnimationsDownloader.renderHud(graphics)
            }
        }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (!registeredScreens.add(screen)) return@register

            ScreenEvents.beforeRender(screen).register { renderedScreen, graphics, _, _, _ ->
                if (!visualsHookActive && Minecraft.getInstance().screen === renderedScreen && isPauseMenu(renderedScreen)) {
                    ScreenAnimationClientState.render(graphics, isOnTop = false)
                }
            }

            ScreenEvents.afterRender(screen).register { renderedScreen, graphics, _, _, _ ->
                if (!visualsHookActive && Minecraft.getInstance().screen === renderedScreen) {
                    if (!isPauseMenu(renderedScreen)) {
                        ScreenAnimationClientState.render(graphics, isOnTop = true)
                    }
                    RemoteAnimationsDownloader.renderHud(graphics)
                }
            }
        }
    }

    companion object {
        @Volatile
        @JvmStatic
        var visualsHookActive: Boolean = false

        @JvmStatic
        fun renderTopLayerFromVisuals(isOnTop: Boolean) {
            visualsHookActive = true
            val client = Minecraft.getInstance()
            val window = client.window ?: return
            val width = window.guiScaledWidth.toFloat()
            val height = window.guiScaledHeight.toFloat()

            val prevProj = Matrix4f(RenderSystem.getProjectionMatrix())
            val prevSort = RenderSystem.getVertexSorting()
            val mvStack = RenderSystem.getModelViewStack()
            mvStack.pushMatrix()

            val graphics = net.minecraft.client.gui.GuiGraphics(client, client.renderBuffers().bufferSource())
            try {
                mvStack.identity()
                RenderSystem.applyModelViewMatrix()

                val ortho = Matrix4f().setOrtho(0f, width, height, 0f, -1000f, 1000f)
                RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z)

                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
                RenderSystem.disableDepthTest()
                RenderSystem.depthMask(false)

                ScreenAnimationClientState.render(graphics, isOnTop = false)
                RemoteAnimationsDownloader.renderHud(graphics)
                graphics.flush()
            } finally {
                RenderSystem.disableBlend()
                RenderSystem.depthMask(true)
                RenderSystem.enableDepthTest()
                mvStack.popMatrix()
                RenderSystem.applyModelViewMatrix()
                RenderSystem.setProjectionMatrix(prevProj, prevSort)
            }
        }
    }
}

private data class PendingAnimation(
    val start: AnimationTransferStartPayload,
    val chunks: Array<ByteArray?>,
    var received: Int = 0,
    var receivedBytes: Int = 0
)

private data class AnimationTexture(
    val texture: ResourceLocation,
    val dynamicTexture: DynamicTexture,
    val width: Int,
    val height: Int
)

private data class PreparedPlayback(
    val transferId: UUID,
    val name: String,
    val frames: List<AnimationTexture>,
    val fps: Int,
    val scale: Float,
    val fitMode: String,
    val sounds: List<SoundCue>,
    val audioBytes: ByteArray?
)

private data class PrewarmedAnimation(
    val name: String,
    val frames: List<AnimationTexture>,
    val fps: Int,
    val scale: Float,
    val fitMode: String,
    val sounds: List<SoundCue>,
    val audioBytes: ByteArray?
)

private data class PlayingAnimation(
    val name: String,
    val frames: List<AnimationTexture>,
    val fps: Int,
    val scale: Float,
    val fitMode: String,
    val sounds: List<SoundCue>,
    val startedAtNanos: Long,
    var lastTriggeredFrame: Int = -1
)

private class SlicedUploadTask(
    val transferId: UUID,
    val animationName: String,
    val frameBytesList: List<ByteArray>,
    val fps: Int,
    val scale: Float,
    val fitMode: String,
    val sounds: List<SoundCue>,
    val audioBytes: ByteArray?,
    val prewarmOnly: Boolean,
    val textures: MutableList<AnimationTexture> = mutableListOf(),
    val decodedQueue: java.util.concurrent.ConcurrentLinkedQueue<NativeImage> = java.util.concurrent.ConcurrentLinkedQueue(),
    @Volatile var decodeFinished: Boolean = false,
    var currentIndex: Int = 0
)

private data class PrewarmedVideo(
    val name: String,
    val videoPath: Path,
    val fps: Int,
    val scale: Float,
    val fitMode: String,
    val sounds: List<SoundCue>,
    val audioBytes: ByteArray?
)

private data class PreparedVideoPlayback(
    val transferId: UUID,
    val name: String,
    val videoUri: URI,
    val fps: Int,
    val scale: Float,
    val fitMode: String,
    val sounds: List<SoundCue>,
    val audioBytes: ByteArray?
)

private class PlayingVideoAnimation(
    val name: String,
    val videoUri: URI,
    val fps: Int,
    val scale: Float,
    val fitMode: String,
    val sounds: List<SoundCue>,
    val audioBytes: ByteArray?,
    val createdAtMs: Long = System.currentTimeMillis(),
    var startedAtNanos: Long = System.nanoTime(),
    var lastTriggeredFrame: Int = -1,
    var hasRenderedFrame: Boolean = false,
    var audioStarted: Boolean = false,
    var playerHandle: Any? = null,
    var pendingMrlHandle: Any? = null,
    var lastAttemptMs: Long = 0L
)

private class WaterFramesTextureWrapper(private val glId: Int) : AbstractTexture() {
    override fun getId(): Int = glId
    override fun bind() {
        if (glId > 0) GlStateManager._bindTexture(glId)
    }
    override fun load(resourceManager: ResourceManager) {}
}

private object WaterMediaAnimBridge {
    private val mrlCache = ConcurrentHashMap<String, org.watermedia.api.player.MRL>()
    private val registeredTextures = ConcurrentHashMap<Int, ResourceLocation>()
    private val bgExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Desgraciamon-WaterMedia-Worker").apply { isDaemon = true }
    }

    fun prewarmMrl(videoPath: Path): Boolean = prewarmMrl(videoPath.toUri())

    fun prewarmMrl(uri: URI): Boolean {
        val key = uri.toString()
        val existing = mrlCache[key]
        if (existing != null) return existing.source(0) != null
        bgExecutor.execute {
            runCatching {
                val mrl = mrlCache.computeIfAbsent(key) { org.watermedia.api.player.PlayerAPI.mrl(uri) }
                mrl.source(0)
            }
        }
        return true
    }

    fun textureLocationFor(glTexId: Int): ResourceLocation {
        return registeredTextures.computeIfAbsent(glTexId) { id ->
            val loc = ResourceLocation.fromNamespaceAndPath(ScreenAnimationsMod.MOD_ID, "waterframes_video/$id")
            Minecraft.getInstance().textureManager.register(loc, WaterFramesTextureWrapper(id))
            loc
        }
    }

    fun tryStartOrPollTexture(state: PlayingVideoAnimation): Long? {
        val existing = state.playerHandle as? org.watermedia.api.player.MediaPlayer
        if (existing != null) {
            if (existing.error() || existing.ended() || existing.stopped()) {
                return -2L
            }
            if (state.hasRenderedFrame && !existing.playing() && !existing.buffering() && !existing.loading() && !existing.waiting()) {
                return -2L
            }
            val texId = existing.texture()
            if (texId > 0L) {
                if (!state.hasRenderedFrame) {
                    state.hasRenderedFrame = true
                    state.startedAtNanos = System.nanoTime()
                }
                return texId
            }
            return 0L
        }

        val now = System.currentTimeMillis()
        if (now - state.createdAtMs > 8000L && state.lastAttemptMs > 0L) {
            return -2L
        }
        if (now - state.lastAttemptMs < 40L && state.lastAttemptMs > 0L) {
            return 0L
        }
        state.lastAttemptMs = now

        val uri = state.videoUri
        val key = uri.toString()
        val mrl = (state.pendingMrlHandle as? org.watermedia.api.player.MRL) ?: mrlCache[key]
        if (mrl == null) {
            bgExecutor.execute {
                runCatching {
                    val resolved = mrlCache.computeIfAbsent(key) { org.watermedia.api.player.PlayerAPI.mrl(uri) }
                    resolved.source(0)
                    state.pendingMrlHandle = resolved
                }
            }
            return 0L
        }
        state.pendingMrlHandle = mrl

        if (mrl.source(0) == null) {
            return 0L
        }

        return runCatching {
            val mc = Minecraft.getInstance()
            val renderThread = if (RenderSystem.isOnRenderThread()) Thread.currentThread() else null
            val useExternalAudio = state.audioBytes != null && state.audioBytes.isNotEmpty()
            val player = org.watermedia.api.player.PlayerAPI.player(
                mrl,
                { org.watermedia.api.player.PlayerAPI.glEngine(renderThread, mc) },
                { if (useExternalAudio) null else runCatching { org.watermedia.api.player.PlayerAPI.alEngine() }.getOrNull() }
            ) ?: return@runCatching -2L

            player.repeat(false)
            if (useExternalAudio) {
                player.mute(true)
                player.volume(0)
            } else {
                player.mute(false)
                player.volume(100)
            }
            player.start()
            state.playerHandle = player
            state.startedAtNanos = System.nanoTime()
            if (useExternalAudio && !state.audioStarted) {
                state.audioStarted = true
                CustomAudioPlayer.playOgg(state.audioBytes!!)
            }
            0L
        }.getOrElse {
            ScreenAnimationsMod.logger.warn("No se pudo iniciar reproducción WaterMedia MP4 para '${state.name}'", it)
            -2L
        }
    }

    fun width(state: PlayingVideoAnimation): Int {
        val player = state.playerHandle as? org.watermedia.api.player.MediaPlayer ?: return 1920
        return if (player.width() > 0) player.width() else 1920
    }

    fun height(state: PlayingVideoAnimation): Int {
        val player = state.playerHandle as? org.watermedia.api.player.MediaPlayer ?: return 1080
        return if (player.height() > 0) player.height() else 1080
    }

    fun stopAndRelease(state: PlayingVideoAnimation?) {
        val player = state?.playerHandle as? org.watermedia.api.player.MediaPlayer ?: return
        state.playerHandle = null
        val texId = runCatching { player.texture().toInt() }.getOrDefault(-1)
        if (texId > 0) {
            registeredTextures.remove(texId)?.let { loc ->
                runCatching { Minecraft.getInstance().textureManager.release(loc) }
            }
        }
        bgExecutor.execute {
            runCatching { player.release() }
        }
    }
}

private object ScreenAnimationClientState {
    private const val MAX_FRAMES = 1000
    private const val MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
    private const val MAX_DIMENSION = 4096
    private const val MAX_TOTAL_PIXELS = 600_000_000L

    private val gson = Gson()
    private val pending = mutableMapOf<UUID, PendingAnimation>()
    private val prewarmed = ConcurrentHashMap<String, PrewarmedAnimation>()
    private val prewarmedVideos = ConcurrentHashMap<String, PrewarmedVideo>()
    private val pinnedCinematicAnimations = ConcurrentHashMap.newKeySet<String>()
    private val pendingPrewarmCallbacks = ConcurrentHashMap<String, MutableList<() -> Unit>>()
    @Volatile private var pendingSyncPrewarmNames: List<String> = emptyList()
    @Volatile private var pendingSyncPrewarmCallback: (() -> Unit)? = null
    private val textureSequence = AtomicLong()
    private var activeTransferId: UUID? = null
    private var prepared: PreparedPlayback? = null
    private var preparedVideo: PreparedVideoPlayback? = null
    private var playing: PlayingAnimation? = null
    private var playingVideo: PlayingVideoAnimation? = null
    private val pendingTriggers = mutableSetOf<UUID>()
    private val activeSoundInstances = mutableListOf<SimpleSoundInstance>()

    private var activeUploadTask: SlicedUploadTask? = null
    private val uploadQueue = ArrayDeque<SlicedUploadTask>()

    private val asyncExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ScreenAnimations-AsyncDecoder").apply { isDaemon = true }
    }

    fun begin(payload: AnimationTransferStartPayload) {
        if (payload.fps !in 1..120 || payload.frameCount !in 1..MAX_FRAMES ||
            payload.chunkCount !in 1..512 || payload.totalBytes !in 1..MAX_ARCHIVE_BYTES
        ) return
        activeTransferId = payload.transferId
        if (!payload.prewarmOnly) {
            prepared?.let { p ->
                if (!prewarmed.containsKey(p.name)) {
                    release(p.frames)
                }
            }
            prepared = null
        }
        pending.clear()
        pending[payload.transferId] = PendingAnimation(payload, arrayOfNulls(payload.chunkCount))
    }

    fun accept(payload: AnimationTransferChunkPayload) {
        val transfer = pending[payload.transferId] ?: return
        if (payload.index !in transfer.chunks.indices || payload.bytes.size > AnimationTransferChunkPayload.MAX_CHUNK_BYTES) return
        if (transfer.chunks[payload.index] != null) return
        transfer.chunks[payload.index] = payload.bytes
        transfer.received++
        transfer.receivedBytes += payload.bytes.size
        if (transfer.receivedBytes > transfer.start.totalBytes) {
            pending.remove(payload.transferId)
            return
        }
        if (transfer.received == transfer.chunks.size) {
            pending.remove(payload.transferId)
            if (transfer.receivedBytes != transfer.start.totalBytes) return
            decodeAsync(transfer)
        }
    }

    private fun decodeAsync(transfer: PendingAnimation) {
        asyncExecutor.execute {
            runCatching {
                val archive = ByteArray(transfer.receivedBytes)
                var cursor = 0
                transfer.chunks.forEach { chunk ->
                    val bytes = chunk ?: return@forEach
                    bytes.copyInto(archive, cursor)
                    cursor += bytes.size
                }

                var audioBytes: ByteArray? = null
                var videoBytes: ByteArray? = null
                val frameBytesList = mutableListOf<ByteArray>()

                ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        if (entry.name == "audio.ogg") {
                            audioBytes = zip.readBytes()
                            zip.closeEntry()
                            continue
                        }
                        if (entry.name.lowercase().endsWith(".mp4")) {
                            videoBytes = zip.readBytes()
                            zip.closeEntry()
                            continue
                        }
                        if (!entry.name.matches(Regex("frame_\\d{3,6}\\.png"))) {
                            zip.closeEntry()
                            continue
                        }
                        require(frameBytesList.size < transfer.start.frameCount) { "Too many frames in animation." }
                        val bytes = zip.readBytes()
                        require(bytes.size <= 8 * 1024 * 1024) { "Frame too large." }
                        frameBytesList += bytes
                        zip.closeEntry()
                    }
                }

                val soundCues = if (transfer.start.soundsJson.isNotBlank()) {
                    runCatching {
                        val listType = object : TypeToken<List<SoundCue>>() {}.type
                        gson.fromJson<List<SoundCue>>(transfer.start.soundsJson, listType) ?: emptyList()
                    }.getOrDefault(emptyList())
                } else emptyList()

                if (videoBytes != null) {
                    val animRoot = FabricLoader.getInstance().configDir
                        .resolve(ScreenAnimationsMod.MOD_ID)
                        .resolve("animations")
                        .resolve(transfer.start.animationName)
                    Files.createDirectories(animRoot)
                    val videoPath = animRoot.resolve("video.mp4")
                    Files.write(videoPath, videoBytes!!)
                    val settingsFile = animRoot.resolve("animation.json")
                    if (!Files.exists(settingsFile)) {
                        Files.writeString(
                            settingsFile,
                            "{\n  \"fps\": ${transfer.start.fps},\n  \"scale\": ${transfer.start.scale},\n  \"fit\": \"${transfer.start.fitMode}\",\n  \"preload\": true\n}\n"
                        )
                    }
                    val vidReady = if (FabricLoader.getInstance().isModLoaded("watermedia")) {
                        WaterMediaAnimBridge.prewarmMrl(videoPath)
                    } else {
                        Files.isRegularFile(videoPath)
                    }
                    val vidEntry = PrewarmedVideo(
                        name = transfer.start.animationName,
                        videoPath = videoPath,
                        fps = transfer.start.fps,
                        scale = transfer.start.scale,
                        fitMode = transfer.start.fitMode,
                        sounds = soundCues,
                        audioBytes = audioBytes
                    )
                    prewarmedVideos[transfer.start.animationName] = vidEntry
                    val prepVid = PreparedVideoPlayback(
                        transferId = transfer.start.transferId,
                        name = transfer.start.animationName,
                        videoUri = videoPath.toUri(),
                        fps = transfer.start.fps,
                        scale = transfer.start.scale,
                        fitMode = transfer.start.fitMode,
                        sounds = soundCues,
                        audioBytes = audioBytes
                    )
                    Minecraft.getInstance().execute {
                        if (transfer.start.prewarmOnly) {
                            if (vidReady && ClientPlayNetworking.canSend(AnimationReadyPayload.TYPE)) {
                                ClientPlayNetworking.send(AnimationReadyPayload(transfer.start.transferId, transfer.start.animationName, prewarmed = true))
                            }
                            notifyPrewarmCallbacks(transfer.start.animationName)
                        } else if (pendingTriggers.remove(transfer.start.transferId)) {
                            startVideoPlayback(prepVid)
                        } else {
                            preparedVideo = prepVid
                            if (ClientPlayNetworking.canSend(AnimationReadyPayload.TYPE)) {
                                ClientPlayNetworking.send(AnimationReadyPayload(transfer.start.transferId, transfer.start.animationName, prewarmed = false))
                            }
                        }
                    }
                    return@runCatching
                }

                require(frameBytesList.size == transfer.start.frameCount) { "Animation frame count did not match." }

                val task = SlicedUploadTask(
                    transferId = transfer.start.transferId,
                    animationName = transfer.start.animationName,
                    frameBytesList = frameBytesList,
                    fps = transfer.start.fps,
                    scale = transfer.start.scale,
                    fitMode = transfer.start.fitMode,
                    sounds = soundCues,
                    audioBytes = audioBytes,
                    prewarmOnly = transfer.start.prewarmOnly
                )

                Minecraft.getInstance().execute {
                    uploadQueue.add(task)
                }

                // Decode images on async background thread to avoid freezing the render tick
                asyncExecutor.execute {
                    try {
                        for (pngBytes in task.frameBytesList) {
                            runCatching {
                                val image = NativeImage.read(ByteArrayInputStream(pngBytes))
                                task.decodedQueue.add(image)
                            }
                        }
                    } finally {
                        task.decodeFinished = true
                    }
                }
            }.onFailure {
                ScreenAnimationsMod.logger.warn("Could not decode screen animation '${transfer.start.animationName}'", it)
            }
        }
    }

    fun tick(client: Minecraft) {
        val task = activeUploadTask ?: uploadQueue.poll() ?: return
        activeUploadTask = task

        // Fast zero-lag upload on main thread:
        // PNG decompression was done asynchronously in the background.
        // We only register already-decoded NativeImage buffers to OpenGL (< 0.05ms each).
        val maxFramesThisTick = if (task.prewarmOnly) 2 else 4
        val maxNanosThisTick = if (task.prewarmOnly) 1_000_000L else 2_500_000L
        val startTime = System.nanoTime()

        var uploadedCount = 0
        while (uploadedCount < maxFramesThisTick) {
            val image = task.decodedQueue.poll() ?: break
            runCatching {
                try {
                    val id = ResourceLocation.fromNamespaceAndPath(
                        ScreenAnimationsMod.MOD_ID,
                        "playback/${task.transferId}_${textureSequence.incrementAndGet()}"
                    )
                    val dynTexture = DynamicTexture(image)
                    client.textureManager.register(id, dynTexture)
                    task.textures += AnimationTexture(id, dynTexture, image.width, image.height)
                } finally {
                    image.close()
                }
            }.onFailure {
                ScreenAnimationsMod.logger.error("Failed to upload frame ${task.currentIndex} for ${task.animationName}", it)
            }

            task.currentIndex++
            uploadedCount++

            if ((System.nanoTime() - startTime) >= maxNanosThisTick) {
                break
            }
        }

        if (task.decodeFinished && task.decodedQueue.isEmpty() && task.currentIndex >= task.frameBytesList.size) {
            activeUploadTask = null
            finishUpload(task)
        }
    }

    private const val MAX_PREWARMED_ANIMATIONS = 2

    private fun finishUpload(task: SlicedUploadTask) {
        // LRU cleanup to prevent VRAM overflow on low-end machines (sin expulsar animaciones fijadas por cinemática)
        while (prewarmed.size >= MAX_PREWARMED_ANIMATIONS) {
            val oldestKey = prewarmed.keys.firstOrNull { !pinnedCinematicAnimations.contains(it) } ?: break
            prewarmed.remove(oldestKey)?.let { old ->
                if (playing?.name != old.name) {
                    release(old.frames)
                }
            }
        }

        prewarmed.remove(task.animationName)?.let { old ->
            if (playing?.name != old.name) {
                release(old.frames)
            }
        }

        val prep = PreparedPlayback(
            transferId = task.transferId,
            name = task.animationName,
            frames = task.textures,
            fps = task.fps,
            scale = task.scale,
            fitMode = task.fitMode,
            sounds = task.sounds,
            audioBytes = task.audioBytes
        )

        val fullyReady = task.decodeFinished && task.textures.isNotEmpty()
        if (fullyReady) {
            prewarmed[task.animationName] = PrewarmedAnimation(
                name = task.animationName,
                frames = task.textures,
                fps = task.fps,
                scale = task.scale,
                fitMode = task.fitMode,
                sounds = task.sounds,
                audioBytes = task.audioBytes
            )
        }

        if (task.prewarmOnly) {
            if (fullyReady && ClientPlayNetworking.canSend(AnimationReadyPayload.TYPE)) {
                ClientPlayNetworking.send(AnimationReadyPayload(task.transferId, task.animationName, prewarmed = true))
            }
            notifyPrewarmCallbacks(task.animationName)
        } else {
            if (pendingTriggers.remove(task.transferId)) {
                startPlayback(prep)
            } else {
                prepared = prep
                if (ClientPlayNetworking.canSend(AnimationReadyPayload.TYPE)) {
                    ClientPlayNetworking.send(AnimationReadyPayload(task.transferId, task.animationName, prewarmed = false))
                }
            }
        }
    }

    fun onTrigger(transferId: UUID) {
        val prepVid = preparedVideo
        if (prepVid != null && prepVid.transferId == transferId) {
            preparedVideo = null
            startVideoPlayback(prepVid)
            return
        }
        val prep = prepared
        if (prep != null && prep.transferId == transferId) {
            prepared = null
            startPlayback(prep)
        } else {
            pendingTriggers.add(transferId)
        }
    }

    private fun findLocalVideoPath(name: String): Path? {
        val root = FabricLoader.getInstance().configDir
            .resolve(ScreenAnimationsMod.MOD_ID)
            .resolve("animations")
        val folder = root.resolve(name).normalize()
        if (folder.startsWith(root) && Files.isDirectory(folder)) {
            val preferred = folder.resolve("video.mp4")
            if (Files.isRegularFile(preferred)) return preferred
            runCatching {
                Files.list(folder).use { paths ->
                    val found = paths.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".mp4") }
                        .sorted()
                        .findFirst()
                        .orElse(null)
                    if (found != null) return found
                }
            }
        }
        val directMp4 = root.resolve("$name.mp4").normalize()
        if (directMp4.startsWith(root) && Files.isRegularFile(directMp4)) {
            return directMp4
        }
        return null
    }

    fun onInstantPlay(name: String, fitMode: String, scale: Float, streamUrl: String = "") {
        val preVid = prewarmedVideos[name] ?: findLocalVideoPath(name)?.let { path ->
            val root = FabricLoader.getInstance().configDir
                .resolve(ScreenAnimationsMod.MOD_ID)
                .resolve("animations")
            val folder = root.resolve(name)
            val audioBytes = if (Files.isDirectory(folder)) {
                listOf("sound.ogg", "audio.ogg").map { folder.resolve(it) }.firstOrNull { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) }
            } else null
            PrewarmedVideo(name, path, 30, 1.0f, "rellenar", emptyList(), audioBytes).also {
                prewarmedVideos[name] = it
            }
        }
        if (preVid != null && FabricLoader.getInstance().isModLoaded("watermedia")) {
            val effectiveFit = if (fitMode.isNotBlank()) fitMode else preVid.fitMode
            val effectiveScale = if (scale > 0.0f) scale else preVid.scale
            startVideoPlayback(
                PreparedVideoPlayback(
                    transferId = UUID.randomUUID(),
                    name = preVid.name,
                    videoUri = preVid.videoPath.toUri(),
                    fps = preVid.fps,
                    scale = effectiveScale,
                    fitMode = effectiveFit,
                    sounds = preVid.sounds,
                    audioBytes = preVid.audioBytes
                )
            )
            return
        }

        val effectiveUrl = streamUrl.ifBlank {
            if (name.startsWith("http://", ignoreCase = true) || name.startsWith("https://", ignoreCase = true)) {
                name
            } else {
                RemoteAnimationsDownloader.getStreamUrl(name).orEmpty()
            }
        }.trim()
        if (effectiveUrl.isNotBlank() && FabricLoader.getInstance().isModLoaded("watermedia")) {
            val uri = runCatching { URI.create(effectiveUrl) }.getOrNull()
            if (uri != null) {
                val effectiveFit = if (fitMode.isNotBlank()) fitMode else "rellenar"
                val effectiveScale = if (scale > 0.0f) scale else 1.0f
                startVideoPlayback(
                    PreparedVideoPlayback(
                        transferId = UUID.randomUUID(),
                        name = name,
                        videoUri = uri,
                        fps = 30,
                        scale = effectiveScale,
                        fitMode = effectiveFit,
                        sounds = emptyList(),
                        audioBytes = null
                    )
                )
                return
            }
        }

        val pre = prewarmed[name]
        if (pre != null) {
            stopPlayingOnly()
            if (pre.audioBytes != null && pre.audioBytes.isNotEmpty()) {
                CustomAudioPlayer.playOgg(pre.audioBytes)
            }
            val effectiveFit = if (fitMode.isNotBlank()) fitMode else pre.fitMode
            val effectiveScale = if (scale > 0.0f) scale else pre.scale
            playing = PlayingAnimation(
                name = pre.name,
                frames = pre.frames,
                fps = pre.fps,
                scale = effectiveScale,
                fitMode = effectiveFit,
                sounds = pre.sounds,
                startedAtNanos = System.nanoTime()
            )
            pre.sounds.filter { it.frame == 0 }.forEach { playSoundCue(it) }
        } else {
            ScreenAnimationsMod.logger.warn("Instant play requested for '$name' but it was not prewarmed. Triggering remote sync.")
            RemoteAnimationsDownloader.startSync(forced = true)
        }
    }

    private fun startVideoPlayback(prep: PreparedVideoPlayback) {
        stopPlayingOnly()
        playingVideo = PlayingVideoAnimation(
            name = prep.name,
            videoUri = prep.videoUri,
            fps = prep.fps,
            scale = prep.scale,
            fitMode = prep.fitMode,
            sounds = prep.sounds,
            audioBytes = prep.audioBytes
        )
        prep.sounds.filter { it.frame == 0 }.forEach { playSoundCue(it) }
    }

    private fun startPlayback(prep: PreparedPlayback) {
        stopPlayingOnly()

        if (prep.audioBytes != null && prep.audioBytes.isNotEmpty()) {
            CustomAudioPlayer.playOgg(prep.audioBytes)
        }

        playing = PlayingAnimation(
            name = prep.name,
            frames = prep.frames,
            fps = prep.fps,
            scale = prep.scale,
            fitMode = prep.fitMode,
            sounds = prep.sounds,
            startedAtNanos = System.nanoTime()
        )

        prep.sounds.filter { it.frame == 0 }.forEach { playSoundCue(it) }
    }

    private fun playSoundCue(cue: SoundCue) {
        runCatching {
            val soundLocation = ResourceLocation.tryParse(cue.sound) ?: return
            val soundEvent = SoundEvent.createVariableRangeEvent(soundLocation)
            val soundInstance = SimpleSoundInstance.forUI(soundEvent, cue.pitch, cue.volume)
            Minecraft.getInstance().soundManager.play(soundInstance)
            activeSoundInstances += soundInstance
        }.onFailure {
            ScreenAnimationsMod.logger.warn("Could not play sound cue '${cue.sound}'", it)
        }
    }

    fun render(graphics: net.minecraft.client.gui.GuiGraphics, isOnTop: Boolean = true) {
        val vid = playingVideo
        if (vid != null && FabricLoader.getInstance().isModLoaded("watermedia")) {
            val statusOrTex = WaterMediaAnimBridge.tryStartOrPollTexture(vid)
            if (statusOrTex == -2L) {
                stopPlayingOnly()
                return
            }
            if (statusOrTex != null && statusOrTex > 0L) {
                val elapsed = (System.nanoTime() - vid.startedAtNanos).coerceAtLeast(0L)
                val frameNumber = (elapsed * vid.fps / 1_000_000_000L).toInt()
                if (frameNumber > vid.lastTriggeredFrame) {
                    for (f in (vid.lastTriggeredFrame + 1)..frameNumber) {
                        vid.sounds.filter { it.frame == f }.forEach { playSoundCue(it) }
                    }
                    vid.lastTriggeredFrame = frameNumber
                }
                val srcW = WaterMediaAnimBridge.width(vid)
                val srcH = WaterMediaAnimBridge.height(vid)
                renderFrameQuad(graphics, null, statusOrTex.toInt(), srcW, srcH, vid.fitMode, vid.scale, isOnTop)
            }
            return
        }

        val animation = playing ?: return
        val elapsed = (System.nanoTime() - animation.startedAtNanos).coerceAtLeast(0L)
        val frameNumber = (elapsed * animation.fps / 1_000_000_000L).toInt()

        if (frameNumber >= animation.frames.size) {
            stopPlayingOnly()
            return
        }

        if (frameNumber > animation.lastTriggeredFrame) {
            for (f in (animation.lastTriggeredFrame + 1)..frameNumber) {
                animation.sounds.filter { it.frame == f }.forEach { playSoundCue(it) }
            }
            animation.lastTriggeredFrame = frameNumber
        }

        val frame = animation.frames[frameNumber.coerceAtMost(animation.frames.lastIndex)]
        renderFrameQuad(graphics, frame.texture, 0, frame.width, frame.height, animation.fitMode, animation.scale, isOnTop)
    }

    private fun renderFrameQuad(
        graphics: net.minecraft.client.gui.GuiGraphics,
        textureLocation: ResourceLocation?,
        glTextureId: Int,
        srcWidth: Int,
        srcHeight: Int,
        fitMode: String,
        scaleFactor: Float,
        isOnTop: Boolean
    ) {
        val window = Minecraft.getInstance().window
        val screenWidth = window.guiScaledWidth
        val screenHeight = window.guiScaledHeight

        val fitModeLower = fitMode.lowercase().trim()
        val (drawWidth, drawHeight) = when (fitModeLower) {
            "rellenar", "cover", "fill_screen", "pantalla_completa", "fullscreen" -> {
                val effectiveScale = scaleFactor.coerceAtLeast(1.0f)
                val baseScale = maxOf(
                    screenWidth.toDouble() / srcWidth.toDouble(),
                    screenHeight.toDouble() / srcHeight.toDouble()
                )
                val s = baseScale * effectiveScale
                val w = Math.ceil(srcWidth * s).toInt().coerceAtLeast(screenWidth)
                val h = Math.ceil(srcHeight * s).toInt().coerceAtLeast(screenHeight)
                Pair(w, h)
            }
            "fill", "stretch", "estirar" -> {
                val effectiveScale = scaleFactor.coerceAtLeast(1.0f)
                val w = (screenWidth * effectiveScale).toInt().coerceAtLeast(screenWidth)
                val h = (screenHeight * effectiveScale).toInt().coerceAtLeast(screenHeight)
                Pair(w, h)
            }
            else -> {
                val userScale = scaleFactor.coerceIn(0.05f, 2.0f)
                val baseScale = minOf(
                    screenWidth.toDouble() / srcWidth.toDouble(),
                    screenHeight.toDouble() / srcHeight.toDouble()
                )
                val s = baseScale * userScale
                Pair(
                    (srcWidth * s).toInt().coerceAtLeast(1),
                    (srcHeight * s).toInt().coerceAtLeast(1)
                )
            }
        }

        val drawX = (screenWidth - drawWidth) / 2
        val drawY = (screenHeight - drawHeight) / 2

        graphics.flush()

        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)

        graphics.pose().pushPose()
        if (isOnTop) {
            graphics.pose().translate(0.0f, 0.0f, 800.0f)
        }

        if (glTextureId > 0) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader)
            RenderSystem.setShaderTexture(0, WaterMediaAnimBridge.textureLocationFor(glTextureId))
            val tessellator = Tesselator.getInstance()
            val buffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
            val matrix = graphics.pose().last().pose()
            val x1 = drawX.toFloat()
            val y1 = drawY.toFloat()
            val x2 = (drawX + drawWidth).toFloat()
            val y2 = (drawY + drawHeight).toFloat()
            buffer.addVertex(matrix, x1, y2, 0f).setUv(0f, 1f)
            buffer.addVertex(matrix, x2, y2, 0f).setUv(1f, 1f)
            buffer.addVertex(matrix, x2, y1, 0f).setUv(1f, 0f)
            buffer.addVertex(matrix, x1, y1, 0f).setUv(0f, 0f)
            BufferUploader.drawWithShader(buffer.buildOrThrow())
        } else if (textureLocation != null) {
            graphics.blit(
                textureLocation,
                drawX,
                drawY,
                drawWidth,
                drawHeight,
                0.0f,
                0.0f,
                srcWidth,
                srcHeight,
                srcWidth,
                srcHeight
            )
            graphics.flush()
        }

        graphics.pose().popPose()

        RenderSystem.disableBlend()
        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
    }

    private fun stopPlayingOnly() {
        CustomAudioPlayer.stop()
        activeSoundInstances.forEach {
            runCatching { Minecraft.getInstance().soundManager.stop(it) }
        }
        activeSoundInstances.clear()
        if (playingVideo != null && FabricLoader.getInstance().isModLoaded("watermedia")) {
            WaterMediaAnimBridge.stopAndRelease(playingVideo)
        }
        playingVideo = null
        playing?.let { p ->
            if (!prewarmed.containsKey(p.name)) {
                release(p.frames)
            }
        }
        playing = null
    }

    fun stop() {
        activeTransferId = null
        stopPlayingOnly()
        preparedVideo = null
        prepared?.let { p ->
            if (!prewarmed.containsKey(p.name)) {
                release(p.frames)
            }
        }
        prepared = null
        pending.clear()
        pendingTriggers.clear()
    }

    fun clearCache(name: String = "") {
        if (name == "__sync_github__" || name == "__reload__") {
            prewarmed.values.forEach { release(it.frames) }
            prewarmed.clear()
            prewarmedVideos.clear()
            autoPrewarmLocalAnimations()
            RemoteAnimationsDownloader.startSync(forced = true)
            return
        }
        if (name.isBlank()) {
            prewarmed.values.forEach { release(it.frames) }
            prewarmed.clear()
            prewarmedVideos.clear()
        } else {
            prewarmed.remove(name)?.let { release(it.frames) }
            prewarmedVideos.remove(name)
        }
    }

    private fun releaseTask(task: SlicedUploadTask) {
        release(task.textures)
        task.textures.clear()
        while (true) {
            val img = task.decodedQueue.poll() ?: break
            runCatching { img.close() }
        }
    }

    fun clearAll() {
        activeTransferId = null
        stopPlayingOnly()
        uploadQueue.forEach { task ->
            releaseTask(task)
        }
        uploadQueue.clear()
        activeUploadTask?.let { task ->
            releaseTask(task)
        }
        activeUploadTask = null
        clearCache("")
        prepared?.let { release(it.frames) }
        prepared = null
        pending.clear()
        pendingTriggers.clear()
    }

    fun unpinCinematicAnimations() {
        pinnedCinematicAnimations.clear()
    }

    private fun notifyPrewarmCallbacks(name: String) {
        val list = pendingPrewarmCallbacks.remove(name) ?: return
        list.forEach { runCatching { it() } }
    }

    fun onRemoteSyncCompleted() {
        val names = pendingSyncPrewarmNames
        val cb = pendingSyncPrewarmCallback
        pendingSyncPrewarmNames = emptyList()
        pendingSyncPrewarmCallback = null
        if (cb != null && names.isNotEmpty()) {
            prewarmAnimationsInternal(names, allowRemoteDownload = false, onAllReady = cb)
        }
    }

    fun prewarmAnimations(names: List<String>, onAllReady: () -> Unit) {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) {
            onAllReady()
            return
        }
        pinnedCinematicAnimations.addAll(clean)
        prewarmAnimationsInternal(clean, allowRemoteDownload = true, onAllReady = onAllReady)
    }

    private fun prewarmAnimationsInternal(clean: List<String>, allowRemoteDownload: Boolean, onAllReady: () -> Unit) {
        val root = FabricLoader.getInstance().configDir.resolve(ScreenAnimationsMod.MOD_ID).resolve("animations")
        val missingLocally = clean.filter { name ->
            !prewarmed.containsKey(name) && !prewarmedVideos.containsKey(name) &&
                !Files.exists(root.resolve(name)) && !Files.exists(root.resolve("$name.mp4"))
        }
        if (allowRemoteDownload && missingLocally.isNotEmpty()) {
            pendingSyncPrewarmNames = clean
            pendingSyncPrewarmCallback = onAllReady
            RemoteAnimationsDownloader.startSync(forced = false)
            return
        }

        val remaining = java.util.concurrent.atomic.AtomicInteger(clean.size)
        val finishOne = {
            if (remaining.decrementAndGet() <= 0) {
                onAllReady()
            }
        }

        for (name in clean) {
            if (prewarmed.containsKey(name) || prewarmedVideos.containsKey(name)) {
                finishOne()
                continue
            }
            val mp4Direct = root.resolve("$name.mp4")
            val folder = root.resolve(name)
            when {
                Files.isRegularFile(mp4Direct) -> {
                    asyncExecutor.execute {
                        prewarmLocalVideo(name, mp4Direct, AnimationDefinition(fps = 30, fit = "rellenar", preload = true), null)
                        Minecraft.getInstance().execute { finishOne() }
                    }
                }
                Files.isDirectory(folder) -> {
                    val settingsFile = folder.resolve("animation.json")
                    val def: AnimationDefinition = if (Files.isRegularFile(settingsFile)) {
                        runCatching {
                            Files.newBufferedReader(settingsFile).use { gson.fromJson(it, AnimationDefinition::class.java) }
                        }.getOrNull() ?: AnimationDefinition()
                    } else {
                        AnimationDefinition(fps = 30, fit = "rellenar", preload = true)
                    }
                    val mp4Sub = Files.list(folder).use { sub ->
                        val preferred = folder.resolve("video.mp4")
                        if (Files.isRegularFile(preferred)) preferred else sub
                            .filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".mp4") }
                            .sorted()
                            .findFirst()
                            .orElse(null)
                    }
                    if (mp4Sub != null) {
                        asyncExecutor.execute {
                            prewarmLocalVideo(name, mp4Sub, def, folder)
                            Minecraft.getInstance().execute { finishOne() }
                        }
                    } else {
                        pendingPrewarmCallbacks.computeIfAbsent(name) { java.util.Collections.synchronizedList(mutableListOf()) }.add(finishOne)
                        asyncExecutor.execute {
                            prewarmLocalFolder(folder, name, def)
                            if (!prewarmed.containsKey(name) && !pendingPrewarmCallbacks.containsKey(name)) {
                                Minecraft.getInstance().execute { finishOne() }
                            }
                        }
                    }
                }
                else -> finishOne()
            }
        }
    }

    fun autoPrewarmLocalAnimations() {
        asyncExecutor.execute {
            runCatching {
                val root = FabricLoader.getInstance().configDir
                    .resolve(ScreenAnimationsMod.MOD_ID)
                    .resolve("animations")
                if (!Files.isDirectory(root)) return@execute
                Files.list(root).use { paths ->
                    paths.forEach { entry ->
                        val fileName = entry.fileName.toString()
                        if (Files.isRegularFile(entry) && fileName.lowercase().endsWith(".mp4")) {
                            val name = fileName.dropLast(4)
                            prewarmLocalVideo(name, entry, AnimationDefinition(fps = 30, fit = "rellenar", preload = true), null)
                        } else if (Files.isDirectory(entry)) {
                            val settingsFile = entry.resolve("animation.json")
                            val def: AnimationDefinition = if (Files.isRegularFile(settingsFile)) {
                                runCatching {
                                    Files.newBufferedReader(settingsFile).use { gson.fromJson(it, AnimationDefinition::class.java) }
                                }.getOrNull() ?: AnimationDefinition()
                            } else {
                                AnimationDefinition(fps = 30, fit = "rellenar", preload = true)
                            }
                            val mp4 = Files.list(entry).use { sub ->
                                val preferred = entry.resolve("video.mp4")
                                if (Files.isRegularFile(preferred)) preferred else sub
                                    .filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".mp4") }
                                    .sorted()
                                    .findFirst()
                                    .orElse(null)
                            }
                            if (mp4 != null) {
                                prewarmLocalVideo(fileName, mp4, def, entry)
                            } else if (def.precalentar || def.preload || def.precargar) {
                                if (!prewarmed.containsKey(fileName)) {
                                    prewarmLocalFolder(entry, fileName, def)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun prewarmLocalVideo(name: String, videoPath: Path, settings: AnimationDefinition, folder: Path?) {
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
        val audioBytes = folder?.let { f ->
            listOf("sound.ogg", "audio.ogg").map { f.resolve(it) }.firstOrNull { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) }
        }
        val rawFit = (settings.fit ?: settings.modo ?: "rellenar").lowercase().trim()
        val fit = when (rawFit) {
            "rellenar", "cover", "fill_screen", "pantalla_completa", "fullscreen" -> "rellenar"
            "fill", "stretch", "estirar" -> "fill"
            else -> "contain"
        }
        val scale = (settings.scale ?: 1.0f).coerceIn(0.05f, 2.0f)
        prewarmedVideos[name] = PrewarmedVideo(name, videoPath, settings.fps.coerceIn(1, 120), scale, fit, soundCues, audioBytes)
        val vidReady = if (FabricLoader.getInstance().isModLoaded("watermedia")) {
            WaterMediaAnimBridge.prewarmMrl(videoPath)
        } else {
            Files.isRegularFile(videoPath)
        }
        Minecraft.getInstance().execute {
            if (vidReady && ClientPlayNetworking.canSend(AnimationReadyPayload.TYPE)) {
                ClientPlayNetworking.send(AnimationReadyPayload(UUID(0L, 0L), name, prewarmed = true))
            }
            notifyPrewarmCallbacks(name)
        }
    }

    private fun prewarmLocalFolder(folder: java.nio.file.Path, name: String, settings: AnimationDefinition) {
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

        val frames = java.nio.file.Files.list(folder).use { paths ->
            paths.filter { java.nio.file.Files.isRegularFile(it) && it.fileName.toString().matches(Regex("frame_\\d{3,6}\\.png")) }
                .sorted()
                .toList()
        }
        if (frames.isEmpty()) return

        val audioFile = listOf("sound.ogg", "audio.ogg").map { folder.resolve(it) }.firstOrNull { java.nio.file.Files.isRegularFile(it) }
        val audioBytes = audioFile?.let { java.nio.file.Files.readAllBytes(it) }

        val frameBytesList = mutableListOf<ByteArray>()
        frames.forEach { framePath ->
            frameBytesList += java.nio.file.Files.readAllBytes(framePath)
        }

        val rawFit = (settings.fit ?: settings.modo ?: "contain").lowercase().trim()
        val fit = when (rawFit) {
            "rellenar", "cover", "fill_screen", "pantalla_completa", "fullscreen" -> "rellenar"
            "fill", "stretch", "estirar" -> "fill"
            else -> "contain"
        }
        val scale = (settings.scale ?: 1.0f).coerceIn(0.05f, 2.0f)

        val task = SlicedUploadTask(
            transferId = UUID.randomUUID(),
            animationName = name,
            frameBytesList = frameBytesList,
            fps = settings.fps.coerceIn(1, 120),
            scale = scale,
            fitMode = fit,
            sounds = soundCues,
            audioBytes = audioBytes,
            prewarmOnly = true
        )

        Minecraft.getInstance().execute {
            uploadQueue.add(task)
        }

        asyncExecutor.execute {
            try {
                for (pngBytes in task.frameBytesList) {
                    runCatching {
                        val image = NativeImage.read(ByteArrayInputStream(pngBytes))
                        task.decodedQueue.add(image)
                    }
                }
            } finally {
                task.decodeFinished = true
            }
        }
    }

    private fun release(frames: List<AnimationTexture>) {
        frames.forEach {
            runCatching { Minecraft.getInstance().textureManager.release(it.texture) }
            runCatching { it.dynamicTexture.close() }
        }
    }
}
