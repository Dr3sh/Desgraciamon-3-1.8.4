package com.desgraciamon.customnames.mixin;

import com.desgraciamon.customnames.CustomNamesManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class PlayerListNickMixin {

    @Inject(method = "getPlayerByName", at = @At("RETURN"), cancellable = true)
    private void desgraciamon$findPlayerByNickname(String name, CallbackInfoReturnable<ServerPlayer> cir) {
        if (cir.getReturnValue() != null) return;
        ServerPlayer match = CustomNamesManager.INSTANCE.findOnlinePlayerByNickname(name);
        if (match != null) {
            cir.setReturnValue(match);
        }
    }
}

