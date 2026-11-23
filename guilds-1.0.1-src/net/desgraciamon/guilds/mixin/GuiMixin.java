package net.desgraciamon.guilds.mixin;

import net.desgraciamon.guilds.GuildSynergyHudHelper;
import net.minecraft.class_327;
import net.minecraft.class_329;
import net.minecraft.class_332;
import net.minecraft.class_9779;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({class_329.class})
public abstract class GuiMixin {
   @Shadow
   public abstract class_327 method_1756();

   @Inject(
      method = {"renderExperienceBar"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void onRenderExperienceBar(class_332 graphics, int x, CallbackInfo ci) {
      if (GuildSynergyHudHelper.isSynergyActive()) {
         GuildSynergyHudHelper.renderSynergyBar(graphics, x);
         ci.cancel();
      }
   }

   @Inject(
      method = {"renderExperienceLevel"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void onRenderExperienceLevel(class_332 graphics, class_9779 deltaTracker, CallbackInfo ci) {
      if (GuildSynergyHudHelper.isSynergyActive()) {
         GuildSynergyHudHelper.renderSynergyLevel(graphics, this.method_1756());
         ci.cancel();
      }
   }
}
