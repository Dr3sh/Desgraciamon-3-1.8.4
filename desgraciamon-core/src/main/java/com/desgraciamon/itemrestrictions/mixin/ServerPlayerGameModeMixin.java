package com.desgraciamon.itemrestrictions.mixin;

import com.desgraciamon.itemrestrictions.ItemRestrictionsMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void desgraciamon_blockRestrictedUseItem(
        ServerPlayer player,
        Level level,
        ItemStack stack,
        InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (ItemRestrictionsMod.isRestricted(player, stack) ||
            ItemRestrictionsMod.isRestricted(player, player.getItemInHand(hand))) {
            player.inventoryMenu.sendAllDataToRemote();
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void desgraciamon_blockRestrictedUseItemOn(
        ServerPlayer player,
        Level level,
        ItemStack stack,
        InteractionHand hand,
        BlockHitResult hitResult,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (ItemRestrictionsMod.isRestricted(player, stack) ||
            ItemRestrictionsMod.isRestricted(player, player.getItemInHand(hand))) {
            player.inventoryMenu.sendAllDataToRemote();
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
