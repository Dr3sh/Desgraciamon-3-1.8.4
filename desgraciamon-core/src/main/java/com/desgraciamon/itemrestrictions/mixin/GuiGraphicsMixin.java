package com.desgraciamon.itemrestrictions.mixin;

import com.desgraciamon.itemrestrictions.ItemRestrictionsClient;
import com.desgraciamon.itemrestrictions.ItemRestrictionsRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
    @Inject(
        method = "renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
        at = @At("TAIL")
    )
    private void desgraciamon_renderRestrictedItemOverlay(Font font, ItemStack stack, int x, int y, String altText, CallbackInfo ci) {
        if (stack != null && !stack.isEmpty() && ItemRestrictionsClient.isRestricted(stack)) {
            ItemRestrictionsRenderer.renderRestrictedSlotOverlay((GuiGraphics) (Object) this, x, y);
        }
    }
}

