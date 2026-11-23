package com.desgraciamon.hud.mixin;

import com.desgraciamon.hud.CustomHudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.resources.ResourceLocation;

@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = "renderFood", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$cancelFood(GuiGraphics guiGraphics, Player player, int x, int y, CallbackInfo ci) {
        if (CustomHudConfig.INSTANCE.isEnabled()) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "renderHearts", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int desgraciamon$centerHeartsX(int originalX) {
        if (CustomHudConfig.INSTANCE.isEnabled()) {
            Minecraft mc = Minecraft.getInstance();
            int width = mc.getWindow().getGuiScaledWidth();
            return (width - 81) / 2;
        }
        return originalX;
    }

    @ModifyVariable(method = "renderHearts", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int desgraciamon$raiseHeartsY(int originalY) {
        if (CustomHudConfig.INSTANCE.isEnabled()) {
            return originalY - 10;
        }
        return originalY;
    }

    @ModifyVariable(method = "renderArmor", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static int desgraciamon$raiseArmorY(int originalY) {
        if (CustomHudConfig.INSTANCE.isEnabled()) {
            return originalY - 10;
        }
        return originalY;
    }

    @ModifyVariable(method = "renderArmor", at = @At("HEAD"), argsOnly = true, ordinal = 3)
    private static int desgraciamon$centerArmorX(int originalX) {
        if (CustomHudConfig.INSTANCE.isEnabled()) {
            Minecraft mc = Minecraft.getInstance();
            int width = mc.getWindow().getGuiScaledWidth();
            return (width - 81) / 2;
        }
        return originalX;
    }
    @Redirect(method = "renderPlayerHealth", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
    private void desgraciamon$moveAirBubbles(GuiGraphics guiGraphics, ResourceLocation sprite, int x, int y, int width, int height) {
        if (sprite != null && sprite.getPath().contains("air")) {
            Minecraft mc = Minecraft.getInstance();
            int screenWidth = guiGraphics.guiWidth();
            boolean customHud = CustomHudConfig.INSTANCE.isEnabled();
            int heartsX = customHud ? (screenWidth - 81) / 2 : (screenWidth / 2 - 91);
            int i = (screenWidth / 2 + 82 - x) / 8;
            int newX = heartsX + (9 - i) * 8;

            int heartsY = guiGraphics.guiHeight() - (customHud ? 49 : 39);
            Player player = mc.player;
            int armor = player != null ? player.getArmorValue() : 0;
            int newY = armor > 0 ? (heartsY - 20) : (heartsY - 10);

            guiGraphics.blitSprite(sprite, newX, newY, width, height);
        } else {
            guiGraphics.blitSprite(sprite, x, y, width, height);
        }
    }
}
