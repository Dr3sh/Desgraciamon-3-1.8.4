package com.desgraciamon.prismtext.mixin;

import com.desgraciamon.prismtext.PrismTextClient;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public class StringRenderOutputMixin {

    @ModifyVariable(
        method = "accept(ILnet/minecraft/network/chat/Style;I)Z",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private Style desgraciamon$animatePrismStyle(Style style) {
        return PrismTextClient.animateStyle(style);
    }
}

