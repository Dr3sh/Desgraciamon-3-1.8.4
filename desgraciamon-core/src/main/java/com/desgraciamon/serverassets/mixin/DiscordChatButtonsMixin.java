package com.desgraciamon.serverassets.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reubica el boton de adjuntar imagen de discord_chat_mod a la izquierda del boton
 * de emojis de Emojiful (que ocupa la esquina inferior derecha en x = width - 14, y = height - 12),
 * evitando que ambos botones se solapen en ChatScreen.
 */
@Mixin(value = ChatScreen.class, priority = 1200)
public abstract class DiscordChatButtonsMixin extends Screen {

    @Shadow
    protected EditBox input;

    protected DiscordChatButtonsMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void desgraciamon$separateEmojiAndAttachButtons(CallbackInfo ci) {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("emojiful") || !loader.isModLoaded("discord_chat_mod")) {
            return;
        }

        AbstractWidget attachWidget = null;
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget) {
                String clsName = widget.getClass().getName();
                if (clsName.contains("AttachImageWidget") || clsName.contains("AttachImage")) {
                    attachWidget = widget;
                    break;
                }
            }
        }

        if (attachWidget == null) {
            return;
        }

        // Emojiful coloca su icono 12x12 en (width - 14, height - 12).
        // Colocamos AttachImageWidget a la izquierda con un margen limpio de 4px.
        int targetX = Math.max(4, this.width - 14 - attachWidget.getWidth() - 4);
        int targetY = this.height - 14;
        attachWidget.setPosition(targetX, targetY);

        if (this.input != null) {
            int maxInputWidth = Math.max(40, targetX - this.input.getX() - 4);
            if (this.input.getWidth() > maxInputWidth) {
                this.input.setWidth(maxInputWidth);
            }
        }
    }
}
