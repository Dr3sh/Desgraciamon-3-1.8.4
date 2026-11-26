package com.desgraciamon.videomenu

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import org.watermedia.api.player.MediaPlayer
import org.watermedia.api.player.MRL
import org.watermedia.api.player.PlayerAPI
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.util.concurrent.Executor

object VideoPlayerManager {
    private val DEFAULT_TEXTURE = ResourceLocation.fromNamespaceAndPath("desgraciamon4_video_menu", "textures/gui/background_frame.png")
    private val CUSTOM_TEXTURE_ID = ResourceLocation.fromNamespaceAndPath("desgraciamon4_video_menu", "custom_background_frame")

    // Static frame state (Frame 10 fallback)
    private var customTexture: DynamicTexture? = null
    private var customImageWidth: Int = 2560
    private var customImageHeight: Int = 1440
    private var lastLoadedImageFile: File? = null
    private var lastImageModifiedTime: Long = 0L
    private var hasCustomImageTexture: Boolean = false
    private var cachedImageFile: File? = null
    private var lastImageCheckMs: Long = 0L

    // Video player state (Persistent outside gameplay)
    private var mediaPlayer: MediaPlayer? = null
    private var currentVideoUri: URI? = null
    private var isVideoAvailable: Boolean = false
    private var isPrewarming: Boolean = false
    private var cachedVideoFile: File? = null
    private var lastVideoCheckMs: Long = 0L
    private var pendingMrl: MRL? = null
    private var pendingMrlUri: URI? = null
    private var lastPlayerAttemptMs: Long = 0L
    private var lastLoggedPlayerErrorMs: Long = 0L

    fun initialize() {
        println("[DesgraciamonVideoMenu] Video & Frame Manager initialized.")
    }

    fun isInWorld(): Boolean {
        return try {
            val mc = Minecraft.getInstance() ?: return false
            mc.level != null
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Lightweight prewarm during LoadingOverlay: only loads the static PNG/JPG fallback frame
     * without starting software FFmpeg video decoding while mods and resource packs are loading.
     */
    @Synchronized
    fun prewarmStaticOnly() {
        if (hasCustomImageTexture || isInWorld() || isPrewarming) {
            return
        }
        val load = {
            try {
                reloadCustomImageTexture()
            } catch (t: Throwable) {
                System.err.println("[DesgraciamonVideoMenu] Error prewarming static frame: ${t.message}")
            }
        }
        if (RenderSystem.isOnRenderThread()) {
            load()
        } else {
            RenderSystem.recordRenderCall { load() }
        }
    }

    /**
     * Prewarm static frame and video player so they are already resident in memory and decoding.
     */
    @JvmOverloads
    @Synchronized
    fun prewarm(force: Boolean = false) {
        if (!force && isInWorld()) {
            return
        }

        if (mediaPlayer != null && hasCustomImageTexture) {
            return
        }

        if (isPrewarming) {
            return
        }

        if (RenderSystem.isOnRenderThread()) {
            doPrewarm(force)
        } else {
            RenderSystem.recordRenderCall {
                doPrewarm(force)
            }
        }
    }

    private fun doPrewarm(force: Boolean) {
        if (!force && isInWorld()) {
            return
        }

        isPrewarming = true
        try {
            reloadCustomImageTexture()
        } catch (t: Throwable) {
            System.err.println("[DesgraciamonVideoMenu] Error prewarming static frame: ${t.message}")
        }

        try {
            ensureVideoPlayerStarted(force)
        } catch (t: Throwable) {
            System.err.println("[DesgraciamonVideoMenu] Error prewarming video player: ${t.message}")
        } finally {
            isPrewarming = false
        }
    }

    fun resolveVideoFile(): File? {
        val gameDir = FabricLoader.getInstance().gameDir.toFile()
        val candidatePaths = listOf(
            File(gameDir, "desgraciamon4/fondo.mp4"),
            File(gameDir, "backgrounds/fondo.mp4"),
            File(gameDir, "config/desgraciamon4/fondo.mp4"),
            File(gameDir, "videos/fondo.mp4"),
            File(gameDir, "fondo.mp4")
        )

        for (candidate in candidatePaths) {
            if (candidate.exists() && candidate.isFile && candidate.length() > 0) {
                return candidate
            }
        }
        return null
    }

    fun resolveCustomImageFile(): File? {
        val gameDir = FabricLoader.getInstance().gameDir.toFile()
        val candidatePaths = listOf(
            File(gameDir, "desgraciamon4/fondo.png"),
            File(gameDir, "backgrounds/fondo.png"),
            File(gameDir, "config/desgraciamon4/fondo.png"),
            File(gameDir, "desgraciamon4/fondo.jpg"),
            File(gameDir, "backgrounds/fondo.jpg"),
            File(gameDir, "config/desgraciamon4/fondo.jpg"),
            File(gameDir, "fondo.png"),
            File(gameDir, "fondo.jpg")
        )

        for (candidate in candidatePaths) {
            if (candidate.exists() && candidate.isFile && candidate.length() > 0) {
                return candidate
            }
        }
        return null
    }

    private fun reloadCustomImageTexture() {
        val now = System.currentTimeMillis()
        val file = if (now - lastImageCheckMs < 2000L && lastImageCheckMs > 0L) {
            cachedImageFile
        } else {
            lastImageCheckMs = now
            resolveCustomImageFile().also { cachedImageFile = it }
        }
        if (file == null) {
            hasCustomImageTexture = false
            return
        }

        if (file == lastLoadedImageFile && file.lastModified() == lastImageModifiedTime && customTexture != null) {
            return
        }

        try {
            FileInputStream(file).use { stream ->
                val nativeImage = NativeImage.read(stream)
                customImageWidth = nativeImage.width
                customImageHeight = nativeImage.height

                val mc = Minecraft.getInstance() ?: return
                if (mc.textureManager == null) return

                customTexture?.close()
                val tex = DynamicTexture(nativeImage)
                tex.upload()
                mc.textureManager.register(CUSTOM_TEXTURE_ID, tex)
                customTexture = tex
                hasCustomImageTexture = true
                lastLoadedImageFile = file
                lastImageModifiedTime = file.lastModified()
                println("[DesgraciamonVideoMenu] Prewarmed custom background texture (${customImageWidth}x${customImageHeight}) from ${file.name}")
            }
        } catch (t: Throwable) {
            System.err.println("[DesgraciamonVideoMenu] Failed to load custom background image: ${t.message}")
            hasCustomImageTexture = false
        }
    }

    @Synchronized
    private fun ensureVideoPlayerStarted(force: Boolean = false) {
        if (!force && isInWorld()) {
            stopVideo()
            return
        }

        val now = System.currentTimeMillis()
        val video = if (now - lastVideoCheckMs < 2000L && lastVideoCheckMs > 0L) {
            cachedVideoFile
        } else {
            lastVideoCheckMs = now
            resolveVideoFile().also { cachedVideoFile = it }
        }
        if (video == null) {
            stopVideo()
            return
        }

        val targetUri = video.toURI()
        val p = mediaPlayer
        if (p != null && currentVideoUri == targetUri) {
            if (p.paused()) {
                p.resume()
            }
            return
        }

        stopVideo()

        if (now - lastPlayerAttemptMs < 500L) {
            return
        }

        try {
            if (FabricLoader.getInstance().isModLoaded("watermedia")) {
                val mrl = if (pendingMrl != null && pendingMrlUri == targetUri) {
                    pendingMrl!!
                } else {
                    PlayerAPI.mrl(targetUri).also {
                        pendingMrl = it
                        pendingMrlUri = targetUri
                    }
                }
                if (mrl.source(0) == null) {
                    lastPlayerAttemptMs = now
                    return
                }
                lastPlayerAttemptMs = now
                val renderExecutor = Executor { task -> RenderSystem.recordRenderCall { task.run() } }
                val renderThread = if (RenderSystem.isOnRenderThread()) Thread.currentThread() else null

                // Video menu is always muted; omit SFXEngine so early prewarm never touches OpenAL before initialization
                val newPlayer = PlayerAPI.player(
                    mrl,
                    { PlayerAPI.glEngine(renderThread, renderExecutor) },
                    { null }
                )

                if (newPlayer == null) {
                    return
                }

                newPlayer.repeat(true)
                newPlayer.mute(true)
                newPlayer.volume(0)
                newPlayer.start()

                mediaPlayer = newPlayer
                currentVideoUri = targetUri
                pendingMrl = null
                pendingMrlUri = null
                isVideoAvailable = true
                println("[DesgraciamonVideoMenu] Video player started & prewarmed: ${video.name}")
            }
        } catch (t: Throwable) {
            if (now - lastLoggedPlayerErrorMs > 5000L) {
                lastLoggedPlayerErrorMs = now
                System.err.println("[DesgraciamonVideoMenu] Could not start video player: ${t.message}")
            }
            stopVideo()
        }
    }

    @Synchronized
    fun stop() {
        stopVideo()
    }

    @Synchronized
    fun stopVideo() {
        val p = mediaPlayer
        if (p != null) {
            try {
                p.stop()
                p.release()
            } catch (ignored: Exception) {}
            mediaPlayer = null
            currentVideoUri = null
            isVideoAvailable = false
            println("[DesgraciamonVideoMenu] Video player stopped and memory freed.")
        }
    }

    /**
     * Called on TitleScreen (Main Menu): plays video if available, falls back to frame of second 10.
     */
    fun renderTitleScreen(graphics: GuiGraphics, width: Int, height: Int) {
        if (!isInWorld()) {
            ensureVideoPlayerStarted()
        }

        val p = mediaPlayer
        val videoTexId = p?.texture() ?: 0L

        if (videoTexId > 0L) {
            renderTextureQuad(
                graphics, width, height,
                textureId = videoTexId.toInt(),
                resourceLocation = null,
                srcWidth = if (p!!.width() > 0) p.width().toFloat() else 1920f,
                srcHeight = if (p.height() > 0) p.height().toFloat() else 1080f
            )
        } else {
            renderStaticFrame(graphics, width, height)
        }
    }

    /**
     * Called on all other screens with vanilla panorama (Options, World Selection, Credits, etc.):
     * Always renders the static frame of second 10 underneath buttons.
     */
    fun renderStaticFrame(graphics: GuiGraphics, width: Int, height: Int) {
        try {
            reloadCustomImageTexture()

            val imageW = if (hasCustomImageTexture && customImageWidth > 0) customImageWidth.toFloat() else 2560f
            val imageH = if (hasCustomImageTexture && customImageHeight > 0) customImageHeight.toFloat() else 1440f

            if (hasCustomImageTexture && customTexture != null) {
                renderTextureQuad(graphics, width, height, customTexture!!.id, null, imageW, imageH)
            } else {
                renderTextureQuad(graphics, width, height, 0, DEFAULT_TEXTURE, imageW, imageH)
            }
        } catch (t: Throwable) {
            System.err.println("[DesgraciamonVideoMenu] Error rendering static background frame: ${t.message}")
            graphics.fill(0, 0, width, height, -0x1000000)
        }
    }

    fun renderBackground(graphics: GuiGraphics, width: Int, height: Int) {
        renderStaticFrame(graphics, width, height)
    }

    fun renderVideo(graphics: GuiGraphics, width: Int, height: Int) {
        renderTitleScreen(graphics, width, height)
    }

    private fun renderTextureQuad(
        graphics: GuiGraphics,
        screenWidth: Int,
        screenHeight: Int,
        textureId: Int,
        resourceLocation: ResourceLocation?,
        srcWidth: Float,
        srcHeight: Float
    ) {
        val screenW = screenWidth.toFloat()
        val screenH = screenHeight.toFloat()

        // Aspect Fill calculation (Proportional Scaling & Center Crop)
        val scale = maxOf(screenW / srcWidth, screenH / srcHeight)
        val drawW = srcWidth * scale
        val drawH = srcHeight * scale
        val drawX = (screenW - drawW) / 2f
        val drawY = (screenH - drawH) / 2f

        RenderSystem.setShader(GameRenderer::getPositionTexShader)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        if (textureId > 0) {
            RenderSystem.setShaderTexture(0, textureId)
        } else if (resourceLocation != null) {
            RenderSystem.setShaderTexture(0, resourceLocation)
        }
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()

        val tessellator = Tesselator.getInstance()
        val buffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
        val matrix = graphics.pose().last().pose()

        buffer.addVertex(matrix, drawX, drawY + drawH, 0f).setUv(0f, 1f)
        buffer.addVertex(matrix, drawX + drawW, drawY + drawH, 0f).setUv(1f, 1f)
        buffer.addVertex(matrix, drawX + drawW, drawY, 0f).setUv(1f, 0f)
        buffer.addVertex(matrix, drawX, drawY, 0f).setUv(0f, 0f)

        BufferUploader.drawWithShader(buffer.buildOrThrow())
        RenderSystem.disableBlend()

        // Soft darkening overlay (25% black) for menu text & button readability
        graphics.fill(0, 0, screenWidth, screenHeight, 0x40000000)
    }
}
