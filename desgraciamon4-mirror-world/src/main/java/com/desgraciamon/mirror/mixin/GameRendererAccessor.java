package com.desgraciamon.mirror.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Acceso al cargador de efectos de post-procesado (privado en vanilla). */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Invoker("loadEffect")
    void desgraciamon4mirror$loadEffect(ResourceLocation location);

    @Accessor("effectActive")
    boolean desgraciamon4mirror$isEffectActive();

    @Accessor("effectActive")
    void desgraciamon4mirror$setEffectActive(boolean active);
}

