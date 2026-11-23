package com.desgraciamon.showdownarena.mixin;

import com.cobblemon.mod.common.battles.BattleFormat;
import com.desgraciamon.showdownarena.ArenaBattleFormatJson;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BattleFormat.class)
public abstract class BattleFormatMixin {
    @Inject(method = "toFormatJSON", at = @At("HEAD"), cancellable = true, remap = false)
    private void showdownArena$includeNativeRules(CallbackInfoReturnable<String> callback) {
        String json = ArenaBattleFormatJson.forBattle((BattleFormat) (Object) this);
        if (json != null) {
            callback.setReturnValue(json);
        }
    }
}