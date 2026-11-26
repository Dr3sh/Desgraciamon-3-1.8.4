package com.desgraciamon.antidupe

import com.desgraciamon.announcements.SophisticatedBackpacksBridge
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.level.block.ShulkerBoxBlock
import java.util.UUID

object ContainerInspector {

    data class FoundContainer(
        val locationLabel: String,
        val stack: ItemStack,
        val contents: List<ItemStack>
    )

    fun isVanillaShulker(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val item = stack.item
        return item is BlockItem && item.block is ShulkerBoxBlock
    }

    fun isInspectableContainer(stack: ItemStack, player: ServerPlayer): Boolean {
        if (stack.isEmpty) return false
        if (isVanillaShulker(stack)) return true
        if (stack.get(DataComponents.CONTAINER) != null) return true
        return SophisticatedBackpacksBridge.openStackStorage(stack, player.registryAccess()) != null
    }

    fun readContainerContents(stack: ItemStack, player: ServerPlayer): List<ItemStack> {
        if (stack.isEmpty) return emptyList()
        val handle = SophisticatedBackpacksBridge.openStackStorage(stack, player.registryAccess())
        if (handle != null) {
            val out = ArrayList<ItemStack>(handle.slotCount)
            for (i in 0 until handle.slotCount) {
                out.add(handle.getStackInSlot(i).copy())
            }
            return out
        }
        val vanilla = stack.get(DataComponents.CONTAINER)
        if (vanilla != null) {
            return vanilla.nonEmptyItems().map { it.copy() }
        }
        val bundle = stack.get(DataComponents.BUNDLE_CONTENTS)
        if (bundle != null) {
            return bundle.items().map { it.copy() }
        }
        return emptyList()
    }

    fun getEquippedBackpacksAndAccessories(player: ServerPlayer): List<Pair<String, ItemStack>> {
        val result = ArrayList<Pair<String, ItemStack>>()
        val seenIds = HashSet<Int>()

        SophisticatedBackpacksBridge.forEachEquippedBackpack(player) { stack ->
            if (!stack.isEmpty && seenIds.add(System.identityHashCode(stack))) {
                result.add("Mochila Equipada" to stack)
            }
        }

        // Accessories API (WispForest)
        runCatching {
            val capClass = Class.forName("io.wispforest.accessories.api.AccessoriesCapability")
            val getOpt = capClass.getMethod("getOptionally", net.minecraft.world.entity.LivingEntity::class.java)
            val opt = getOpt.invoke(null, player) as? java.util.Optional<*>
            val cap = opt?.orElse(null) ?: return@runCatching
            @Suppress("UNCHECKED_CAST")
            val containers = cap.javaClass.getMethod("getContainers").invoke(cap) as? Map<String, Any> ?: return@runCatching
            for ((slotName, contObj) in containers) {
                val accInv = contObj.javaClass.getMethod("getAccessories").invoke(contObj) as? Container ?: continue
                for (i in 0 until accInv.containerSize) {
                    val stack = accInv.getItem(i)
                    if (!stack.isEmpty && seenIds.add(System.identityHashCode(stack))) {
                        result.add("Accesorio ($slotName)" to stack)
                    }
                }
            }
        }

        // Trinkets API fallback
        runCatching {
            val apiClass = Class.forName("dev.emi.trinkets.api.TrinketsApi")
            val getComp = apiClass.getMethod("getTrinketComponent", net.minecraft.world.entity.LivingEntity::class.java)
            val opt = getComp.invoke(null, player) as? java.util.Optional<*>
            val comp = opt?.orElse(null) ?: return@runCatching
            @Suppress("UNCHECKED_CAST")
            val equipped = comp.javaClass.getMethod("getAllEquipped").invoke(comp) as? List<*> ?: return@runCatching
            for (pairObj in equipped) {
                if (pairObj == null) continue
                val stack = pairObj.javaClass.getMethod("getB").invoke(pairObj) as? ItemStack ?: continue
                if (!stack.isEmpty && seenIds.add(System.identityHashCode(stack))) {
                    result.add("Trinket Equipado" to stack)
                }
            }
        }

        return result
    }

    fun collectAllContainers(player: ServerPlayer): List<FoundContainer> {
        val found = ArrayList<FoundContainer>()
        val visitedStackIds = HashSet<Int>()
        val visitedStorageUuids = HashSet<UUID>()

        fun inspectRecursive(stack: ItemStack, source: String, depth: Int) {
            if (stack.isEmpty || depth > 4) return
            if (!visitedStackIds.add(System.identityHashCode(stack))) return

            val handle = SophisticatedBackpacksBridge.openStackStorage(stack, player.registryAccess())
            if (handle != null) {
                val uuid = handle.storageUuid
                if (uuid != null && !visitedStorageUuids.add(uuid)) return
                val contents = ArrayList<ItemStack>(handle.slotCount)
                for (i in 0 until handle.slotCount) {
                    val inner = handle.getStackInSlot(i).copy()
                    contents.add(inner)
                }
                found.add(FoundContainer(source, stack.copy(), contents))
                for (inner in contents) {
                    if (!inner.isEmpty && isInspectableContainer(inner, player)) {
                        inspectRecursive(inner, "$source > ${inner.hoverName.string}", depth + 1)
                    }
                }
                return
            }

            if (isVanillaShulker(stack) || stack.get(DataComponents.CONTAINER) != null) {
                val contents = readContainerContents(stack, player)
                found.add(FoundContainer(source, stack.copy(), contents))
                for (inner in contents) {
                    if (!inner.isEmpty && isInspectableContainer(inner, player)) {
                        inspectRecursive(inner, "$source > ${inner.hoverName.string}", depth + 1)
                    }
                }
            }
        }

        for ((label, stack) in getEquippedBackpacksAndAccessories(player)) {
            if (isInspectableContainer(stack, player)) {
                inspectRecursive(stack, label, 0)
            }
        }

        player.inventory.armor.forEachIndexed { idx, stack ->
            if (isInspectableContainer(stack, player)) {
                inspectRecursive(stack, "Armadura #$idx", 0)
            }
        }
        player.inventory.offhand.forEach { stack ->
            if (isInspectableContainer(stack, player)) {
                inspectRecursive(stack, "Mano secundaria", 0)
            }
        }
        player.inventory.items.forEachIndexed { idx, stack ->
            if (isInspectableContainer(stack, player)) {
                inspectRecursive(stack, "Inventario (slot $idx)", 0)
            }
        }
        for (i in 0 until player.enderChestInventory.containerSize) {
            val stack = player.enderChestInventory.getItem(i)
            if (isInspectableContainer(stack, player)) {
                inspectRecursive(stack, "Ender Chest (slot $i)", 0)
            }
        }

        return found
    }

    fun openPlayerInventory(admin: ServerPlayer, target: ServerPlayer) {
        val container = SimpleContainer(54)
        val actions = HashMap<Int, (ServerPlayer) -> Unit>()

        // Filas 0-3 (slots 0..35): inventario principal + hotbar
        for (i in 0 until minOf(36, target.inventory.items.size)) {
            val stack = target.inventory.items[i]
            if (!stack.isEmpty) {
                val copy = stack.copy()
                if (isInspectableContainer(stack, target)) {
                    annotateInspectable(copy, "Clic para abrir contenido")
                    actions[i] = { opener ->
                        openContainerContents(opener, target, stack.copy(), stack.hoverName.string, 0) {
                            openPlayerInventory(opener, target)
                        }
                    }
                }
                container.setItem(i, copy)
            }
        }

        // Fila 4 (slots 36..39): armadura (casco..botas)
        for (a in 0 until 4) {
            val armorIdx = 3 - a
            val stack = target.inventory.armor.getOrElse(armorIdx) { ItemStack.EMPTY }
            val slot = 36 + a
            if (!stack.isEmpty) {
                val copy = stack.copy()
                if (isInspectableContainer(stack, target)) {
                    annotateInspectable(copy, "Clic para abrir contenido")
                    actions[slot] = { opener ->
                        openContainerContents(opener, target, stack.copy(), stack.hoverName.string, 0) {
                            openPlayerInventory(opener, target)
                        }
                    }
                }
                container.setItem(slot, copy)
            } else {
                container.setItem(slot, spacer("§8[Sin Armadura]"))
            }
        }

        // Slot 40: mano secundaria
        val offhand = target.inventory.offhand.firstOrNull() ?: ItemStack.EMPTY
        if (!offhand.isEmpty) {
            val copy = offhand.copy()
            if (isInspectableContainer(offhand, target)) {
                annotateInspectable(copy, "Clic para abrir contenido")
                actions[40] = { opener ->
                    openContainerContents(opener, target, offhand.copy(), offhand.hoverName.string, 0) {
                        openPlayerInventory(opener, target)
                    }
                }
            }
            container.setItem(40, copy)
        } else {
            container.setItem(40, spacer("§8[Mano Secundaria Vacía]"))
        }

        // Slots 41..44: mochilas/accesorios equipados
        val equipped = getEquippedBackpacksAndAccessories(target)
        for (idx in 0 until 4) {
            val slot = 41 + idx
            val pair = equipped.getOrNull(idx)
            if (pair != null && !pair.second.isEmpty) {
                val orig = pair.second
                val copy = orig.copy()
                annotateInspectable(copy, "§e${pair.first} §7(Clic si es mochila/contenedor)")
                if (isInspectableContainer(orig, target)) {
                    actions[slot] = { opener ->
                        openContainerContents(opener, target, orig.copy(), "${pair.first}: ${orig.hoverName.string}", 0) {
                            openPlayerInventory(opener, target)
                        }
                    }
                }
                container.setItem(slot, copy)
            } else {
                container.setItem(slot, spacer("§8[Slot Equipado Libre]"))
            }
        }

        // Fila 5: barra de navegación
        for (s in 45..53) container.setItem(s, spacer(" "))

        container.setItem(45, menuButton(Items.ENDER_CHEST, "§d§lVer Ender Chest", listOf("§7Clic para inspeccionar el Ender Chest")))
        actions[45] = { opener -> openEnderChest(opener, target) }

        container.setItem(
            46,
            menuButton(
                Items.SHULKER_BOX,
                "§6§lTodas las Mochilas y Shulkers",
                listOf("§7Muestra equipadas, en inventario,", "§7Ender Chest y anidadas.")
            )
        )
        actions[46] = { opener -> openContainersBrowser(opener, target, 0) }

        container.setItem(
            49,
            menuButton(
                Items.PLAYER_HEAD,
                "§a${target.scoreboardName}",
                listOf("§7Haz clic en cualquier mochila o Shulker", "§7para abrir su inventario (solo lectura).")
            )
        )

        container.setItem(53, menuButton(Items.COMPASS, "§b§lActualizar Vista", listOf("§7Recarga el inventario actual")))
        actions[53] = { opener -> openPlayerInventory(opener, target) }

        openReadOnlyMenu(admin, Component.literal("§8Invsee: §6${target.scoreboardName}"), container, actions)
    }

    fun openEnderChest(admin: ServerPlayer, target: ServerPlayer) {
        val container = SimpleContainer(54)
        val actions = HashMap<Int, (ServerPlayer) -> Unit>()

        val ec = target.enderChestInventory
        for (i in 0 until minOf(27, ec.containerSize)) {
            val stack = ec.getItem(i)
            if (!stack.isEmpty) {
                val copy = stack.copy()
                if (isInspectableContainer(stack, target)) {
                    annotateInspectable(copy, "Clic para abrir contenido")
                    actions[i] = { opener ->
                        openContainerContents(opener, target, stack.copy(), "Ender Chest > ${stack.hoverName.string}", 0) {
                            openEnderChest(opener, target)
                        }
                    }
                }
                container.setItem(i, copy)
            }
        }

        for (s in 27..53) container.setItem(s, spacer(" "))
        container.setItem(45, menuButton(Items.ARROW, "§e§lVolver al Inventario", listOf("§7Regresar al inventario principal")))
        actions[45] = { opener -> openPlayerInventory(opener, target) }

        container.setItem(46, menuButton(Items.SHULKER_BOX, "§6§lMochilas y Shulkers", listOf("§7Lista completa de contenedores")))
        actions[46] = { opener -> openContainersBrowser(opener, target, 0) }

        container.setItem(53, menuButton(Items.COMPASS, "§b§lActualizar", listOf("§7Recargar Ender Chest")))
        actions[53] = { opener -> openEnderChest(opener, target) }

        openReadOnlyMenu(admin, Component.literal("§8EnderChest: §d${target.scoreboardName}"), container, actions)
    }

    fun openContainersBrowser(admin: ServerPlayer, target: ServerPlayer, page: Int = 0) {
        val found = collectAllContainers(target)
        val perPage = 45
        val totalPages = ((found.size - 1).coerceAtLeast(0) / perPage) + 1
        val safePage = page.coerceIn(0, totalPages - 1)

        val container = SimpleContainer(54)
        val actions = HashMap<Int, (ServerPlayer) -> Unit>()

        if (found.isEmpty()) {
            container.setItem(22, menuButton(Items.BARRIER, "§cSin mochilas ni Shulkers", listOf("§7El jugador no tiene contenedores.")))
        } else {
            val start = safePage * perPage
            val slice = found.drop(start).take(perPage)
            slice.forEachIndexed { idx, entry ->
                val icon = entry.stack.copy()
                val nonEmptyCount = entry.contents.count { !it.isEmpty }
                annotateInspectable(
                    icon,
                    "§eUbicación: §f${entry.locationLabel}",
                    "§7Ítems ocupados: §a$nonEmptyCount / ${entry.contents.size}",
                    "§bClic para abrir"
                )
                container.setItem(idx, icon)
                actions[idx] = { opener ->
                    openContainerContents(opener, target, entry.stack.copy(), "${entry.locationLabel}", 0) {
                        openContainersBrowser(opener, target, safePage)
                    }
                }
            }
        }

        for (s in 45..53) container.setItem(s, spacer(" "))
        container.setItem(45, menuButton(Items.ARROW, "§e§lVolver al Inventario", listOf("§7Regresar al inventario de ${target.scoreboardName}")))
        actions[45] = { opener -> openPlayerInventory(opener, target) }

        if (safePage > 0) {
            container.setItem(48, menuButton(Items.SPECTRAL_ARROW, "§aPágina Anterior", listOf("§7Página $safePage / $totalPages")))
            actions[48] = { opener -> openContainersBrowser(opener, target, safePage - 1) }
        }
        container.setItem(49, menuButton(Items.PAPER, "§fPágina ${safePage + 1} / $totalPages", listOf("§7Total encontrados: ${found.size}")))
        if (safePage + 1 < totalPages) {
            container.setItem(50, menuButton(Items.SPECTRAL_ARROW, "§aPágina Siguiente", listOf("§7Página ${safePage + 2} / $totalPages")))
            actions[50] = { opener -> openContainersBrowser(opener, target, safePage + 1) }
        }
        container.setItem(53, menuButton(Items.COMPASS, "§b§lActualizar", listOf("§7Re-escanear contenedores")))
        actions[53] = { opener -> openContainersBrowser(opener, target, safePage) }

        openReadOnlyMenu(admin, Component.literal("§8Mochilas/Shulkers: §6${target.scoreboardName}"), container, actions)
    }

    fun openContainerContents(
        admin: ServerPlayer,
        target: ServerPlayer,
        containerStack: ItemStack,
        titleLabel: String,
        page: Int = 0,
        backAction: (ServerPlayer) -> Unit
    ) {
        val contents = readContainerContents(containerStack, target)
        val perPage = 45
        val totalPages = ((contents.size - 1).coerceAtLeast(0) / perPage) + 1
        val safePage = page.coerceIn(0, totalPages - 1)

        val container = SimpleContainer(54)
        val actions = HashMap<Int, (ServerPlayer) -> Unit>()

        val start = safePage * perPage
        val slice = contents.drop(start).take(perPage)
        slice.forEachIndexed { idx, item ->
            if (!item.isEmpty) {
                val copy = item.copy()
                if (isInspectableContainer(item, target)) {
                    annotateInspectable(copy, "§bClic para abrir contenedor anidado")
                    actions[idx] = { opener ->
                        openContainerContents(opener, target, item.copy(), "$titleLabel > ${item.hoverName.string}", 0) { ret ->
                            openContainerContents(ret, target, containerStack, titleLabel, safePage, backAction)
                        }
                    }
                }
                container.setItem(idx, copy)
            }
        }

        for (s in 45..53) container.setItem(s, spacer(" "))
        container.setItem(45, menuButton(Items.ARROW, "§e§lVolver", listOf("§7Regresar a la vista anterior")))
        actions[45] = backAction

        if (safePage > 0) {
            container.setItem(48, menuButton(Items.SPECTRAL_ARROW, "§aPágina Anterior", listOf("§7Página $safePage / $totalPages")))
            actions[48] = { opener -> openContainerContents(opener, target, containerStack, titleLabel, safePage - 1, backAction) }
        }
        container.setItem(49, menuButton(Items.PAPER, "§fPágina ${safePage + 1} / $totalPages", listOf("§7Slots totales: ${contents.size}")))
        if (safePage + 1 < totalPages) {
            container.setItem(50, menuButton(Items.SPECTRAL_ARROW, "§aPágina Siguiente", listOf("§7Página ${safePage + 2} / $totalPages")))
            actions[50] = { opener -> openContainerContents(opener, target, containerStack, titleLabel, safePage + 1, backAction) }
        }

        val shortTitle = if (titleLabel.length > 24) titleLabel.take(22) + ".." else titleLabel
        openReadOnlyMenu(admin, Component.literal("§8Contenedor: §6$shortTitle"), container, actions)
    }

    private fun openReadOnlyMenu(
        admin: ServerPlayer,
        title: Component,
        container: SimpleContainer,
        actions: Map<Int, (ServerPlayer) -> Unit>
    ) {
        admin.openMenu(
            SimpleMenuProvider(
                { syncId, playerInv, _ ->
                    ReadOnlyInspectorMenu(syncId, playerInv, container, actions)
                },
                title
            )
        )
    }

    private class ReadOnlyInspectorMenu(
        syncId: Int,
        playerInv: Inventory,
        container: SimpleContainer,
        private val actions: Map<Int, (ServerPlayer) -> Unit>
    ) : ChestMenu(MenuType.GENERIC_9x6, syncId, playerInv, container, 6) {

        override fun stillValid(player: Player): Boolean = true

        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

        override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
            if (player is ServerPlayer) {
                carried = ItemStack.EMPTY
                sendAllDataToRemote()
                val action = actions[slotId]
                if (action != null) {
                    player.server.execute { action(player) }
                }
            }
        }
    }

    private fun spacer(name: String): ItemStack {
        val stack = ItemStack(Items.GRAY_STAINED_GLASS_PANE)
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name))
        return stack
    }

    private fun menuButton(item: net.minecraft.world.item.Item, title: String, lore: List<String>): ItemStack {
        val stack = ItemStack(item)
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(title))
        stack.set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))
        return stack
    }

    private fun annotateInspectable(stack: ItemStack, vararg extraLines: String) {
        val existing = stack.get(DataComponents.LORE)?.lines()?.toMutableList() ?: ArrayList()
        for (line in extraLines) {
            existing.add(Component.literal(line))
        }
        stack.set(DataComponents.LORE, ItemLore(existing))
    }
}
