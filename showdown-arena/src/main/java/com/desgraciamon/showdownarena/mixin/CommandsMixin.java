package com.desgraciamon.showdownarena.mixin;

import com.desgraciamon.showdownarena.GtsZoneManager;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public class CommandsMixin {

    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$interceptPerformCommand(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
        if (isGtsCommand(command)) {
            CommandSourceStack source = parseResults.getContext().getSource();
            if (!GtsZoneManager.INSTANCE.canUseGts(source)) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$interceptPrefixedCommand(CommandSourceStack source, String command, CallbackInfo ci) {
        if (isGtsCommand(command)) {
            if (!GtsZoneManager.INSTANCE.canUseGts(source)) {
                ci.cancel();
            }
        }
    }

    private static boolean isGtsCommand(String command) {
        if (command == null) return false;
        String trimmed = command.startsWith("/") ? command.substring(1).trim() : command.trim();
        return trimmed.equalsIgnoreCase("gts") || trimmed.toLowerCase().startsWith("gts ");
    }
}
