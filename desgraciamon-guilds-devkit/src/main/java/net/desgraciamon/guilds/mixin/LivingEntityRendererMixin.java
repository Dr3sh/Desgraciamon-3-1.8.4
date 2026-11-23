package net.desgraciamon.guilds.mixin;

import net.desgraciamon.guilds.GuildClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({LivingEntityRenderer.class})
abstract class LivingEntityRendererMixin {
   @Inject(
      method = {"shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void desgraciamon$showOwnNameTagInDebug(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
      if (GuildClientState.debugShowOwnNameTag) {
         Minecraft client = Minecraft.getInstance();
         if (entity == client.getCameraEntity() && Minecraft.renderNames() && !entity.isInvisible()) {
            cir.setReturnValue(true);
         }
      }
   }
}
