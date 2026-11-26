package com.desgraciamon.joinmessages.mixin;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ChatTypeDecoration;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatTypeDecoration.class)
public abstract class ChatTypeDecorationMixin {
    @Shadow
    public abstract String translationKey();

    @Inject(method = "decorate", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$formatChatNoAngleBrackets(Component content, ChatType.Bound bound, CallbackInfoReturnable<Component> cir) {
        String key = this.translationKey();
        if ("chat.type.text".equals(key)) {
            MutableComponent message = Component.empty()
                .append(bound.name())
                .append(Component.literal(": "))
                .append(content);
            cir.setReturnValue(message);
        } else if ("chat.type.announcement".equals(key)) {
            String senderName = bound.name().getString();
            MutableComponent prefix = "Server".equalsIgnoreCase(senderName)
                ? Component.literal("§b§lServidor§r §f:roles/admin:§7: §f")
                : Component.empty().append(bound.name()).append(Component.literal(" §7:roles/admin:§7: §f"));
            cir.setReturnValue(Component.empty().append(prefix).append(content));
        }
    }
}

