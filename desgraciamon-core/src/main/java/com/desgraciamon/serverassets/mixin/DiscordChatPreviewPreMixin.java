package com.desgraciamon.serverassets.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Compatibilidad entre ChatHeads / ImmediatelyFast hud_batching / discord_chat_mod:
 * ChatHeads llama RenderSystem.disableBlend() tras dibujar cabezas de jugadores y
 * ImmediatelyFast difiere el dibujo del chat si no se hace flush() antes/despues del blit
 * de discord_chat_mod (prioridad 1000). Prepara estado GL y eleva Z a prioridad 900.
 */
@Mixin(value = ChatComponent.class, priority = 900)
public class DiscordChatPreviewPreMixin {

    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V",
        at = @At("TAIL")
    )
    private void desgraciamon$prepareStateBeforeDiscordPreview(
        GuiGraphics guiGraphics, int tickCount, int mouseX, int mouseY, boolean focused, CallbackInfo ci
    ) {
        guiGraphics.flush();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 150.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
