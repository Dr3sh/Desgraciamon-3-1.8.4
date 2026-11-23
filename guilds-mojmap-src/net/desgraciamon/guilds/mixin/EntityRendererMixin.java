package net.desgraciamon.guilds.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.desgraciamon.guilds.GuildClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({EntityRenderer.class})
abstract class EntityRendererMixin {
   @ModifyVariable(
      method = {"renderNameTag"},
      at = @At("HEAD"),
      argsOnly = true
   )
   private Component desgraciamon$colorPlayerName(Component displayName, Entity entity) {
      if (entity instanceof AbstractClientPlayer player) {
         GuildClientState.NameTag guild;
         synchronized (GuildClientState.nameTags) {
            guild = GuildClientState.nameTags.get(player.getUUID());
         }

         if (guild != null && guild.name() != null && !guild.name().isBlank()) {
            int rgb = guild.color() & 16777215;
            return displayName.copy().withStyle(style -> style.withColor(TextColor.fromRgb(rgb)));
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
      Entity entity, Component displayName, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick, CallbackInfo callback
   ) {
      if (entity instanceof AbstractClientPlayer player) {
         GuildClientState.NameTag guild;
         synchronized (GuildClientState.nameTags) {
            guild = GuildClientState.nameTags.get(player.getUUID());
         }

         if (guild != null && guild.name() != null && !guild.name().isBlank()) {
            poseStack.pushPose();
            poseStack.translate(0.0F, 0.3F, 0.0F);
         }
      }
   }

   @Inject(
      method = {"renderNameTag"},
      at = {@At("RETURN")}
   )
   private void desgraciamon$renderGuildName(
      Entity entity, Component displayName, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick, CallbackInfo callback
   ) {
      if (entity instanceof AbstractClientPlayer player) {
         GuildClientState.NameTag guild;
         synchronized (GuildClientState.nameTags) {
            guild = GuildClientState.nameTags.get(player.getUUID());
         }

         if (guild != null && guild.name() != null && !guild.name().isBlank()) {
            Minecraft client = Minecraft.getInstance();
            if (client.getEntityRenderDispatcher().distanceToSqr(entity) <= 4096.0) {
               Vec3 vec3 = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
               if (vec3 != null) {
                  Font font = client.font;
                  Component text = Component.literal("<" + guild.name() + ">");
                  float x = (float)(-font.width(text)) / 2.0F;
                  float y = 10.5F;
                  boolean notSneaking = !entity.isDiscrete();
                  int bgAlpha = (int)(client.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
                  int color = 0xFF000000 | guild.color() & 16777215;
                  int fadedColor = color & 16777215 | 536870912;
                  poseStack.pushPose();
                  poseStack.translate(vec3.x, vec3.y + 0.5, vec3.z);
                  poseStack.mulPose(client.getEntityRenderDispatcher().cameraOrientation());
                  poseStack.scale(0.025F, -0.025F, 0.025F);
                  Matrix4f matrix = poseStack.last().pose();
                  font.drawInBatch(
                     text,
                     x,
                     y,
                     notSneaking ? fadedColor : color,
                     false,
                     matrix,
                     bufferSource,
                     notSneaking ? DisplayMode.SEE_THROUGH : DisplayMode.NORMAL,
                     bgAlpha,
                     packedLight
                  );
                  if (notSneaking) {
                     font.drawInBatch(text, x, y, color, false, matrix, bufferSource, DisplayMode.NORMAL, 0, packedLight);
                  }

                  poseStack.popPose();
               }
            }

            poseStack.popPose();
         }
      }
   }
}
