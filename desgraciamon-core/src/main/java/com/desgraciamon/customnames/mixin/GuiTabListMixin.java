package com.desgraciamon.customnames.mixin;

import com.desgraciamon.customnames.CustomNamesClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Gui.class)
public abstract class GuiTabListMixin {

    @Redirect(
        method = "renderTabList",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isLocalServer()Z")
    )
    private boolean desgraciamon$enableTabListInSingleplayer(Minecraft instance) {
        // En singleplayer, Minecraft deshabilita la lista de Tab si está jugando solo.
        // Retornar false permite que el tablist se renderice siempre.
        return false;
    }

    @Redirect(
        method = "renderTabList",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z")
    )
    private boolean desgraciamon$checkTabDownOrPreview(KeyMapping keyMapping) {
        return keyMapping.isDown() || CustomNamesClient.isPreviewingTabList();
    }
}
