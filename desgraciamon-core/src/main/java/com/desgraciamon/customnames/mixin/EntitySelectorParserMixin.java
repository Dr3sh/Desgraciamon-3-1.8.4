package com.desgraciamon.customnames.mixin;

import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(EntitySelectorParser.class)
public abstract class EntitySelectorParserMixin {

    @ModifyConstant(method = "parseNameOrUUID", constant = @Constant(intValue = 16))
    private int desgraciamon$allowLongerNicknames(int original) {
        return 32;
    }
}

