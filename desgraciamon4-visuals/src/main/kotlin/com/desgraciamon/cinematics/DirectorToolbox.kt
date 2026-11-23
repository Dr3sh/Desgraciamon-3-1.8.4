package com.desgraciamon.cinematics

import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore

object DirectorToolbox {
    private const val TAG_KEY = "desgraciamon_director_tool"

    fun register() {
        UseItemCallback.EVENT.register { player, world, hand ->
            val stack = player.getItemInHand(hand)
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return@register InteractionResultHolder.pass(stack)
            val tag = customData.copyTag()
            if (!tag.contains(TAG_KEY)) return@register InteractionResultHolder.pass(stack)

            if (world.isClientSide) {
                return@register InteractionResultHolder.success(stack)
            }
            val serverPlayer = player as? ServerPlayer ?: return@register InteractionResultHolder.pass(stack)
            if (!serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(Component.literal("§cNecesitas permisos de operador para usar la Toolbox de Director."))
                return@register InteractionResultHolder.fail(stack)
            }

            val action = tag.getString(TAG_KEY)
            handleToolUse(serverPlayer, action)
            InteractionResultHolder.success(stack)
        }
    }

    fun giveToolbox(player: ServerPlayer) {
        val tools = listOf(
            makeTool(Items.EMERALD, "add", "§a§l[Director] Añadir Punto", "§7Clic derecho: añade un keyframe en tu posición"),
            makeTool(Items.COMPASS, "move", "§e§l[Director] Mover Punto", "§7Clic derecho: mueve el punto más cercano aquí"),
            makeTool(Items.BARRIER, "remove", "§c§l[Director] Quitar Punto", "§7Clic derecho: elimina el último punto (o el más cercano)"),
            makeTool(Items.ENDER_EYE, "test", "§6§l[Director] Probar Cinemática", "§7Clic derecho: reproduce la cinemática en modo prueba"),
            makeTool(Items.WRITABLE_BOOK, "gui", "§b§l[Director] Abrir GUI Director", "§7Clic derecho: abre el editor visual de keyframes")
        )
        for (tool in tools) {
            if (!player.inventory.add(tool)) {
                player.drop(tool, false)
            }
        }
        player.sendSystemMessage(Component.literal("§a§l[Director] §fHerramientas de Toolbox entregadas en tu inventario."))
    }

    private fun makeTool(item: Item, action: String, title: String, lore: String): ItemStack {
        val stack = ItemStack(item)
        val tag = CompoundTag().apply { putString(TAG_KEY, action) }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(title))
        stack.set(DataComponents.LORE, ItemLore(listOf(Component.literal(lore))))
        return stack
    }

    private fun handleToolUse(player: ServerPlayer, action: String) {
        val active = CinematicCommands.getPlayerActiveRoute(player)
        if (active == null) {
            player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa §e/cinematicas director editar <nombre>"))
            return
        }

        when (action) {
            "add" -> {
                val nextId = active.keyframes.size + 1
                val kf = Keyframe(
                    id = nextId,
                    x = player.x,
                    y = player.y + player.eyeHeight,
                    z = player.z,
                    yaw = player.yRot,
                    pitch = player.xRot,
                    durationSeconds = 4.0
                )
                active.keyframes.add(kf)
                CinematicRouteStorage.saveRoute(active)
                CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
                player.sendSystemMessage(Component.literal("§a§l+ Punto #$nextId §fañadido a '§e${active.name}§f'."))
            }
            "move" -> {
                if (active.keyframes.isEmpty()) {
                    player.sendSystemMessage(Component.literal("§cLa ruta no tiene puntos aún."))
                    return
                }
                val nearestIdx = active.keyframes.indices.minByOrNull { i ->
                    active.keyframes[i].position().distanceTo(player.eyePosition)
                } ?: 0
                val k = active.keyframes[nearestIdx]
                k.x = player.x
                k.y = player.y + player.eyeHeight
                k.z = player.z
                k.yaw = player.yRot
                k.pitch = player.xRot
                CinematicRouteStorage.saveRoute(active)
                CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
                player.sendSystemMessage(Component.literal("§ePunto #${nearestIdx + 1} §fmovido a tu posición actual."))
            }
            "remove" -> {
                if (active.keyframes.isEmpty()) {
                    player.sendSystemMessage(Component.literal("§cLa ruta no tiene puntos para quitar."))
                    return
                }
                val removeIdx = active.keyframes.indices.minByOrNull { i ->
                    val d = active.keyframes[i].position().distanceTo(player.eyePosition)
                    if (d < 8.0) d else Double.MAX_VALUE
                }?.takeIf { active.keyframes[it].position().distanceTo(player.eyePosition) < 8.0 }
                    ?: active.keyframes.lastIndex
                active.keyframes.removeAt(removeIdx)
                active.keyframes.forEachIndexed { i, k -> k.id = i + 1 }
                CinematicRouteStorage.saveRoute(active)
                CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
                player.sendSystemMessage(Component.literal("§cEliminado punto #${removeIdx + 1} de '§e${active.name}§c'. Quedan ${active.keyframes.size} puntos."))
            }
            "test" -> {
                if (active.keyframes.size < 2) {
                    player.sendSystemMessage(Component.literal("§cNecesitas al menos 2 puntos para probar la cinemática."))
                    return
                }
                CinematicServerManager.playCinematic(player.server, active, player)
            }
            "gui" -> {
                CinematicPackets.openEditorGui(player, CinematicRouteStorage.toJson(active))
            }
        }
    }
}
