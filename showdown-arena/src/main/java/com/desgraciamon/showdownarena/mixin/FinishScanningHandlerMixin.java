package com.desgraciamon.showdownarena.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.net.messages.server.pokedex.scanner.FinishScanningPacket;
import com.cobblemon.mod.common.net.serverhandling.pokedex.scanner.FinishScanningHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FinishScanningHandler.class)
public abstract class FinishScanningHandlerMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void showdownArena$blockBattleCloneScan(FinishScanningPacket packet, MinecraftServer server, ServerPlayer player, CallbackInfo callback) {
        Entity entity = player.level().getEntity(packet.getTargetedId());
        if (entity instanceof PokemonEntity pokemon && pokemon.isBattleClone()) {
            callback.cancel();
        }
    }
}