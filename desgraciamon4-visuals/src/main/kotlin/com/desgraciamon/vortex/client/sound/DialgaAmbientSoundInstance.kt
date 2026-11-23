package com.desgraciamon.vortex.client.sound

import com.desgraciamon.vortex.client.VortexShaderController
import com.desgraciamon.vortex.sound.DesgraciamonSounds
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import java.util.Locale

class DialgaAmbientSoundInstance : AbstractTickableSoundInstance(
    DesgraciamonSounds.DIALGA_AMBIENT,
    SoundSource.AMBIENT,
    SoundInstance.createUnseededRandom()
) {
    init {
        this.looping = true
        this.delay = 0
        this.volume = 0.18f
        this.relative = true
        this.attenuation = SoundInstance.Attenuation.NONE
    }

    override fun tick() {
        val isDialgaActive = VortexShaderController.isEventActiveClient &&
            VortexShaderController.currentEffectType.lowercase(Locale.ROOT) == "dialga"
        val intensity = VortexShaderController.currentIntensity

        if (!isDialgaActive || intensity <= 0.002f) {
            this.stop()
            return
        }

        // Volumen bastante bajito de fondo (0.18f), escalando con la intensidad del shader
        this.volume = 0.18f * intensity
    }
}

