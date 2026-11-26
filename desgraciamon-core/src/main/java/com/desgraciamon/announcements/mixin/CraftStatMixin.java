package com.desgraciamon.announcements.mixin;

import com.desgraciamon.announcements.BulletinStore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class CraftStatMixin {

    @Inject(method = "awardStat", at = @At("HEAD"))
    private void desgraciamon$onCraftStat(Stat<?> stat, int increment, CallbackInfo ci) {
        if (increment <= 0 || stat == null) return;
        if (stat.getType() == Stats.ITEM_CRAFTED && stat.getValue() instanceof Item item) {
            ServerPlayer player = (ServerPlayer) (Object) this;
            String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
            BulletinStore.INSTANCE.progressCraft(player, itemId, increment);
        }
    }
}

