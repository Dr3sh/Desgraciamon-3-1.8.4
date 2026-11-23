package com.desgraciamon.badges.mixin;

import com.desgraciamon.badges.BadgesClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$showOwnNameTagInDebug(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (BadgesClientState.getDebugShowOwnNameTag()) {
            Minecraft client = Minecraft.getInstance();
            if (entity == client.getCameraEntity() && Minecraft.renderNames() && !entity.isInvisible()) {
                cir.setReturnValue(true);
            }
        }
    }
}

