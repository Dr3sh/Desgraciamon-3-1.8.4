package com.desgraciamon.showdownarena.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokedex.scanner.PokemonScanner;
import com.cobblemon.mod.common.pokedex.scanner.ScannableEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PokemonScanner.class)
public abstract class PokemonScannerMixin {
    @Inject(method = "findScannableEntity", at = @At("RETURN"), cancellable = true)
    private void showdownArena$hideBattleClones(net.minecraft.world.entity.Entity castingEntity, int zoomLevel, CallbackInfoReturnable<ScannableEntity> callback) {
        ScannableEntity target = callback.getReturnValue();
        if (target != null && target.resolveEntityScan() instanceof PokemonEntity pokemon && pokemon.isBattleClone()) {
            callback.setReturnValue(null);
        }
    }
}