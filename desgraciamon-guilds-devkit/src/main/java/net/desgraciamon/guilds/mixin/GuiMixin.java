package net.desgraciamon.guilds.mixin;

import net.desgraciamon.guilds.GuildSynergyHudHelper;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Gui.class})
public abstract class GuiMixin {
   @Shadow
   public abstract Font getFont();

   @Inject(
      method = {"renderExperienceBar"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void onRenderExperienceBar(GuiGraphics graphics, int x, CallbackInfo ci) {
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
   private void onRenderExperienceLevel(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
      if (GuildSynergyHudHelper.isSynergyActive()) {
         GuildSynergyHudHelper.renderSynergyLevel(graphics, this.getFont());
         ci.cancel();
      }
   }
}
