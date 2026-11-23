package com.addoncobblemon.legendarycooldown.mixin;

import com.addoncobblemon.legendarycooldown.RefundTracker;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EmptyPokeBallEntity.class)
public abstract class EmptyPokeBallEntityMixin {
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true, remap = false)
    private void legendaryCooldown$returnToInventory(CallbackInfo callback) {
        EmptyPokeBallEntity ball = (EmptyPokeBallEntity) (Object) this;
        ItemStack stack = RefundTracker.INSTANCE.consume(ball.getUUID());
        if (stack == null) {
            return;
        }

        if (ball.getOwner() instanceof ServerPlayer player && !player.isCreative()) {
            if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                player.drop(stack, false);
            }
        }

        ball.discard();
        callback.cancel();
    }
}