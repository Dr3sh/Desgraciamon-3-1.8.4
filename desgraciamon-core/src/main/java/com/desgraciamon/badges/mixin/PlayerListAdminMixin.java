package com.desgraciamon.badges.mixin;

import com.desgraciamon.badges.AdminAuth;
import com.desgraciamon.badges.BadgeData;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PlayerList.class, priority = 500)
public abstract class PlayerListAdminMixin {

    @Inject(method = "isOp", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$adminBadgeIsOp(GameProfile profile, CallbackInfoReturnable<Boolean> cir) {
        if (profile == null || !BadgeData.INSTANCE.isLoaded()) return;
        if (AdminAuth.hasAdminBadge(profile.getId())) {
            cir.setReturnValue(true);
        }
    }
}
