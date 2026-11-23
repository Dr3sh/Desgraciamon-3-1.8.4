package com.desgraciamon.itemrestrictions

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.item.ItemStack

object ItemRestrictionsRenderer {
    private const val FILL_COLOR = 0x80202020.toInt()

    @JvmStatic
    fun isRestricted(stack: ItemStack): Boolean {
        return ItemRestrictionsClient.isRestricted(stack)
    }

    @JvmStatic
    fun renderRestrictedSlotOverlay(guiGraphics: GuiGraphics, x: Int, y: Int) {
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(0f, 0f, 250f)
        guiGraphics.fill(RenderType.guiOverlay(), x, y, x + 16, y + 16, FILL_COLOR)
        pose.popPose()
    }
}
