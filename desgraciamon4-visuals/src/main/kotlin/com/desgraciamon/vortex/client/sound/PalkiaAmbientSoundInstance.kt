package com.desgraciamon.vortex.client.sound

import com.desgraciamon.vortex.client.VortexShaderController
import com.desgraciamon.vortex.sound.DesgraciamonSounds
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import java.util.Locale

class PalkiaAmbientSoundInstance : AbstractTickableSoundInstance(
    DesgraciamonSounds.PALKIA_AMBIENT,
    SoundSource.AMBIENT,
    SoundInstance.createUnseededRandom()
) {
    init {
        this.looping = true
        this.delay = 0
        this.volume = 0.20f
        this.relative = true
        this.attenuation = SoundInstance.Attenuation.NONE
    }

    override fun tick() {
        val isPalkiaActive = VortexShaderController.isEventActiveClient &&
            VortexShaderController.currentEffectType.lowercase(Locale.ROOT) == "palkia"
        val intensity = VortexShaderController.currentIntensity

        if (!isPalkiaActive || intensity <= 0.002f) {
            this.stop()
            return
        }

        // Sonido ambiental cósmico suave de fondo, escalando con la intensidad del shader
        this.volume = 0.20f * intensity
    }
}

