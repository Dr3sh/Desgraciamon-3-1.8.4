package net.desgraciamon.guilds.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.desgraciamon.guilds.GuildClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
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
   private static final ResourceLocation ROLE_PAWN = ResourceLocation.fromNamespaceAndPath("desgraciamon_guilds", "textures/gui/roles/pawn.png");
   private static final ResourceLocation ROLE_KNIGHT = ResourceLocation.fromNamespaceAndPath("desgraciamon_guilds", "textures/gui/roles/knight.png");
   private static final ResourceLocation ROLE_KING = ResourceLocation.fromNamespaceAndPath("desgraciamon_guilds", "textures/gui/roles/king.png");

   private static ResourceLocation desgraciamon$roleTexture(String role) {
      if ("LEADER".equals(role)) {
         return ROLE_KING;
      } else if ("OFFICER".equals(role)) {
         return ROLE_KNIGHT;
      } else {
         return ROLE_PAWN;
      }
   }

   private static void desgraciamon$drawRoleIcon(
      Matrix4f matrix, MultiBufferSource bufferSource, ResourceLocation texture, float x, float y, float size, int alpha, boolean seeThrough, int packedLight
   ) {
      VertexConsumer consumer = bufferSource.getBuffer(seeThrough ? RenderType.textSeeThrough(texture) : RenderType.text(texture));
      float x2 = x + size;
      float y2 = y + size;
      consumer.addVertex(matrix, x, y, 0.0F).setColor(255, 255, 255, alpha).setUv(0.0F, 0.0F).setLight(packedLight);
      consumer.addVertex(matrix, x, y2, 0.0F).setColor(255, 255, 255, alpha).setUv(0.0F, 1.0F).setLight(packedLight);
      consumer.addVertex(matrix, x2, y2, 0.0F).setColor(255, 255, 255, alpha).setUv(1.0F, 1.0F).setLight(packedLight);
      consumer.addVertex(matrix, x2, y, 0.0F).setColor(255, 255, 255, alpha).setUv(1.0F, 0.0F).setLight(packedLight);
   }

   private static void desgraciamon$drawIconBg(
      Matrix4f matrix, MultiBufferSource bufferSource, float x1, float y1, float x2, float y2, int bgAlpha, boolean seeThrough, int packedLight
   ) {
      if (bgAlpha == 0) {
         return;
      }
      VertexConsumer bg = bufferSource.getBuffer(seeThrough ? RenderType.textBackgroundSeeThrough() : RenderType.textBackground());
      bg.addVertex(matrix, x1, y1, 0.0F).setColor(bgAlpha).setLight(packedLight);
      bg.addVertex(matrix, x1, y2, 0.0F).setColor(bgAlpha).setLight(packedLight);
      bg.addVertex(matrix, x2, y2, 0.0F).setColor(bgAlpha).setLight(packedLight);
      bg.addVertex(matrix, x2, y1, 0.0F).setColor(bgAlpha).setLight(packedLight);
   }
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
                  float iconSize = 8.0F;
                  float iconGap = 2.0F;
                  float textWidth = (float)font.width(text);
                  float totalWidth = iconSize + iconGap + textWidth;
                  float iconX = -totalWidth / 2.0F;
                  float textX = iconX + iconSize + iconGap;
                  float y = 10.5F;
                  float iconY = y - 0.5F;
                  boolean notSneaking = !entity.isDiscrete();
                  int bgAlpha = (int)(client.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
                  int color = 0xFF000000 | guild.color() & 16777215;
                  int fadedColor = color & 16777215 | 536870912;
                  ResourceLocation roleTex = desgraciamon$roleTexture(guild.role());
                  poseStack.pushPose();
                  poseStack.translate(vec3.x, vec3.y + 0.5, vec3.z);
                  poseStack.mulPose(client.getEntityRenderDispatcher().cameraOrientation());
                  poseStack.scale(0.025F, -0.025F, 0.025F);
                  Matrix4f matrix = poseStack.last().pose();
                  desgraciamon$drawIconBg(matrix, bufferSource, iconX - 1.0F, y - 1.0F, textX - 1.0F, y + 9.0F, bgAlpha, notSneaking, packedLight);
                  if (notSneaking) {
                     desgraciamon$drawRoleIcon(matrix, bufferSource, roleTex, iconX, iconY, iconSize, 80, true, packedLight);
                  }
                  desgraciamon$drawRoleIcon(matrix, bufferSource, roleTex, iconX, iconY, iconSize, 255, false, packedLight);
                  font.drawInBatch(
                     text,
                     textX,
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
                     font.drawInBatch(text, textX, y, color, false, matrix, bufferSource, DisplayMode.NORMAL, 0, packedLight);
                  }

                  poseStack.popPose();
               }
            }

            poseStack.popPose();
         }
      }
   }
}
