package com.desgraciamon.serverassets.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flush y restauracion de pose/estado GL posterior a discord_chat_mod ChatComponentMixin.render (prioridad 1000).
 */
@Mixin(value = ChatComponent.class, priority = 1100)
public class DiscordChatPreviewPostMixin {

    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V",
        at = @At("TAIL")
    )
    private void desgraciamon$flushAfterDiscordPreview(
        GuiGraphics guiGraphics, int tickCount, int mouseX, int mouseY, boolean focused, CallbackInfo ci
    ) {
        guiGraphics.flush();
        guiGraphics.pose().popPose();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }
}
