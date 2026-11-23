package com.desgraciamon.badges.mixin;

import com.desgraciamon.badges.BadgesClientState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EntityRenderer.class, priority = 900)
public abstract class EntityRendererMixin<T extends Entity> {

    @Inject(method = "renderNameTag", at = @At("RETURN"))
    private void desgraciamon$renderBadgesLeftOfCenteredName(
            T entity,
            Component displayName,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            float partialTick,
            CallbackInfo ci
    ) {
        if (!(entity instanceof AbstractClientPlayer player)) return;
        if (!BadgesClientState.hasVisibleNameTagBadges(player.getUUID())) return;

        Minecraft client = Minecraft.getInstance();
        if (client.getEntityRenderDispatcher().distanceToSqr(entity) > 4096.0D) return;
        Vec3 vec3 = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
        if (vec3 == null) return;

        Font font = client.font;
        boolean notSneaking = !entity.isDiscrete();
        int y = "deadmau5".equals(displayName.getString()) ? -10 : 0;
        // El nombre del jugador esta centrado en [-nameWidth / 2.0F, +nameWidth / 2.0F].
        // Dibujamos las insignias justo a la derecha de (+nameWidth / 2.0F), sin mover el nombre.
        float nameHalfWidth = font.width(displayName) / 2.0F;
        float gap = 3.0F;
        float x = nameHalfWidth + gap;

        int bgAlpha = (int) (client.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;

        poseStack.pushPose();
        poseStack.translate(vec3.x, vec3.y + 0.5D, vec3.z);
        poseStack.mulPose(client.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(0.025F, -0.025F, 0.025F);
        Matrix4f matrix = poseStack.last().pose();

        BadgesClientState.renderNameTagBadges(
                player.getUUID(), font, matrix, bufferSource,
                x, y, notSneaking, bgAlpha, packedLight
        );

        poseStack.popPose();
    }
}

