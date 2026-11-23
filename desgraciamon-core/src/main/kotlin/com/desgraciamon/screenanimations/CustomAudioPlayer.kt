package com.desgraciamon.screenanimations

import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundSource
import org.lwjgl.openal.AL10
import org.lwjgl.stb.STBVorbis
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

object CustomAudioPlayer {
    private var activeSource: Int = 0
    private var activeBuffer: Int = 0

    @Synchronized
    fun playOgg(oggBytes: ByteArray, volume: Float = 1.0f) {
        stop()
        if (oggBytes.isEmpty()) return

        runCatching {
            val directBuf = ByteBuffer.allocateDirect(oggBytes.size).order(ByteOrder.nativeOrder())
            directBuf.put(oggBytes).flip()

            MemoryStack.stackPush().use { stack ->
                val channels = stack.mallocInt(1)
                val sampleRate = stack.mallocInt(1)
                val pcm = STBVorbis.stb_vorbis_decode_memory(directBuf, channels, sampleRate)
                    ?: run {
                        ScreenAnimationsMod.logger.warn("STBVorbis failed to decode custom audio OGG bytes.")
                        return@runCatching
                    }

                try {
                    val buffer = AL10.alGenBuffers()
                    val channelCount = channels.get(0)
                    val format = if (channelCount == 1) AL10.AL_FORMAT_MONO16 else AL10.AL_FORMAT_STEREO16
                    AL10.alBufferData(buffer, format, pcm, sampleRate.get(0))

                    val source = AL10.alGenSources()
                    AL10.alSourcei(source, AL10.AL_BUFFER, buffer)

                    val masterVolume = runCatching {
                        Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER)
                    }.getOrDefault(1.0f)

                    val finalGain = (volume.coerceIn(0.0f, 1.0f) * masterVolume).coerceIn(0.0f, 1.0f)
                    AL10.alSourcef(source, AL10.AL_GAIN, finalGain)
                    AL10.alSourcePlay(source)

                    activeSource = source
                    activeBuffer = buffer
                } finally {
                    MemoryUtil.memFree(pcm)
                }
            }
        }.onFailure {
            ScreenAnimationsMod.logger.warn("Could not play custom audio OGG", it)
        }
    }

    @Synchronized
    fun stop() {
        if (activeSource != 0) {
            runCatching {
                AL10.alSourceStop(activeSource)
                AL10.alDeleteSources(activeSource)
            }
            activeSource = 0
        }
        if (activeBuffer != 0) {
            runCatching {
                AL10.alDeleteBuffers(activeBuffer)
            }
            activeBuffer = 0
        }
    }
}

