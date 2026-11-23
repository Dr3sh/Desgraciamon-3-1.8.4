package com.desgraciamon.badges.mixin;

import com.desgraciamon.badges.AdminAuth;
import com.desgraciamon.badges.BadgeData;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MinecraftServer.class, priority = 500)
public abstract class MinecraftServerAdminMixin {

    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$adminBadgeProfilePermissions(GameProfile profile, CallbackInfoReturnable<Integer> cir) {
        if (profile == null || !BadgeData.INSTANCE.isLoaded()) return;
        if (AdminAuth.hasAdminBadge(profile.getId())) {
            cir.setReturnValue(4);
        }
    }
}
