package com.desgraciamon.itemrestrictions.mixin;

import com.desgraciamon.itemrestrictions.ItemRestrictionsRenderer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void desgraciamon_clientBlockUseItem(
        Player player,
        InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (ItemRestrictionsRenderer.isRestricted(player.getItemInHand(hand))) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void desgraciamon_clientBlockUseItemOn(
        LocalPlayer player,
        InteractionHand hand,
        BlockHitResult hitResult,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (ItemRestrictionsRenderer.isRestricted(player.getItemInHand(hand))) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void desgraciamon_clientBlockInteract(
        Player player,
        Entity target,
        InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (ItemRestrictionsRenderer.isRestricted(player.getItemInHand(hand))) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "interactAt", at = @At("HEAD"), cancellable = true)
    private void desgraciamon_clientBlockInteractAt(
        Player player,
        Entity target,
        EntityHitResult ray,
        InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (ItemRestrictionsRenderer.isRestricted(player.getItemInHand(hand))) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
