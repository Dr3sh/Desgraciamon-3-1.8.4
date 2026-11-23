package com.desgraciamon.customnames.mixin;

import com.desgraciamon.customnames.CustomNamesMod;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerNameMixin {

    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void desgraciamon$customPlayerName(CallbackInfoReturnable<Component> cir) {
        Player self = (Player) (Object) this;
        Component custom = CustomNamesMod.getNameComponent(self.getUUID(), self.level().isClientSide());
        if (custom != null) {
            cir.setReturnValue(custom);
        }
    }
}
