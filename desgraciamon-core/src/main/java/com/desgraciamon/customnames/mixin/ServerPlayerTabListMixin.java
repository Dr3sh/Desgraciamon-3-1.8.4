package com.desgraciamon.customnames.mixin;

import com.desgraciamon.customnames.CustomNamesMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTabListMixin {

    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$tabListNick(CallbackInfoReturnable<Component> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        Component custom = CustomNamesMod.getTabListDisplayName(self);
        if (custom != null) {
            cir.setReturnValue(custom);
        }
    }
}

