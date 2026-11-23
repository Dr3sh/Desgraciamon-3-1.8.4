package com.desgraciamon.economy.mixin;

import com.desgraciamon.economy.BankHook;
import fr.harmex.cobbledollars.common.network.handlers.server.SellHandler;
import fr.harmex.cobbledollars.common.network.packets.c2s.SellPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SellHandler.class, remap = false)
public class SellHandlerMixin {
    @Inject(method = "handle(Lfr/harmex/cobbledollars/common/network/packets/c2s/SellPacket;Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/class_3222;)V", at = @At("HEAD"), cancellable = true)
    private void onBankSell(SellPacket packet, MinecraftServer server, ServerPlayer player, CallbackInfo ci) {
        if (BankHook.onBankSell(player)) {
            ci.cancel();
        }
    }
}
