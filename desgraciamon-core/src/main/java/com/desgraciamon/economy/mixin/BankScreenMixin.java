package com.desgraciamon.economy.mixin;

import com.desgraciamon.economy.BankScreenHelper;
import com.desgraciamon.economy.BankScreenRenderer;
import com.desgraciamon.economy.EconomySellPayload;
import fr.harmex.cobbledollars.common.client.gui.screen.BankScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BankScreen.class, remap = false)
public abstract class BankScreenMixin {
    @Unique
    private long desgraciamon$openedAtNanos = System.nanoTime();

    @Inject(method = "method_25426", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        desgraciamon$openedAtNanos = System.nanoTime();
        BankScreenHelper.INSTANCE.onBankContainerChanged((BankScreen) (Object) this);
    }

    @Inject(method = "method_5453", at = @At("RETURN"))
    private void onContainerChanged(Container container, CallbackInfo ci) {
        BankScreenHelper.INSTANCE.onBankContainerChanged((BankScreen) (Object) this);
    }

    @Inject(method = "sell", at = @At("HEAD"))
    private void onSellClicked(CallbackInfo ci) {
        if (((BankScreen) (Object) this).canSell()) {
            try {
                ClientPlayNetworking.send(EconomySellPayload.INSTANCE);
            } catch (Throwable ignored) {}
        }
    }

    @Inject(method = "method_25394", at = @At("HEAD"))
    private void onRenderHead(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        BankScreenHelper.INSTANCE.onBankContainerChanged((BankScreen) (Object) this);
        float progress = Math.min(1.0F, (System.nanoTime() - desgraciamon$openedAtNanos) / 280_000_000.0F);
        float eased = 1.0F - (float) Math.pow(1.0 - progress, 3.0);
        float scale = 0.08F + eased * 0.92F;

        Screen screen = (Screen) (Object) this;
        float centerX = screen.width / 2.0F;
        float centerY = screen.height / 2.0F;

        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.pose().translate(-centerX, -centerY, 0.0F);
    }

    @Inject(method = "method_25394", at = @At("RETURN"))
    private void onRenderReturn(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        BankScreenRenderer.INSTANCE.renderPredictionHUD((BankScreen) (Object) this, graphics, mouseX, mouseY);
        graphics.pose().popPose();
    }
}
