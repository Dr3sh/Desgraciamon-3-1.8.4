package com.desgraciamon.serverassets.mixin;

import com.desgraciamon.prismtext.PrismTextClient;
import com.desgraciamon.serverassets.ServerAssetsClientState;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public class ServerEmojiRenderOutputMixin {

    @Shadow @Final MultiBufferSource bufferSource;
    @Shadow @Final private boolean dropShadow;
    @Shadow @Final private float a;
    @Shadow @Final private Matrix4f pose;
    @Shadow @Final private Font.DisplayMode mode;
    @Shadow @Final private int packedLightCoords;
    @Shadow float x;
    @Shadow float y;

    @Inject(method = "accept(ILnet/minecraft/network/chat/Style;I)Z", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$renderServerEmoji(int positionInCurrentSequence, Style style, int codePoint, CallbackInfoReturnable<Boolean> cir) {
        ResourceLocation tex = ServerAssetsClientState.getEmojiTextureForCodepoint(codePoint);
        if (tex == null) return;

        if (!this.dropShadow) {
            RenderType renderType = switch (this.mode) {
                case SEE_THROUGH -> RenderType.textSeeThrough(tex);
                case POLYGON_OFFSET -> RenderType.textPolygonOffset(tex);
                default -> RenderType.text(tex);
            };
            VertexConsumer vc = this.bufferSource.getBuffer(renderType);
            int alpha = Math.max(0, Math.min(255, (int) (this.a * 255.0F)));
            Style animatedStyle = PrismTextClient.animateStyle(style);
            TextColor textColor = animatedStyle.getColor();
            int r = 255;
            int g = 255;
            int b = 255;
            if (textColor != null) {
                int rgb = textColor.getValue();
                r = (rgb >> 16) & 0xFF;
                g = (rgb >> 8) & 0xFF;
                b = rgb & 0xFF;
            }
            float x0 = this.x;
            float y0 = this.y - 0.5F;
            float size = 8.5F;
            vc.addVertex(this.pose, x0, y0, 0.0F).setColor(r, g, b, alpha).setUv(0.0F, 0.0F).setLight(this.packedLightCoords);
            vc.addVertex(this.pose, x0, y0 + size, 0.0F).setColor(r, g, b, alpha).setUv(0.0F, 1.0F).setLight(this.packedLightCoords);
            vc.addVertex(this.pose, x0 + size, y0 + size, 0.0F).setColor(r, g, b, alpha).setUv(1.0F, 1.0F).setLight(this.packedLightCoords);
            vc.addVertex(this.pose, x0 + size, y0, 0.0F).setColor(r, g, b, alpha).setUv(1.0F, 0.0F).setLight(this.packedLightCoords);
        }

        this.x += 9.0F;
        cir.setReturnValue(true);
    }
}
