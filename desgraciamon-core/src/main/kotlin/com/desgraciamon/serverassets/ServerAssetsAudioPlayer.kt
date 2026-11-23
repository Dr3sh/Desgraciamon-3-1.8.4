package com.desgraciamon.serverassets

import com.desgraciamon.screenanimations.CustomAudioPlayer
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundSource
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent
import kotlin.math.log10

object ServerAssetsAudioPlayer {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Desgraciamon-ServerAudio").apply { isDaemon = true }
    }

    @Volatile
    private var activeClip: Clip? = null

    @Volatile
    private var activeWaterMediaHandle: Any? = null

    fun play(file: Path, ext: String, volume: Float = 1.0f) {
        if (!Files.isRegularFile(file)) return
        executor.execute {
            runCatching {
                stopNonOgg()
                val master = runCatching {
                    Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER)
                }.getOrDefault(1.0f)
                val effectiveVolume = (volume.coerceIn(0.0f, 2.0f) * master).coerceIn(0.0f, 1.0f)
                if (effectiveVolume <= 0.001f) return@runCatching

                when (ext.lowercase()) {
                    "ogg" -> {
                        val bytes = Files.readAllBytes(file)
                        CustomAudioPlayer.playOgg(bytes, volume.coerceIn(0.0f, 1.0f))
                    }
                    "wav" -> {
                        playWav(Files.readAllBytes(file), effectiveVolume)
                    }
                    "mp3" -> {
                        if (FabricLoader.getInstance().isModLoaded("watermedia")) {
                            WaterMediaSoundBridge.playMp3(file, effectiveVolume)
                        } else {
                            ServerAssetsManager.LOGGER.warn("[ServerAssets] No se puede reproducir MP3 sin WaterMedia instalado: {}", file.fileName)
                        }
                    }
                }
            }.onFailure {
                ServerAssetsManager.LOGGER.warn("[ServerAssets] Error al reproducir sonido {}: {}", file.fileName, it.message)
            }
        }
    }

    fun stopAll() {
        executor.execute {
            runCatching { CustomAudioPlayer.stop() }
            stopNonOgg()
        }
    }

    private fun stopNonOgg() {
        activeClip?.let { clip ->
            runCatching {
                clip.stop()
                clip.close()
            }
            activeClip = null
        }
        if (activeWaterMediaHandle != null && FabricLoader.getInstance().isModLoaded("watermedia")) {
            WaterMediaSoundBridge.stop()
        }
    }

    private fun playWav(bytes: ByteArray, effectiveVolume: Float) {
        AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { ais ->
            val clip = AudioSystem.getClip()
            clip.open(ais)
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                val db = (20.0 * log10(effectiveVolume.toDouble().coerceAtLeast(0.0001))).toFloat()
                    .coerceIn(gain.minimum, gain.maximum)
                gain.value = db
            }
            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    runCatching { clip.close() }
                    if (activeClip === clip) {
                        activeClip = null
                    }
                }
            }
            activeClip = clip
            clip.start()
        }
    }

    private object WaterMediaSoundBridge {
        fun playMp3(file: Path, effectiveVolume: Float) {
            stop()
            runCatching {
                val uri = file.toUri()
                val mrl = org.watermedia.api.player.PlayerAPI.mrl(uri)
                val deadline = System.currentTimeMillis() + 3000L
                while (mrl.source(0) == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(25L)
                }
                if (mrl.source(0) == null) return@runCatching
                val player = org.watermedia.api.player.PlayerAPI.player(
                    mrl,
                    { null },
                    { runCatching { org.watermedia.api.player.PlayerAPI.alEngine() }.getOrNull() }
                ) ?: return@runCatching
                player.repeat(false)
                player.mute(false)
                player.volume((effectiveVolume * 100f).toInt().coerceIn(0, 100))
                player.start()
                activeWaterMediaHandle = player
            }.onFailure {
                ServerAssetsManager.LOGGER.warn("[ServerAssets] Error reproduciendo MP3 con WaterMedia: {}", it.message)
            }
        }

        fun stop() {
            val handle = activeWaterMediaHandle as? org.watermedia.api.player.MediaPlayer ?: return
            activeWaterMediaHandle = null
            runCatching { handle.release() }
        }
    }
}
