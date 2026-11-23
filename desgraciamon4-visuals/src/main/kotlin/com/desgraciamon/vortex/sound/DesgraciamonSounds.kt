package com.desgraciamon.vortex.sound

import com.desgraciamon.vortex.DesgraciamonMod
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent

object DesgraciamonSounds {
    val DIALGA_AMBIENT_ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(DesgraciamonMod.MOD_ID, "dialga.ambient")
    val DIALGA_AMBIENT: SoundEvent = SoundEvent.createVariableRangeEvent(DIALGA_AMBIENT_ID)

    val PALKIA_AMBIENT_ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(DesgraciamonMod.MOD_ID, "palkia.ambient")
    val PALKIA_AMBIENT: SoundEvent = SoundEvent.createVariableRangeEvent(PALKIA_AMBIENT_ID)

    fun register() {
        Registry.register(BuiltInRegistries.SOUND_EVENT, DIALGA_AMBIENT_ID, DIALGA_AMBIENT)
        Registry.register(BuiltInRegistries.SOUND_EVENT, PALKIA_AMBIENT_ID, PALKIA_AMBIENT)
    }
}
