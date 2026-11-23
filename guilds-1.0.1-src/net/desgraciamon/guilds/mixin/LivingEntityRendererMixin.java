package net.desgraciamon.guilds.mixin;

import net.desgraciamon.guilds.GuildClientState;
import net.minecraft.class_1309;
import net.minecraft.class_310;
import net.minecraft.class_922;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({class_922.class})
abstract class LivingEntityRendererMixin {
   @Inject(
      method = {"shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void desgraciamon$showOwnNameTagInDebug(class_1309 entity, CallbackInfoReturnable<Boolean> cir) {
      if (GuildClientState.debugShowOwnNameTag) {
         class_310 client = class_310.method_1551();
         if (entity == client.method_1560() && class_310.method_1498() && !entity.method_5767()) {
            cir.setReturnValue(true);
         }
      }
   }
}
