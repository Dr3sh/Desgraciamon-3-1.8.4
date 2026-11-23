package net.desgraciamon.guilds.mixin;

import net.desgraciamon.guilds.GuildClientState;
import net.minecraft.class_1297;
import net.minecraft.class_243;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_4587;
import net.minecraft.class_4597;
import net.minecraft.class_5251;
import net.minecraft.class_742;
import net.minecraft.class_897;
import net.minecraft.class_9064;
import net.minecraft.class_327.class_6415;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({class_897.class})
abstract class EntityRendererMixin {
   @ModifyVariable(
      method = {"renderNameTag"},
      at = @At("HEAD"),
      argsOnly = true
   )
   private class_2561 desgraciamon$colorPlayerName(class_2561 displayName, class_1297 entity) {
      if (entity instanceof class_742 player) {
         GuildClientState.NameTag guild;
         synchronized (GuildClientState.nameTags) {
            guild = GuildClientState.nameTags.get(player.method_5667());
         }

         if (guild != null && guild.name() != null && !guild.name().isBlank()) {
            int rgb = guild.color() & 16777215;
            return displayName.method_27661().method_27694(style -> style.method_27703(class_5251.method_27717(rgb)));
         } else {
            return displayName;
         }
      } else {
         return displayName;
      }
   }

   @Inject(
      method = {"renderNameTag"},
      at = {@At("HEAD")}
   )
   private void desgraciamon$raiseNameTagIfInGuild(
      class_1297 entity, class_2561 displayName, class_4587 poseStack, class_4597 bufferSource, int packedLight, float partialTick, CallbackInfo callback
   ) {
      if (entity instanceof class_742 player) {
         GuildClientState.NameTag guild;
         synchronized (GuildClientState.nameTags) {
            guild = GuildClientState.nameTags.get(player.method_5667());
         }

         if (guild != null && guild.name() != null && !guild.name().isBlank()) {
            poseStack.method_22903();
            poseStack.method_46416(0.0F, 0.3F, 0.0F);
         }
      }
   }

   @Inject(
      method = {"renderNameTag"},
      at = {@At("RETURN")}
   )
   private void desgraciamon$renderGuildName(
      class_1297 entity, class_2561 displayName, class_4587 poseStack, class_4597 bufferSource, int packedLight, float partialTick, CallbackInfo callback
   ) {
      if (entity instanceof class_742 player) {
         GuildClientState.NameTag guild;
         synchronized (GuildClientState.nameTags) {
            guild = GuildClientState.nameTags.get(player.method_5667());
         }

         if (guild != null && guild.name() != null && !guild.name().isBlank()) {
            class_310 client = class_310.method_1551();
            if (client.method_1561().method_23168(entity) <= 4096.0) {
               class_243 vec3 = entity.method_56072().method_55675(class_9064.field_47745, 0, entity.method_5705(partialTick));
               if (vec3 != null) {
                  class_327 font = client.field_1772;
                  class_2561 text = class_2561.method_43470("<" + guild.name() + ">");
                  float x = (float)(-font.method_27525(text)) / 2.0F;
                  float y = 10.5F;
                  boolean notSneaking = !entity.method_21751();
                  int bgAlpha = (int)(client.field_1690.method_19343(0.25F) * 255.0F) << 24;
                  int color = 0xFF000000 | guild.color() & 16777215;
                  int fadedColor = color & 16777215 | 536870912;
                  poseStack.method_22903();
                  poseStack.method_22904(vec3.field_1352, vec3.field_1351 + 0.5, vec3.field_1350);
                  poseStack.method_22907(client.method_1561().method_24197());
                  poseStack.method_22905(0.025F, -0.025F, 0.025F);
                  Matrix4f matrix = poseStack.method_23760().method_23761();
                  font.method_30882(
                     text,
                     x,
                     y,
                     notSneaking ? fadedColor : color,
                     false,
                     matrix,
                     bufferSource,
                     notSneaking ? class_6415.field_33994 : class_6415.field_33993,
                     bgAlpha,
                     packedLight
                  );
                  if (notSneaking) {
                     font.method_30882(text, x, y, color, false, matrix, bufferSource, class_6415.field_33993, 0, packedLight);
                  }

                  poseStack.method_22909();
               }
            }

            poseStack.method_22909();
         }
      }
   }
}
