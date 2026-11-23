package com.desgraciamon.prismtext.mixin;

import com.desgraciamon.prismtext.PrismTextClient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Screen.class)
public class ScreenMixin {

    @ModifyVariable(
        method = "handleComponentClicked(Lnet/minecraft/network/chat/Style;)Z",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private Style desgraciamon$stripPrismInsertionOnClick(Style style) {
        if (PrismTextClient.isPrismStyle(style)) {
            return style.withInsertion(null);
        }
        return style;
    }
}

