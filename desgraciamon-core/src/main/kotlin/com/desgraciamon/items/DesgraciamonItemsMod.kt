package com.desgraciamon.items

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.minecraft.ChatFormatting
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

class TokenPirataItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component {
        return Component.translatable(descriptionId)
            .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true))
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltipComponents: MutableList<Component>,
        tooltipFlag: TooltipFlag
    ) {
        tooltipComponents.add(
            Component.translatable("item.desgraciamon_core.token_pirata.lore")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(false).withItalic(false))
        )
    }
}

class DesgraciamonItemsMod : ModInitializer {
    companion object {
        val TOKEN_PIRATA: Item = TokenPirataItem(Item.Properties())
    }

    override fun onInitialize() {
        Registry.register(
            BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath("desgraciamon_core", "token_pirata"),
            TOKEN_PIRATA
        )
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.INGREDIENTS).register { entries ->
            entries.accept(TOKEN_PIRATA)
        }
    }
}

