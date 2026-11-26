package com.desgraciamon.mirror.mixin;

import com.desgraciamon.mirror.MirrorRules;
import com.desgraciamon.mirror.MirrorSettings;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDropMixin {
    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"), cancellable = true)
    private void onDropItem(ItemStack itemStack, boolean dropAround, boolean traceItem, CallbackInfoReturnable<ItemEntity> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (MirrorRules.INSTANCE.isMirror(player.level())) {
            player.getInventory().add(itemStack);
            player.sendSystemMessage(MirrorSettings.INSTANCE.text("protect.drop_blocked"));
            cir.setReturnValue(null);
        }
    }
}

