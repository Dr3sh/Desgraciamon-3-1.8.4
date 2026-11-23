package com.desgraciamon.serverassets.mixin;

import com.desgraciamon.serverassets.ServerAssetsClientState;
import com.mojang.blaze3d.font.GlyphInfo;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontSet.class)
public class FontSetMixin {

    @Unique
    private static final GlyphInfo DESGRACIAMON_SERVER_EMOJI_GLYPH_INFO = (GlyphInfo.SpaceGlyphInfo) () -> 9.0F;

    @Inject(method = "getGlyphInfo(IZ)Lcom/mojang/blaze3d/font/GlyphInfo;", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$serverEmojiGlyphWidth(int codePoint, boolean filterFishyGlyphs, CallbackInfoReturnable<GlyphInfo> cir) {
        if (ServerAssetsClientState.getEmojiTextureForCodepoint(codePoint) != null) {
            cir.setReturnValue(DESGRACIAMON_SERVER_EMOJI_GLYPH_INFO);
        }
    }
}
