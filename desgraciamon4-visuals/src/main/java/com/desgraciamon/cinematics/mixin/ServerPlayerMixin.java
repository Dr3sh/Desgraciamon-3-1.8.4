package com.desgraciamon.cinematics.mixin;

import com.desgraciamon.cinematics.CinematicServerManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void desgraciamon$freezeDuringCinematic(CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (CinematicServerManager.INSTANCE.isPlayerFrozen(player.getUUID())) {
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0f;
        }
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$preventDamageDuringCinematic(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (CinematicServerManager.INSTANCE.isPlayerFrozen(player.getUUID())) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }
}

