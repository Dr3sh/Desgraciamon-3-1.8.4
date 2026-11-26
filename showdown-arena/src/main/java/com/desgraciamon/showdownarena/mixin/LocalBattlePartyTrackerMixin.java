package com.desgraciamon.showdownarena.mixin;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.desgraciamon.showdownarena.ShowdownBattlePartyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Pseudo
@Mixin(targets = "name.modid.client.LocalBattlePartyTracker", remap = false)
public class LocalBattlePartyTrackerMixin {
    @Inject(method = "getLocalParty", at = @At("HEAD"), cancellable = true, remap = false)
    private static void showdown$useTemporaryShowdownParty(CallbackInfoReturnable<List<Pokemon>> cir) {
        if (CobblemonClient.INSTANCE.getBattle() != null && !ShowdownBattlePartyClient.activeShowdownParty.isEmpty()) {
            cir.setReturnValue(new ArrayList<>(ShowdownBattlePartyClient.activeShowdownParty));
        }
    }
}
