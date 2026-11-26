package com.desgraciamon.showdownarena

import de.maxhenkel.voicechat.api.VoicechatClientApi
import de.maxhenkel.voicechat.api.audiochannel.ClientLocationalAudioChannel
import de.maxhenkel.voicechat.api.audiochannel.ClientStaticAudioChannel
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.io.BufferedInputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ArenaMusicClient {
    private const val FADE_DISTANCE = 30.0f
    private const val ACTIVATION_DISTANCE = 30.0
    private const val BASE_GAIN = 0.178f // -15 dB default background level
    private const val SAMPLE_RATE = 48_000
    private const val FRAME_SAMPLES = 960 // 20 ms at 48,000 Hz mono
    private const val FRAME_NS = 20_000_000L
    private val EMPTY_FRAME = ShortArray(0)

    private val CHANNEL_UUID = UUID.nameUUIDFromBytes("showdown_arena:dome_music".toByteArray())
    private val STATIC_CHANNEL_UUID = UUID.nameUUIDFromBytes("showdown_arena:dome_music_static".toByteArray())

    @Volatile
    private var voicechatApi: VoicechatClientApi? = null

    @Volatile
    private var voicechatConnected = false

    @Volatile
    private var channel: ClientLocationalAudioChannel? = null

    @Volatile
    private var staticChannel: ClientStaticAudioChannel? = null

    @Volatile
    private var useStaticChannel = false

    @Volatile
    private var playlistTracks: List<ArenaPlaylistTrack> = emptyList()

    @Volatile
    private var playlistEpochOffsetMs: Long = 0L

    @Volatile
    private var totalPlaylistDurationMs: Long = 0L

    @Volatile
    private var shouldPlay = false

    @Volatile
    private var currentSpatialGain = 0.0f

    @Volatile
    private var running = false

    @Volatile
    private var wasInAudioRange = false

    @Volatile
    private var lastAnnouncedTrackIdx = -1

    @Volatile
    var bassLevel: Float = 0.0f
        private set

    val spectrumBands: FloatArray = FloatArray(8)

    private var workerThread: Thread? = null
    private val workerGeneration = AtomicInteger(0)

    @Volatile
    private var predecodedTrackId: String? = null

    @Volatile
    private var predecodedFuture: CompletableFuture<ShortArray?>? = null

    @Volatile
    private var lastUsedStatic: Boolean? = null

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(ArenaPlaylistSyncPayload.ID) { payload, _ ->
            val nowLocal = System.currentTimeMillis()
            playlistEpochOffsetMs = payload.serverEpochMs - nowLocal
            playlistTracks = payload.tracks
            totalPlaylistDurationMs = payload.tracks.sumOf { it.durationMs.coerceAtLeast(1000L) }
        }
    }

    fun onVoicechatInit(api: VoicechatClientApi) {
        voicechatApi = api
    }

    fun onVoicechatConnection(connected: Boolean) {
        voicechatConnected = connected
        if (!connected) {
            channel = null
            staticChannel = null
            stopWorker()
        }
    }

    fun tick(client: Minecraft) {
        val player = client.player
        val level = client.level
        val zone = ArenaZoneClient.currentZone
        val api = voicechatApi

        if (player == null || level == null || !ArenaZoneClient.isEnabled || zone == null || playlistTracks.isEmpty() || api == null || !voicechatConnected) {
            shouldPlay = false
            stopWorker()
            return
        }

        val dim = level.dimension().location().toString()
        if (zone.dimension != dim) {
            shouldPlay = false
            stopWorker()
            return
        }

        val centerX = (minOf(zone.minX, zone.maxX) + maxOf(zone.minX, zone.maxX)) * 0.5
        val centerZ = (minOf(zone.minZ, zone.maxZ) + maxOf(zone.minZ, zone.maxZ)) * 0.5
        val toCenterDx = centerX - player.x
        val toCenterDz = centerZ - player.z
        val toCenterHoriz = sqrt(toCenterDx * toCenterDx + toCenterDz * toCenterDz)
        val dirCenterX = if (toCenterHoriz > 0.01) toCenterDx / toCenterHoriz else 1.0
        val dirCenterZ = if (toCenterHoriz > 0.01) toCenterDz / toCenterHoriz else 0.0

        val inside = zone.contains(player)
        val (chanX, chanY, chanZ, distOutside) = if (inside) {
            // Keep a valid 1-block 3D vector toward the dome center so OpenAL never gets a zero-length vector
            Quad(player.x + dirCenterX, player.eyeY, player.z + dirCenterZ, 0.0)
        } else {
            val (ex, ey, ez) = zone.nearestPointOnDome(player.x, player.eyeY, player.z)
            val dx = ex - player.x
            val dy = ey - player.eyeY
            val dz = ez - player.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            // Position channel at (dist + 1.0) toward the nearest dome edge so SVC attenuates smoothly over 15 blocks
            val offset = dist + 1.0
            val inv = if (dist > 0.001) 1.0 / dist else 0.0
            Quad(player.x + dx * inv * offset, player.eyeY + dy * inv * offset, player.z + dz * inv * offset, dist)
        }

        if (distOutside > ACTIVATION_DISTANCE) {
            shouldPlay = false
            currentSpatialGain = 0.0f
            wasInAudioRange = false
            lastAnnouncedTrackIdx = -1
            stopWorker()
            return
        }

        // Only show the song title in the actionbar when actually inside the dome
        if (inside) {
            val (trackIdx, _) = computeSyncedPosition(serverTimeNowMs())
            if (!wasInAudioRange || trackIdx != lastAnnouncedTrackIdx) {
                wasInAudioRange = true
                lastAnnouncedTrackIdx = trackIdx
                val trackTitle = playlistTracks.getOrNull(trackIdx)?.title
                if (!trackTitle.isNullOrBlank()) {
                    player.displayClientMessage(Component.literal("§b♫ §f$trackTitle"), true)
                }
            }
        } else {
            wasInAudioRange = false
        }

        currentSpatialGain = if (inside) {
            1.0f
        } else {
            val t = (distOutside / FADE_DISTANCE).toFloat().coerceIn(0.0f, 1.0f)
            (1.0f - t) * (1.0f - t * 0.25f)
        }.coerceIn(0.0f, 1.0f)

        useStaticChannel = inside
        if (inside) {
            ensureStaticChannel(api)
        } else {
            val ch = ensureChannel(api, chanX, chanY, chanZ)
            ch.setLocation(api.createPosition(chanX, chanY, chanZ))
            ch.setDistance(FADE_DISTANCE)
        }

        shouldPlay = true
        ensureWorker()
    }

    private fun ensureStaticChannel(api: VoicechatClientApi): ClientStaticAudioChannel {
        val existing = staticChannel
        if (existing != null) return existing
        val created = api.createStaticAudioChannel(STATIC_CHANNEL_UUID)
        created.setCategory(ShowdownVoicechatPlugin.CATEGORY_ID)
        staticChannel = created
        return created
    }

    private fun ensureChannel(api: VoicechatClientApi, x: Double, y: Double, z: Double): ClientLocationalAudioChannel {
        val existing = channel
        if (existing != null) return existing
        val created = api.createLocationalAudioChannel(CHANNEL_UUID, api.createPosition(x, y, z))
        created.setCategory(ShowdownVoicechatPlugin.CATEGORY_ID)
        created.setDistance(FADE_DISTANCE)
        channel = created
        return created
    }

    @Synchronized
    private fun ensureWorker() {
        if (running && workerThread?.isAlive == true) return
        running = true
        val gen = workerGeneration.incrementAndGet()
        val t = Thread({ audioLoop(gen) }, "ArenaMusic-SVC-Player-$gen")
        t.isDaemon = true
        workerThread = t
        t.start()
    }

    @Synchronized
    private fun stopWorker() {
        running = false
        workerGeneration.incrementAndGet()
        workerThread?.interrupt()
        workerThread = null
        runCatching { staticChannel?.play(EMPTY_FRAME) }
        runCatching { channel?.play(EMPTY_FRAME) }
    }

    fun reset() {
        shouldPlay = false
        currentSpatialGain = 0.0f
        wasInAudioRange = false
        lastAnnouncedTrackIdx = -1
        lastUsedStatic = null
        bassLevel = 0.0f
        spectrumBands.fill(0.0f)
        stopWorker()
        channel = null
        staticChannel = null
        predecodedTrackId = null
        predecodedFuture = null
        playlistTracks = emptyList()
        totalPlaylistDurationMs = 0L
    }

    private fun serverTimeNowMs(): Long = System.currentTimeMillis() + playlistEpochOffsetMs

    private fun computeSyncedPosition(serverMs: Long): Pair<Int, Long> {
        val tracks = playlistTracks
        val total = totalPlaylistDurationMs
        if (tracks.isEmpty() || total <= 0L) return 0 to 0L
        var pos = ((serverMs % total) + total) % total
        for (i in tracks.indices) {
            val dur = tracks[i].durationMs.coerceAtLeast(1000L)
            if (pos < dur) {
                return i to pos
            }
            pos -= dur
        }
        return 0 to 0L
    }

    private fun decodeTrackPcm(api: VoicechatClientApi, trackId: String): ShortArray? {
        val resourcePath = "/assets/showdown_arena/music/$trackId.mp3"
        val rawStream = ArenaMusicClient::class.java.getResourceAsStream(resourcePath)
        if (rawStream == null) {
            println("[ArenaMusic] Missing MP3 resource: $resourcePath")
            return null
        }
        return rawStream.use { stream ->
            runCatching {
                val decoder = api.createMp3Decoder(BufferedInputStream(stream, 32_768))
                if (decoder == null) {
                    println("[ArenaMusic] createMp3Decoder returned null for $trackId")
                    return null
                }
                val samples = decoder.decode()
                val format = decoder.audioFormat
                println("[ArenaMusic] Decoded $trackId: samples=${samples.size}, rate=${format?.sampleRate}, ch=${format?.channels}")
                if (format != null && format.channels == 2) {
                    val mono = ShortArray(samples.size / 2)
                    for (i in mono.indices) {
                        mono[i] = ((samples[i * 2].toInt() + samples[i * 2 + 1].toInt()) / 2).toShort()
                    }
                    mono
                } else {
                    samples
                }
            }.onFailure { err ->
                println("[ArenaMusic] Failed decoding $trackId: ${err.message}")
            }.getOrNull()
        }
    }

    private fun takeOrDecodeTrack(api: VoicechatClientApi, trackId: String): ShortArray? {
        val future = predecodedFuture
        if (predecodedTrackId == trackId && future != null) {
            predecodedTrackId = null
            predecodedFuture = null
            val cached = runCatching { future.get() }.getOrNull()
            if (cached != null && cached.isNotEmpty()) return cached
        }
        return decodeTrackPcm(api, trackId)
    }

    private fun schedulePredecode(api: VoicechatClientApi, nextTrackId: String) {
        if (predecodedTrackId == nextTrackId && predecodedFuture != null) return
        predecodedTrackId = nextTrackId
        predecodedFuture = CompletableFuture.supplyAsync { decodeTrackPcm(api, nextTrackId) }
    }

    private fun audioLoop(gen: Int) {
        var forcedNextTrackIdx: Int? = null
        try {
            while (running && shouldPlay && workerGeneration.get() == gen) {
                val api = voicechatApi ?: break
                val tracks = playlistTracks
                if (tracks.isEmpty()) break

                val (syncedIdx, syncedPosMs) = computeSyncedPosition(serverTimeNowMs())
                val trackIdx = if (forcedNextTrackIdx != null) {
                    val idx = forcedNextTrackIdx!!.mod(tracks.size)
                    forcedNextTrackIdx = null
                    idx
                } else {
                    syncedIdx
                }
                val trackPosMs = if (trackIdx == syncedIdx) syncedPosMs else 0L
                val track = tracks.getOrNull(trackIdx) ?: break

                val pcm = takeOrDecodeTrack(api, track.trackId)
                if (pcm == null || pcm.isEmpty()) {
                    forcedNextTrackIdx = (trackIdx + 1) % tracks.size
                    try {
                        Thread.sleep(250L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                // Pre-decode the next track asynchronously so track transitions are gapless
                val nextIdx = (trackIdx + 1) % tracks.size
                schedulePredecode(api, tracks[nextIdx].trackId)

                var sampleIdx = ((trackPosMs * SAMPLE_RATE) / 1000L).toInt().coerceIn(0, (pcm.size - 1).coerceAtLeast(0))
                var nextFrameNs = System.nanoTime()
                var frameCount = 0

                while (running && shouldPlay && workerGeneration.get() == gen && sampleIdx < pcm.size) {
                    if (Minecraft.getInstance().level == null) break
                    // Resync only if we are far off (> 4s) and not near the very end of the track
                    val (expectedIdx, expectedPosMs) = computeSyncedPosition(serverTimeNowMs())
                    if (expectedIdx != trackIdx && expectedPosMs > 3000L && frameCount > 150) {
                        break
                    }

                    // Micro fade-in over first 40 frames (0.8s) to avoid click on entry
                    val fadeInMul = if (frameCount < 40) (frameCount + 1) / 40.0f else 1.0f
                    val gain = BASE_GAIN * fadeInMul * currentSpatialGain

                    val frame = ShortArray(FRAME_SAMPLES)
                    val remaining = pcm.size - sampleIdx
                    val toCopy = minOf(FRAME_SAMPLES, remaining)

                    var lpBass = 0.0f
                    var lpLowMid = 0.0f
                    var lpMid = 0.0f
                    var sumBass = 0.0f
                    var sumLowMid = 0.0f
                    var sumMid = 0.0f
                    var sumHigh = 0.0f

                    for (i in 0 until toCopy) {
                        val rawSample = pcm[sampleIdx + i]
                        val x = rawSample / 32768.0f
                        lpBass += 0.032f * (x - lpBass)
                        lpLowMid += 0.105f * (x - lpLowMid)
                        lpMid += 0.30f * (x - lpMid)
                        val lowMid = lpLowMid - lpBass
                        val mid = lpMid - lpLowMid
                        val high = x - lpMid
                        sumBass += lpBass * lpBass
                        sumLowMid += lowMid * lowMid
                        sumMid += mid * mid
                        sumHigh += high * high

                        val scaled = (rawSample * gain).roundToInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        frame[i] = scaled.toShort()
                    }
                    sampleIdx += toCopy

                    if (toCopy > 0 && currentSpatialGain > 0.01f) {
                        val invN = 1.0f / toCopy
                        val rmsBass = kotlin.math.sqrt(sumBass * invN) * 4.2f
                        val rmsLowMid = kotlin.math.sqrt(sumLowMid * invN) * 4.8f
                        val rmsMid = kotlin.math.sqrt(sumMid * invN) * 5.2f
                        val rmsHigh = kotlin.math.sqrt(sumHigh * invN) * 6.0f
                        val targets = floatArrayOf(
                            rmsBass.coerceIn(0f, 1f),
                            ((rmsBass * 0.65f + rmsLowMid * 0.35f)).coerceIn(0f, 1f),
                            rmsLowMid.coerceIn(0f, 1f),
                            ((rmsLowMid * 0.55f + rmsMid * 0.45f)).coerceIn(0f, 1f),
                            rmsMid.coerceIn(0f, 1f),
                            ((rmsMid * 0.55f + rmsHigh * 0.45f)).coerceIn(0f, 1f),
                            rmsHigh.coerceIn(0f, 1f),
                            ((rmsBass * 0.4f + rmsHigh * 0.6f)).coerceIn(0f, 1f)
                        )
                        for (b in 0 until 8) {
                            val prev = spectrumBands[b]
                            val tVal = targets[b]
                            val coeff = if (tVal > prev) 0.65f else 0.22f
                            spectrumBands[b] = prev + (tVal - prev) * coeff
                        }
                        val targetBass = (rmsBass * rmsBass * 1.15f).coerceIn(0f, 1f)
                        val bassCoeff = if (targetBass > bassLevel) 0.72f else 0.20f
                        bassLevel += (targetBass - bassLevel) * bassCoeff
                    } else {
                        for (b in 0 until 8) spectrumBands[b] *= 0.80f
                        bassLevel *= 0.80f
                    }

                    if (voicechatConnected) {
                        val wantStatic = useStaticChannel
                        val prevStatic = lastUsedStatic
                        if (prevStatic != null && prevStatic != wantStatic) {
                            if (prevStatic) runCatching { staticChannel?.play(EMPTY_FRAME) } else runCatching { channel?.play(EMPTY_FRAME) }
                        }
                        lastUsedStatic = wantStatic
                        if (wantStatic) {
                            staticChannel?.play(frame)
                        } else {
                            channel?.play(frame)
                        }
                    }

                    frameCount++
                    nextFrameNs += FRAME_NS
                    val sleepNs = nextFrameNs - System.nanoTime()
                    if (sleepNs > 0L) {
                        val ms = sleepNs / 1_000_000L
                        val ns = (sleepNs % 1_000_000L).toInt()
                        try {
                            Thread.sleep(ms, ns)
                        } catch (_: InterruptedException) {
                            break
                        }
                    } else if (sleepNs < -200_000_000L) {
                        nextFrameNs = System.nanoTime()
                    }
                }

                // If the track finished naturally at end of PCM, loop seamlessly to the next track
                if (running && shouldPlay && workerGeneration.get() == gen && sampleIdx >= pcm.size) {
                    forcedNextTrackIdx = nextIdx
                }
            }
        } finally {
            if (workerGeneration.get() == gen) {
                running = false
            }
        }
    }

    private data class Quad(val x: Double, val y: Double, val z: Double, val dist: Double)
}
