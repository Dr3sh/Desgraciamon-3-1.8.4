package com.desgraciamon.badges.mixin;

import com.desgraciamon.badges.AdminAuth;
import com.desgraciamon.badges.BadgeData;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerPlayer.class, priority = 500)
public abstract class ServerPlayerAdminMixin {

    @Inject(method = "getPermissionLevel", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$adminBadgePermissionLevel(CallbackInfoReturnable<Integer> cir) {
        if (!BadgeData.INSTANCE.isLoaded()) return;
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (AdminAuth.hasAdminBadge(self.getUUID())) {
            cir.setReturnValue(4);
        }
    }
}
