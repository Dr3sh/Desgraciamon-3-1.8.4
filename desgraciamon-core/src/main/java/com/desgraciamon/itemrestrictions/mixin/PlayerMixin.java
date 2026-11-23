package com.desgraciamon.itemrestrictions.mixin;

import com.desgraciamon.itemrestrictions.ItemRestrictionsMod;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {

    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void desgraciamon_blockRestrictedInteractOn(
        Entity entity,
        InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        Player self = (Player) (Object) this;
        if (!self.level().isClientSide()) {
            ItemStack stack = self.getItemInHand(hand);
            if (ItemRestrictionsMod.isRestricted(self, stack)) {
                cir.setReturnValue(InteractionResult.FAIL);
            }
        }
    }
}
