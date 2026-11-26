package com.desgraciamon.badges.mixin;

import com.desgraciamon.badges.BadgesMod;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    private static boolean isCalledFromDiscordChatMod() {
        return STACK_WALKER.walk(frames -> frames.limit(32).anyMatch(frame -> {
            String cls = frame.getClassName();
            String method = frame.getMethodName();
            return cls.startsWith("com.denisnumb.discord_chat_mod.")
                || cls.contains("discord_chat_mod")
                || method.contains("discord_chat_mod");
        }));
    }

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void desgraciamon$prependBadgesToDisplayName(CallbackInfoReturnable<Component> cir) {
        Player self = (Player) (Object) this;
        if (self.level().isClientSide()) return;
        if (isCalledFromDiscordChatMod()) return;
        Component decorated = BadgesMod.decoratePlayerDisplayName(self.getUUID(), cir.getReturnValue());
        cir.setReturnValue(decorated);
    }
}

