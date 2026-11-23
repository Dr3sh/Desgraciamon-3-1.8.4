package com.desgraciamon.mirror.mixin;

import com.desgraciamon.mirror.MirrorRules;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Las explosiones en el Mundo Espejo no rompen bloques ni dañan entidades (solo queda el sonido). */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private Level level;

    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void desgraciamon4mirror$cancelInMirror(CallbackInfo ci) {
        if (MirrorRules.isMirror(this.level) || MirrorRules.isRecuerdos(this.level)) ci.cancel();
    }
}

