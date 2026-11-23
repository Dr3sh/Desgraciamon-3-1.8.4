package com.desgraciamon.customnames.mixin;

import com.desgraciamon.customnames.CustomNamesManager;
import com.desgraciamon.customnames.CustomNamesMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackMixin {
    @Shadow public abstract MinecraftServer getServer();
    @Shadow public abstract Entity getEntity();

    @Inject(method = "getOnlinePlayerNames", at = @At("RETURN"), cancellable = true)
    private void desgraciamon$includeNicknamesInSuggestions(CallbackInfoReturnable<Collection<String>> cir) {
        Collection<String> original = cir.getReturnValue();
        Set<String> set = new LinkedHashSet<>();
        MinecraftServer server = this.getServer();
        if (server != null && server.getPlayerList() != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                String plainNick = CustomNamesManager.INSTANCE.getPlain(player.getUUID());
                if (plainNick != null && !plainNick.isBlank()) {
                    set.add(plainNick);
                }
                set.add(player.getGameProfile().getName());
            }
        }
        if (original != null) {
            set.addAll(original);
        }
        cir.setReturnValue(set);
    }

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void desgraciamon$sourceDisplayName(CallbackInfoReturnable<Component> cir) {
        Entity entity = this.getEntity();
        if (entity instanceof ServerPlayer player) {
            Component custom = CustomNamesMod.getNameComponent(player.getUUID(), false);
            if (custom != null) {
                cir.setReturnValue(player.getDisplayName());
            }
        }
    }
}

