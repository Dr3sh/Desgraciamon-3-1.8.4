package com.desgraciamon.itemrestrictions.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(LootTable.class)
public abstract class LootTableMixin {

    @Shadow @Final private Optional<ResourceLocation> randomSequence;

    @Shadow public abstract ObjectArrayList<ItemStack> getRandomItems(LootContext context);

    @Unique
    private static final ResourceLocation DESGRACIAMON_MASTER_BALL =
        ResourceLocation.fromNamespaceAndPath("cobblemon", "master_ball");

    @Unique
    private static final ThreadLocal<Boolean> DESGRACIAMON_REROLLING =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(
        method = "getRandomItems(Lnet/minecraft/world/level/storage/loot/LootContext;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
        at = @At("RETURN")
    )
    private void desgraciamon$removeTrialChamberMasterBall(
        LootContext context,
        CallbackInfoReturnable<ObjectArrayList<ItemStack>> cir
    ) {
        if (Boolean.TRUE.equals(DESGRACIAMON_REROLLING.get())) return;
        if (this.randomSequence.isEmpty()) return;
        ResourceLocation seq = this.randomSequence.get();
        if (!"minecraft".equals(seq.getNamespace()) || !seq.getPath().startsWith("chests/trial_chambers/")) {
            return;
        }

        ObjectArrayList<ItemStack> drops = cir.getReturnValue();
        if (drops == null || drops.isEmpty()) return;

        boolean removed = drops.removeIf(LootTableMixin::desgraciamon$isMasterBall);
        if (removed && drops.isEmpty()) {
            DESGRACIAMON_REROLLING.set(Boolean.TRUE);
            try {
                for (int attempt = 0; attempt < 8; attempt++) {
                    ObjectArrayList<ItemStack> rerolled = this.getRandomItems(context);
                    rerolled.removeIf(LootTableMixin::desgraciamon$isMasterBall);
                    if (!rerolled.isEmpty()) {
                        drops.addAll(rerolled);
                        break;
                    }
                }
            } finally {
                DESGRACIAMON_REROLLING.set(Boolean.FALSE);
            }
        }
    }

    @Unique
    private static boolean desgraciamon$isMasterBall(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return DESGRACIAMON_MASTER_BALL.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
