package com.desgraciamon.customnames.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reemplaza las barras de senal del Tablist por el ping numerico en milisegundos ("42ms")
 * junto al nombre de cada jugador y amplia ligeramente la columna reservada para evitar solapamientos.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayPingMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @ModifyConstant(
        method = "render",
        constant = @Constant(intValue = 13),
        require = 0
    )
    private int desgraciamon$widenPingColumn(int original) {
        return 34;
    }

    @Inject(method = "renderPingIcon", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$renderNumericPing(
        GuiGraphics guiGraphics, int width, int x, int y, PlayerInfo playerInfo, CallbackInfo ci
    ) {
        int latency = playerInfo.getLatency();
        String text = latency < 0 ? "?ms" : (latency + "ms");

        int color;
        if (latency < 0) {
            color = 0xFF9E9E9E;
        } else if (latency < 70) {
            color = 0xFF55FF55;
        } else if (latency < 150) {
            color = 0xFFFFFF55;
        } else if (latency < 250) {
            color = 0xFFFFAA00;
        } else {
            color = 0xFFFF5555;
        }

        Font font = this.minecraft.font;
        float scale = 0.85f;
        int textW = font.width(text);
        float scaledW = textW * scale;

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        // Alinear a la derecha dentro del espacio de la entrada, centrado verticalmente en la fila de 8px
        float drawX = (x + width - 1) - scaledW;
        float drawY = y + 0.5f;
        pose.translate(drawX, drawY, 200.0f);
        pose.scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, text, 0, 0, color, true);
        pose.popPose();

        ci.cancel();
    }
}
