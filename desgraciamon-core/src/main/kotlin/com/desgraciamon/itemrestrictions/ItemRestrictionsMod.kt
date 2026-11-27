package com.desgraciamon.itemrestrictions

import com.desgraciamon.badges.AdminAuth
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ItemRestrictionsMod : ModInitializer {

    private val lastSyncedTags = ConcurrentHashMap<UUID, Set<String>>()
    private var tagPollTick = 0

    override fun onInitialize() {
        ItemRestrictionsConfig.load()

        PayloadTypeRegistry.playS2C().register(ItemRestrictionsSyncPayload.TYPE, ItemRestrictionsSyncPayload.CODEC)

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            lastSyncedTags[handler.player.uuid] = handler.player.tags.toSet()
            sendSync(handler.player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            lastSyncedTags.remove(handler.player.uuid)
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (++tagPollTick % 20 == 0) {
                for (player in server.playerList.players) {
                    val currentTags = player.tags
                    val prev = lastSyncedTags[player.uuid]
                    if (prev == null || prev.size != currentTags.size || !prev.containsAll(currentTags)) {
                        lastSyncedTags[player.uuid] = currentTags.toSet()
                        sendSync(player)
                    }
                }
            }
        }

        UseItemCallback.EVENT.register { player, world, hand ->
            val stack = player.getItemInHand(hand)
            if (world.isClientSide) return@register InteractionResultHolder.pass(stack)
            if (isRestricted(player, stack)) {
                notifyRestrictedItem(player)
                return@register InteractionResultHolder.fail(stack)
            }
            InteractionResultHolder.pass(stack)
        }

        UseBlockCallback.EVENT.register { player, world, hand, _ ->
            if (world.isClientSide) return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            if (isRestricted(player, stack)) {
                notifyRestrictedItem(player)
                return@register InteractionResult.FAIL
            }
            InteractionResult.PASS
        }

        UseEntityCallback.EVENT.register { player, world, hand, _, _ ->
            if (world.isClientSide) return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            if (isRestricted(player, stack)) {
                notifyRestrictedItem(player)
                return@register InteractionResult.FAIL
            }
            InteractionResult.PASS
        }

        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register { player, world, hand, _, _ ->
            if (world.isClientSide) return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            if (isRestricted(player, stack)) {
                notifyRestrictedItem(player)
                return@register InteractionResult.FAIL
            }
            InteractionResult.PASS
        }

        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register { player, world, hand, _, _ ->
            if (world.isClientSide) return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            if (isRestricted(player, stack)) {
                notifyRestrictedItem(player)
                return@register InteractionResult.FAIL
            }
            InteractionResult.PASS
        }

        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register { world, player, _, _, _ ->
            if (world.isClientSide) return@register true
            val stack = player.mainHandItem
            if (isRestricted(player, stack)) {
                notifyRestrictedItem(player)
                return@register false
            }
            true
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerCommands(dispatcher)
        }

        LOGGER.info("Desgraciamon 3 Item Restrictions inicializado correctamente.")
    }

    private fun notifyRestrictedItem(player: Player) {
        player.displayClientMessage(TextCatalog.component("item.disabled"), true)
    }

    private fun normalizeItemOrTag(rawInput: String): String {
        val clean = rawInput.lowercase().trim().removeSurrounding("\"")
        if (clean.startsWith("#")) return clean
        return if (clean.contains(":")) clean else "minecraft:$clean"
    }

    private fun registerCommands(dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>) {
        val itemSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
            SharedSuggestionProvider.suggest(BuiltInRegistries.ITEM.keySet().map { it.toString() }, builder)
        }

        val root = Commands.literal("restringir")
            .requires { AdminAuth.isAdminSource(it) }
            .then(Commands.literal("bypass").executes { ctx ->
                val player = ctx.source.playerOrException
                val wasBypassed = bypassedPlayers.contains(player.uuid)
                if (wasBypassed) {
                    bypassedPlayers.remove(player.uuid)
                    sendSync(player)
                    ctx.source.sendSuccess({
                        Component.literal("§6[Restringir] §aModo restriccion ACTIVADO. §7Los items restringidos ahora te bloquean y se ven en rojo.")
                    }, false)
                } else {
                    bypassedPlayers.add(player.uuid)
                    sendSync(player)
                    ctx.source.sendSuccess({
                        Component.literal("§6[Restringir] §cModo bypass ACTIVADO. §7Puedes usar items restringidos con normalidad.")
                    }, false)
                }
                1
            })
            .then(Commands.literal("lista").executes { ctx ->
                val config = ItemRestrictionsConfig.current
                ctx.source.sendSuccess({
                    Component.literal("§b=== Items Restringidos Globales (${config.restrictedItems.size + config.restrictedTags.size}) ===")
                }, false)
                for (item in config.restrictedItems) {
                    ctx.source.sendSuccess({ Component.literal(" §7- §c$item") }, false)
                }
                for (t in config.restrictedTags) {
                    ctx.source.sendSuccess({ Component.literal(" §7- §e$t") }, false)
                }
                if (config.playerRestrictions.isNotEmpty()) {
                    ctx.source.sendSuccess({ Component.literal("§b=== Restricciones por Jugador ===") }, false)
                    for ((pKey, items) in config.playerRestrictions) {
                        ctx.source.sendSuccess({ Component.literal(" §7- §f$pKey§7: §c${items.joinToString(", ")}") }, false)
                    }
                }
                if (config.tagRestrictions.isNotEmpty()) {
                    ctx.source.sendSuccess({ Component.literal("§b=== Restricciones por Tag ===") }, false)
                    for ((tKey, items) in config.tagRestrictions) {
                        ctx.source.sendSuccess({ Component.literal(" §7- §e$tKey§7: §c${items.joinToString(", ")}") }, false)
                    }
                }
                1
            })
            .then(Commands.literal("agregar")
                .then(Commands.literal("todos")
                    .then(Commands.argument("item", StringArgumentType.greedyString())
                        .suggests(itemSuggestions)
                        .executes { ctx ->
                            val normalized = normalizeItemOrTag(StringArgumentType.getString(ctx, "item"))
                            val config = ItemRestrictionsConfig.current
                            val added = if (normalized.startsWith("#")) {
                                config.restrictedTags.add(normalized)
                            } else {
                                config.restrictedItems.add(normalized)
                            }
                            if (added) {
                                ItemRestrictionsConfig.save()
                                broadcastSync(ctx.source.server)
                                ctx.source.sendSuccess({ Component.literal("§aRestriccion global agregada: §e$normalized") }, true)
                            } else {
                                ctx.source.sendFailure(Component.literal("§c$normalized ya estaba restringido globalmente."))
                            }
                            1
                        }
                    )
                )
                .then(Commands.literal("jugador")
                    .then(Commands.argument("jugador", EntityArgument.player())
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                            .suggests(itemSuggestions)
                            .executes { ctx ->
                                val target = EntityArgument.getPlayer(ctx, "jugador")
                                val normalized = normalizeItemOrTag(StringArgumentType.getString(ctx, "item"))
                                val pKey = target.scoreboardName.lowercase()
                                val set = ItemRestrictionsConfig.current.playerRestrictions.computeIfAbsent(pKey) { LinkedHashSet() }
                                if (set.add(normalized)) {
                                    ItemRestrictionsConfig.save()
                                    sendSync(target)
                                    ctx.source.sendSuccess({ Component.literal("§aRestriccion §e$normalized §aagregada para el jugador §f${target.scoreboardName}§a.") }, true)
                                } else {
                                    ctx.source.sendFailure(Component.literal("§c${target.scoreboardName} ya tenia restringido $normalized."))
                                }
                                1
                            }
                        )
                    )
                )
                .then(Commands.literal("tag")
                    .then(Commands.argument("etiqueta", StringArgumentType.word())
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                            .suggests(itemSuggestions)
                            .executes { ctx ->
                                val tagKey = StringArgumentType.getString(ctx, "etiqueta").lowercase().trim()
                                val normalized = normalizeItemOrTag(StringArgumentType.getString(ctx, "item"))
                                val set = ItemRestrictionsConfig.current.tagRestrictions.computeIfAbsent(tagKey) { LinkedHashSet() }
                                if (set.add(normalized)) {
                                    ItemRestrictionsConfig.save()
                                    broadcastSync(ctx.source.server)
                                    ctx.source.sendSuccess({ Component.literal("§aRestriccion §e$normalized §aagregada para jugadores con tag §b$tagKey§a.") }, true)
                                } else {
                                    ctx.source.sendFailure(Component.literal("§cEl tag $tagKey ya tenia restringido $normalized."))
                                }
                                1
                            }
                        )
                    )
                )
            )
            .then(Commands.literal("quitar")
                .then(Commands.literal("todos")
                    .then(Commands.argument("item", StringArgumentType.greedyString())
                        .suggests { _, builder ->
                            val all = ItemRestrictionsConfig.current.restrictedItems + ItemRestrictionsConfig.current.restrictedTags
                            SharedSuggestionProvider.suggest(all, builder)
                        }
                        .executes { ctx ->
                            val normalized = normalizeItemOrTag(StringArgumentType.getString(ctx, "item"))
                            val config = ItemRestrictionsConfig.current
                            val removed = config.restrictedItems.remove(normalized) || config.restrictedTags.remove(normalized)
                            if (removed) {
                                ItemRestrictionsConfig.save()
                                broadcastSync(ctx.source.server)
                                ctx.source.sendSuccess({ Component.literal("§aRestriccion global §e$normalized §aeliminada.") }, true)
                            } else {
                                ctx.source.sendFailure(Component.literal("§c$normalized no estaba en la lista global."))
                            }
                            1
                        }
                    )
                )
                .then(Commands.literal("jugador")
                    .then(Commands.argument("jugador", StringArgumentType.word())
                        .suggests { ctx, builder ->
                            val keys = LinkedHashSet<String>()
                            keys.addAll(ItemRestrictionsConfig.current.playerRestrictions.keys)
                            ctx.source.server.playerList.players.forEach { keys.add(it.scoreboardName.lowercase()) }
                            SharedSuggestionProvider.suggest(keys, builder)
                        }
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                            .suggests { ctx, builder ->
                                val pKey = runCatching { StringArgumentType.getString(ctx, "jugador").lowercase() }.getOrDefault("")
                                val items = ItemRestrictionsConfig.current.playerRestrictions[pKey].orEmpty()
                                SharedSuggestionProvider.suggest(items, builder)
                            }
                            .executes { ctx ->
                                val pKey = StringArgumentType.getString(ctx, "jugador").lowercase().trim()
                                val normalized = normalizeItemOrTag(StringArgumentType.getString(ctx, "item"))
                                val set = ItemRestrictionsConfig.current.playerRestrictions[pKey]
                                if (set != null && set.remove(normalized)) {
                                    if (set.isEmpty()) ItemRestrictionsConfig.current.playerRestrictions.remove(pKey)
                                    ItemRestrictionsConfig.save()
                                    broadcastSync(ctx.source.server)
                                    ctx.source.sendSuccess({ Component.literal("§aRestriccion §e$normalized §aeliminada para el jugador §f$pKey§a.") }, true)
                                } else {
                                    ctx.source.sendFailure(Component.literal("§cEl jugador $pKey no tenia restringido $normalized."))
                                }
                                1
                            }
                        )
                    )
                )
                .then(Commands.literal("tag")
                    .then(Commands.argument("etiqueta", StringArgumentType.word())
                        .suggests { _, builder ->
                            SharedSuggestionProvider.suggest(ItemRestrictionsConfig.current.tagRestrictions.keys, builder)
                        }
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                            .suggests { ctx, builder ->
                                val tKey = runCatching { StringArgumentType.getString(ctx, "etiqueta").lowercase() }.getOrDefault("")
                                val items = ItemRestrictionsConfig.current.tagRestrictions[tKey].orEmpty()
                                SharedSuggestionProvider.suggest(items, builder)
                            }
                            .executes { ctx ->
                                val tagKey = StringArgumentType.getString(ctx, "etiqueta").lowercase().trim()
                                val normalized = normalizeItemOrTag(StringArgumentType.getString(ctx, "item"))
                                val set = ItemRestrictionsConfig.current.tagRestrictions[tagKey]
                                if (set != null && set.remove(normalized)) {
                                    if (set.isEmpty()) ItemRestrictionsConfig.current.tagRestrictions.remove(tagKey)
                                    ItemRestrictionsConfig.save()
                                    broadcastSync(ctx.source.server)
                                    ctx.source.sendSuccess({ Component.literal("§aRestriccion §e$normalized §aeliminada para el tag §b$tagKey§a.") }, true)
                                } else {
                                    ctx.source.sendFailure(Component.literal("§cEl tag $tagKey no tenia restringido $normalized."))
                                }
                                1
                            }
                        )
                    )
                )
            )

        dispatcher.register(root)
    }

    companion object {
        val LOGGER = LoggerFactory.getLogger("desgraciamon4-item-restrictions")
        val bypassedPlayers = ConcurrentHashMap.newKeySet<UUID>()

        private fun effectiveRestrictionsFor(player: Player): Pair<Set<String>, Set<String>> {
            val config = ItemRestrictionsConfig.current
            val items = LinkedHashSet<String>()
            val tags = LinkedHashSet<String>()

            items.addAll(config.restrictedItems)
            tags.addAll(config.restrictedTags)

            val playerKeyName = player.scoreboardName.lowercase()
            val playerKeyUuid = player.uuid.toString().lowercase()
            val perPlayer = buildList {
                config.playerRestrictions[playerKeyName]?.let { addAll(it) }
                config.playerRestrictions[playerKeyUuid]?.let { addAll(it) }
            }
            for (entry in perPlayer) {
                if (entry.startsWith("#")) tags.add(entry) else items.add(entry)
            }

            for (entityTag in player.tags) {
                val perTag = config.tagRestrictions[entityTag.lowercase()] ?: continue
                for (entry in perTag) {
                    if (entry.startsWith("#")) tags.add(entry) else items.add(entry)
                }
            }

            return items to tags
        }

        @JvmStatic
        fun isRestricted(player: Player, stack: ItemStack): Boolean {
            if (stack.isEmpty) return false
            if (player.isCreative || bypassedPlayers.contains(player.uuid)) return false

            val (effectiveItems, effectiveTags) = effectiveRestrictionsFor(player)
            val itemKey = BuiltInRegistries.ITEM.getKey(stack.item).toString().lowercase()
            if (effectiveItems.contains(itemKey)) return true

            val allTagCandidates = effectiveTags + effectiveItems.filter { it.startsWith("#") }
            for (tagKey in allTagCandidates) {
                val cleanTag = tagKey.removePrefix("#")
                val rl = ResourceLocation.tryParse(cleanTag) ?: continue
                val tag = TagKey.create(Registries.ITEM, rl)
                if (stack.`is`(tag)) return true
            }

            return false
        }

        fun sendSync(player: ServerPlayer) {
            val isBypassed = bypassedPlayers.contains(player.uuid)
            val active = !isBypassed
            val (effectiveItems, effectiveTags) = effectiveRestrictionsFor(player)
            ServerPlayNetworking.send(
                player,
                ItemRestrictionsSyncPayload(
                    effectiveItems.toList(),
                    effectiveTags.toList(),
                    active
                )
            )
        }

        fun broadcastSync(server: MinecraftServer) {
            server.playerList.players.forEach { sendSync(it) }
        }
    }
}
