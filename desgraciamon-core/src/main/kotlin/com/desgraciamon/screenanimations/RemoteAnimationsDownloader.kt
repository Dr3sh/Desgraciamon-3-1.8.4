package com.desgraciamon.screenanimations

import com.google.gson.GsonBuilder
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import org.joml.Matrix4f
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

object RemoteAnimationsDownloader {
    private val logger = LoggerFactory.getLogger("desgraciamon4-remote-assets")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val asyncExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Desgraciamon-RemoteAssetsDownloader").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    @Volatile
    var onSyncComplete: (() -> Unit)? = null

    @Volatile
    private var rerunRequested: Boolean = false

    private val remoteStreamUrls = ConcurrentHashMap<String, String>()

    fun getStreamUrl(name: String): String? = remoteStreamUrls[name]

    private const val DEFAULT_MANIFEST_URL = "https://raw.githubusercontent.com/Dr3sh/desgraciamon-assets/main/animations/manifest.json"

    data class RemoteConfig(
        var enabled: Boolean = true,
        var manifestUrl: String = DEFAULT_MANIFEST_URL,
        var autoSyncOnJoin: Boolean = true
    )

    data class ManifestEntry(
        var name: String = "",
        var url: String = "",
        var hash: String = "",
        var sizeBytes: Long = 0L
    )

    data class RemoteManifest(
        var version: Int = 1,
        var animations: List<ManifestEntry> = emptyList()
    )

    data class CacheState(
        var downloadedAnimations: MutableMap<String, String> = mutableMapOf() // name -> hash
    )

    object Status {
        @Volatile var isRunning: Boolean = false
        @Volatile var message: String = "Descargando assets..."
        @Volatile var currentItem: String = ""
        @Volatile var itemsDone: Int = 0
        @Volatile var itemsTotal: Int = 0
        @Volatile var percent: Int = 0
        @Volatile var bannerAlpha: Float = 0.0f
        @Volatile var finishTimeMs: Long = 0L
    }

    private fun configPath(): Path = FabricLoader.getInstance().configDir
        .resolve(ScreenAnimationsMod.MOD_ID)
        .resolve("remote.json")

    private fun cacheStatePath(): Path = FabricLoader.getInstance().configDir
        .resolve(ScreenAnimationsMod.MOD_ID)
        .resolve("remote_cache.json")

    private fun animationsDir(): Path = FabricLoader.getInstance().configDir
        .resolve(ScreenAnimationsMod.MOD_ID)
        .resolve("animations")

    fun loadConfig(): RemoteConfig {
        return runCatching {
            val file = configPath()
            if (Files.isRegularFile(file)) {
                val cfg = Files.newBufferedReader(file).use { gson.fromJson(it, RemoteConfig::class.java) } ?: RemoteConfig()
                if (cfg.manifestUrl.isBlank() || cfg.manifestUrl.contains("usuario/repo")) {
                    cfg.manifestUrl = DEFAULT_MANIFEST_URL
                    Files.createDirectories(file.parent)
                    Files.writeString(file, gson.toJson(cfg))
                }
                cfg
            } else {
                Files.createDirectories(file.parent)
                val defaultCfg = RemoteConfig()
                Files.writeString(file, gson.toJson(defaultCfg))
                defaultCfg
            }
        }.getOrElse {
            logger.warn("Could not read remote.json, using defaults.", it)
            RemoteConfig()
        }
    }

    private fun withCacheBuster(url: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        return "${url}${sep}cb=${System.currentTimeMillis()}"
    }

    private fun loadCacheState(): CacheState {
        return runCatching {
            val file = cacheStatePath()
            if (Files.isRegularFile(file)) {
                Files.newBufferedReader(file).use { gson.fromJson(it, CacheState::class.java) } ?: CacheState()
            } else CacheState()
        }.getOrElse { CacheState() }
    }

    private fun saveCacheState(state: CacheState) {
        runCatching {
            val file = cacheStatePath()
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(state))
        }
    }

    /**
     * Inicia la sincronización remota desde GitHub en segundo plano.
     * @param forced true si fue llamado por comando manual
     */
    fun startSync(forced: Boolean = false) {
        if (Status.isRunning) {
            if (forced) {
                rerunRequested = true
            }
            logger.info("[RemoteAssets] Sincronizacion ya en curso.")
            return
        }

        val config = loadConfig()
        if (!config.enabled && !forced) return

        val manifestUrl = config.manifestUrl.trim()
        if (manifestUrl.isBlank() || manifestUrl.contains("usuario/repo")) {
            logger.info("[RemoteAssets] manifestUrl no configurada o es plantilla predeterminada. Omitiendo descarga.")
            return
        }

        rerunRequested = false
        asyncExecutor.execute {
            if (!forced) {
                runCatching { Thread.sleep(3500L) }
            }
            while (true) {
                rerunRequested = false
                val currentUrl = loadConfig().manifestUrl.trim()
                if (currentUrl.isNotBlank() && !currentUrl.contains("usuario/repo")) {
                    syncInternal(currentUrl, throttled = !forced)
                }
                if (!rerunRequested) break
            }
        }
    }

    private fun parseRemoteManifest(raw: String?): RemoteManifest {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return RemoteManifest()
        return try {
            gson.fromJson(trimmed, RemoteManifest::class.java) ?: RemoteManifest()
        } catch (ex: Exception) {
            if (trimmed.startsWith("{\\n") || trimmed.startsWith("{\\r\\n")) {
                val unescaped = trimmed
                    .replace("\\r\\n", "\n")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                try {
                    return gson.fromJson(unescaped, RemoteManifest::class.java) ?: RemoteManifest()
                } catch (_: Exception) {
                }
            }
            logger.warn("[RemoteAssets] manifest.json vacio o con formato invalido (${ex.message}); se usara manifiesto vacio")
            RemoteManifest()
        }
    }

    private fun syncInternal(manifestUrl: String, throttled: Boolean = true) {
        Status.isRunning = true
        Status.itemsDone = 0
        Status.itemsTotal = 0
        Status.percent = 0
        Status.bannerAlpha = 1.0f
        Status.message = "Comprobando assets remotos..."
        Status.currentItem = ""

        try {
            logger.info("[RemoteAssets] Consultando manifest desde: $manifestUrl")
            val request = HttpRequest.newBuilder()
                .uri(URI.create(withCacheBuster(manifestUrl)))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "DesgraciaMon4-ScreenAnimations/1.0")
                .header("Cache-Control", "no-cache, no-store")
                .header("Pragma", "no-cache")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logger.warn("[RemoteAssets] Error HTTP ${response.statusCode()} al obtener manifest.json")
                Status.message = "Error al conectar con repositorio"
                finishStatus(delayMs = 2500)
                return
            }

            val manifest = parseRemoteManifest(response.body())

            val remoteAnims = manifest.animations.filter { it.name.isNotBlank() && it.url.isNotBlank() }
            val remoteNames = remoteAnims.map { it.name }.toSet()
            remoteStreamUrls.clear()
            for (entry in remoteAnims) {
                remoteStreamUrls[entry.name] = entry.url.trim()
            }

            val downloadedCount = synchronized(ScreenAnimationStorage.remoteIoLock) {
                val cacheState = loadCacheState()
                val animDir = animationsDir()
                Files.createDirectories(animDir)

                // 1. Limpieza de animaciones eliminadas en GitHub
                val localDownloaded = cacheState.downloadedAnimations.keys.toList()
                for (name in localDownloaded) {
                    if (!remoteNames.contains(name)) {
                        val folder = animDir.resolve(name)
                        if (Files.exists(folder)) {
                            logger.info("[RemoteAssets] Eliminando animacion obsoleta o retirada: $name")
                            deleteDirectory(folder)
                        }
                        cacheState.downloadedAnimations.remove(name)
                    }
                }
                saveCacheState(cacheState)

                // 2. Determinar animaciones que necesitan descargarse (MP4 usa streaming directo por defecto)
                val toDownload = remoteAnims.filter { entry ->
                    val cleanUrl = entry.url.substringBefore('?').substringBefore('#').lowercase()
                    if (cleanUrl.endsWith(".mp4")) {
                        return@filter false
                    }
                    val cachedHash = cacheState.downloadedAnimations[entry.name]
                    val folder = animDir.resolve(entry.name)
                    val hasConfig = Files.exists(folder.resolve("animation.json"))
                    val hasMp4 = hasMp4InFolder(folder) || Files.isRegularFile(animDir.resolve("${entry.name}.mp4"))
                    val isFolderComplete = (Files.isDirectory(folder) && (hasConfig || hasMp4)) || Files.isRegularFile(animDir.resolve("${entry.name}.mp4"))
                    !isFolderComplete || cachedHash != entry.hash
                }

                if (toDownload.isEmpty()) {
                    logger.info("[RemoteAssets] Todos los assets remotos estan al dia.")
                    Status.message = "Assets al dia"
                    Status.percent = 100
                    finishStatus(delayMs = 1500)
                    onSyncComplete?.invoke()
                    return
                }

                Status.itemsTotal = toDownload.size
                logger.info("[RemoteAssets] Descargando ${toDownload.size} animaciones nuevas/actualizadas...")

                for ((index, entry) in toDownload.withIndex()) {
                    Status.itemsDone = index
                    Status.currentItem = entry.name
                    Status.message = "Descargando: ${entry.name}"
                    Status.percent = ((index.toFloat() / toDownload.size.toFloat()) * 100f).toInt()

                    downloadAndExtractAnimation(entry, animDir, index, toDownload.size, throttled)
                    cacheState.downloadedAnimations[entry.name] = entry.hash
                    saveCacheState(cacheState)
                    if (throttled && index + 1 < toDownload.size) {
                        runCatching { Thread.sleep(350L) }
                    }
                }
                toDownload.size
            }

            Status.itemsDone = downloadedCount
            Status.percent = 100
            Status.message = "Assets sincronizados con éxito"
            logger.info("[RemoteAssets] Sincronización completada exitosamente.")
            finishStatus(delayMs = 3000)
            onSyncComplete?.invoke()

        } catch (e: Exception) {
            logger.error("[RemoteAssets] Excepción durante la sincronización remota", e)
            Status.message = "Error en descarga de assets"
            finishStatus(delayMs = 2500)
        }
    }

    private fun hasMp4InFolder(folder: Path): Boolean {
        if (!Files.isDirectory(folder)) return false
        return runCatching {
            Files.list(folder).use { paths ->
                paths.anyMatch { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".mp4") }
            }
        }.getOrDefault(false)
    }

    private fun downloadAndExtractAnimation(
        entry: ManifestEntry,
        animDir: Path,
        itemIndex: Int,
        totalItems: Int,
        throttled: Boolean
    ) {
        val targetFolder = animDir.resolve(entry.name)
        val tempFile = animDir.resolve(".${entry.name}.part")
        Files.deleteIfExists(tempFile)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(withCacheBuster(entry.url)))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", "DesgraciaMon4-ScreenAnimations/1.0")
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("HTTP ${response.statusCode()} al descargar ${entry.url}")
        }

        val expectedSize = response.headers().firstValueAsLong("content-length").orElse(entry.sizeBytes).coerceAtLeast(0L)
        val headerSample = ByteArray(12)
        var headerBytes = 0
        var totalRead = 0L

        try {
            BufferedInputStream(response.body(), 16 * 1024).use { bis ->
                Files.newOutputStream(tempFile).use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var chunksSincePause = 0
                    while (true) {
                        val read = bis.read(buffer)
                        if (read <= 0) break
                        if (headerBytes < headerSample.size) {
                            val toCopy = minOf(read, headerSample.size - headerBytes)
                            System.arraycopy(buffer, 0, headerSample, headerBytes, toCopy)
                            headerBytes += toCopy
                        }
                        out.write(buffer, 0, read)
                        totalRead += read
                        chunksSincePause++

                        if (expectedSize > 0L && totalItems > 0) {
                            val itemFraction = (totalRead.toFloat() / expectedSize.toFloat()).coerceIn(0f, 1f)
                            Status.percent = (((itemIndex + itemFraction) / totalItems.toFloat()) * 100f).toInt().coerceIn(0, 99)
                        }

                        if (throttled && chunksSincePause >= 4) {
                            chunksSincePause = 0
                            runCatching { Thread.sleep(2L) }
                        }
                    }
                }
            }

            if (Files.exists(targetFolder)) {
                deleteDirectory(targetFolder)
            }
            Files.createDirectories(targetFolder)

            val cleanUrl = entry.url.substringBefore('?').substringBefore('#').lowercase()
            val isMp4Header = headerBytes >= 8 &&
                headerSample[4] == 'f'.code.toByte() &&
                headerSample[5] == 't'.code.toByte() &&
                headerSample[6] == 'y'.code.toByte() &&
                headerSample[7] == 'p'.code.toByte()
            val isZipHeader = headerBytes >= 2 && headerSample[0] == 'P'.code.toByte() && headerSample[1] == 'K'.code.toByte()

            if (cleanUrl.endsWith(".mp4") || (isMp4Header && !isZipHeader)) {
                Files.move(
                    tempFile,
                    targetFolder.resolve("video.mp4"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
                val settingsFile = targetFolder.resolve("animation.json")
                if (!Files.exists(settingsFile)) {
                    Files.writeString(
                        settingsFile,
                        "{\n  \"fps\": 30,\n  \"scale\": 1.0,\n  \"fit\": \"rellenar\",\n  \"preload\": true\n}\n"
                    )
                }
                logger.info("[RemoteAssets] Descargado video MP4 '${entry.name}' en $targetFolder ($totalRead bytes)")
            } else {
                ZipInputStream(BufferedInputStream(Files.newInputStream(tempFile), 16 * 1024)).use { zis ->
                    val copyBuf = ByteArray(16 * 1024)
                    while (true) {
                        val zipEntry = zis.nextEntry ?: break
                        if (!zipEntry.isDirectory) {
                            val outFile = targetFolder.resolve(zipEntry.name).normalize()
                            if (outFile.startsWith(targetFolder)) {
                                Files.createDirectories(outFile.parent)
                                Files.newOutputStream(outFile).use { out ->
                                    while (true) {
                                        val n = zis.read(copyBuf)
                                        if (n <= 0) break
                                        out.write(copyBuf, 0, n)
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                        if (throttled) {
                            runCatching { Thread.sleep(1L) }
                        }
                    }
                }
                val settingsFile = targetFolder.resolve("animation.json")
                if (!Files.exists(settingsFile) && hasMp4InFolder(targetFolder)) {
                    Files.writeString(
                        settingsFile,
                        "{\n  \"fps\": 30,\n  \"scale\": 1.0,\n  \"fit\": \"rellenar\",\n  \"preload\": true\n}\n"
                    )
                }
                logger.info("[RemoteAssets] Extraida animacion '${entry.name}' en $targetFolder")
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun deleteDirectory(path: Path) {
        runCatching {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private fun finishStatus(delayMs: Long) {
        Status.finishTimeMs = System.currentTimeMillis() + delayMs
        Status.isRunning = false
    }

    /**
     * Dibuja un rectángulo coloreado 2D directo usando Tesselator.
     */
    private fun drawRect(x1: Float, y1: Float, x2: Float, y2: Float, colorArgb: Int) {
        val a = ((colorArgb ushr 24) and 0xFF) / 255.0f
        val r = ((colorArgb ushr 16) and 0xFF) / 255.0f
        val g = ((colorArgb ushr 8) and 0xFF) / 255.0f
        val b = (colorArgb and 0xFF) / 255.0f
        if (a <= 0.001f) return

        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val tessellator = Tesselator.getInstance()
        val buf = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        val m = Matrix4f()

        buf.addVertex(m, x1, y2, 0f).setColor(r, g, b, a)
        buf.addVertex(m, x2, y2, 0f).setColor(r, g, b, a)
        buf.addVertex(m, x2, y1, 0f).setColor(r, g, b, a)
        buf.addVertex(m, x1, y1, 0f).setColor(r, g, b, a)

        buf.build()?.let { BufferUploader.drawWithShader(it) }
    }

    /**
     * Renderizado de la barra de progreso en el HUD del cliente.
     */
    fun renderHud(graphics: GuiGraphics) {
        val now = System.currentTimeMillis()

        if (!Status.isRunning && Status.finishTimeMs > 0L) {
            val remain = Status.finishTimeMs - now
            if (remain <= 0L) {
                Status.bannerAlpha = 0.0f
                Status.finishTimeMs = 0L
                return
            } else if (remain < 800L) {
                Status.bannerAlpha = (remain.toFloat() / 800f).coerceIn(0f, 1f)
            }
        }

        val alpha = Status.bannerAlpha
        if (alpha <= 0.01f) return

        val client = Minecraft.getInstance()
        val window = client.window ?: return
        val font = client.font

        val screenW = window.guiScaledWidth.toFloat()
        val cardW = 230f
        val cardH = 32f
        val cardX = (screenW - cardW) / 2f
        val cardY = 12f

        val prevProj = Matrix4f(RenderSystem.getProjectionMatrix())
        val prevSort = RenderSystem.getVertexSorting()

        val ortho = Matrix4f().setOrtho(0f, screenW, window.guiScaledHeight.toFloat(), 0f, -1000f, 1000f)
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z)

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()

        // 1. Fondo oscuro translúcido con borde sutil
        val bgAlpha = (alpha * 0.85f * 255f).toInt().coerceIn(0, 255)
        val borderAlpha = (alpha * 0.45f * 255f).toInt().coerceIn(0, 255)
        val bgColor = (bgAlpha shl 24) or 0x0E131F
        val borderColor = (borderAlpha shl 24) or 0x4F46E5

        // Borde exterior
        drawRect(cardX - 1f, cardY - 1f, cardX + cardW + 1f, cardY + cardH + 1f, borderColor)
        // Relleno interior
        drawRect(cardX, cardY, cardX + cardW, cardY + cardH, bgColor)

        // 2. Barra de progreso interior
        val barX = cardX + 10f
        val barY = cardY + 22f
        val barW = cardW - 20f
        val barH = 4f

        val barBgColor = ((alpha * 0.35f * 255f).toInt() shl 24) or 0x1E293B
        drawRect(barX, barY, barX + barW, barY + barH, barBgColor)

        val fillFraction = (Status.percent.toFloat() / 100f).coerceIn(0f, 1f)
        if (fillFraction > 0.005f) {
            val fillW = barW * fillFraction
            val barFillColor = ((alpha * 0.95f * 255f).toInt() shl 24) or 0x38BDF8 // Celeste brillante
            drawRect(barX, barY, barX + fillW, barY + barH, barFillColor)
        }

        // Restaurar estado para texto
        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        RenderSystem.setProjectionMatrix(prevProj, prevSort)

        // 3. Textos informativos
        val textAlphaInt = (alpha * 255f).toInt().coerceIn(4, 255)
        val textAlphaMask = textAlphaInt shl 24

        val title = if (Status.itemsTotal > 0) {
            "Descargando assets (${Status.itemsDone}/${Status.itemsTotal})"
        } else {
            Status.message
        }

        graphics.drawString(
            font,
            title,
            (cardX + 10f).toInt(),
            (cardY + 8f).toInt(),
            0xE0E7FF or textAlphaMask,
            false
        )

        val percentStr = "${Status.percent}%"
        val pctWidth = font.width(percentStr)
        graphics.drawString(
            font,
            percentStr,
            (cardX + cardW - 10f - pctWidth).toInt(),
            (cardY + 8f).toInt(),
            0x38BDF8 or textAlphaMask,
            false
        )
    }
}

