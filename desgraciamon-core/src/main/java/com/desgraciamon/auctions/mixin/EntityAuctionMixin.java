package com.desgraciamon.auctions.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityAuctionMixin {

    private boolean desgraciamon$isAuctionEntity() {
        Entity self = (Entity) (Object) this;
        return self.getTags().contains("desgraciamon_auction_stand");
    }

    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$noPickable(CallbackInfoReturnable<Boolean> cir) {
        if (desgraciamon$isAuctionEntity()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$noPushable(CallbackInfoReturnable<Boolean> cir) {
        if (desgraciamon$isAuctionEntity()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$noCollision(Entity other, CallbackInfoReturnable<Boolean> cir) {
        if (desgraciamon$isAuctionEntity()) {
            cir.setReturnValue(false);
        }
    }
}

