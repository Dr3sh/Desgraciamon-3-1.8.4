package com.desgraciamon.vortex.mixin;

import com.desgraciamon.vortex.server.VortexManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerFlightMixin extends LivingEntity {

    protected PlayerFlightMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Shadow
    public abstract void stopFallFlying();

    /**
     * Módulo 3.2: Restricción Vanilla - Evaluar isFallFlying() (Elytras) en cada tick del jugador
     * y al intentar iniciar el planeo con Elytra. Si el evento está activo con No-Fly, forzar desmontaje/cierre
     * y aplicar SLOW_FALLING por 5 segundos.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void desgraciamon$enforceNoFlyTick(CallbackInfo ci) {
        if (!this.level().isClientSide() && (Object) this instanceof ServerPlayer serverPlayer) {
            if (VortexManager.INSTANCE.isVortexActive() && VortexManager.INSTANCE.isNoFlyActive()) {
                if (this.isFallFlying()) {
                    this.stopFallFlying();
                    VortexManager.INSTANCE.applySlowFallingSafeguard(serverPlayer);
                }
            }
        }
    }

    @Inject(method = "tryToStartFallFlying", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$preventStartFallFlying(CallbackInfoReturnable<Boolean> cir) {
        if (VortexManager.INSTANCE.isVortexActive() && VortexManager.INSTANCE.isNoFlyActive()) {
            if (!this.level().isClientSide() && (Object) this instanceof ServerPlayer serverPlayer) {
                if (!serverPlayer.isCreative() && !serverPlayer.isSpectator()) {
                    VortexManager.INSTANCE.applySlowFallingSafeguard(serverPlayer);
                    cir.setReturnValue(false);
                }
            }
        }
    }
}

