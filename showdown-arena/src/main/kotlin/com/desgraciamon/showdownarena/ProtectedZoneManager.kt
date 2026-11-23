package com.desgraciamon.showdownarena

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.level.storage.LevelResource
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiPredicate

data class ProtectedZone(
    val name: String,
    val dimension: String,
    val minX: Int,
    val minZ: Int,
    val maxX: Int,
    val maxZ: Int,
    val isSpawn: Boolean = false
) {
    fun containsXZ(dim: String, bx: Int, bz: Int): Boolean {
        if (dimension != dim) return false
        val lx = minOf(minX, maxX)
        val hx = maxOf(minX, maxX)
        val lz = minOf(minZ, maxZ)
        val hz = maxOf(minZ, maxZ)
        return bx in lx..hx && bz in lz..hz
    }

    fun isSpawnZone(): Boolean = isSpawn || name.equals("spawn", ignoreCase = true)
}

object ProtectedZoneManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val zones = mutableListOf<ProtectedZone>()
    private val adminBypassDisabled = ConcurrentHashMap.newKeySet<UUID>()
    private var activeServer: MinecraftServer? = null

    private fun getFile(server: MinecraftServer): File {
        return server.getWorldPath(LevelResource.ROOT).resolve("data/desgraciamon_protected_zones.json").toFile()
    }

    fun init() {
        PlayerBlockBreakEvents.BEFORE.register { world, player, pos, _, _ ->
            if (world.isClientSide) return@register true
            val dim = world.dimension().location().toString()
            if (isBlockedForPlayer(player, dim, pos.x, pos.z)) {
                player.displayClientMessage(Component.literal("§cNo puedes quitar bloques en esta zona protegida."), true)
                return@register false
            }
            true
        }

        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (world.isClientSide) return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            if (stack.isEmpty) return@register InteractionResult.PASS
            val isPlacingItem = stack.item is BlockItem || stack.item is BucketItem
            if (!isPlacingItem) return@register InteractionResult.PASS

            val dim = world.dimension().location().toString()
            val targetPos = hitResult.blockPos.relative(hitResult.direction)
            val clickedPos = hitResult.blockPos
            if (isBlockedForPlayer(player, dim, targetPos.x, targetPos.z) || isBlockedForPlayer(player, dim, clickedPos.x, clickedPos.z)) {
                player.displayClientMessage(Component.literal("§cNo puedes colocar bloques en esta zona protegida."), true)
                if (player is ServerPlayer) {
                    player.connection.send(ClientboundBlockUpdatePacket(world, targetPos))
                    player.connection.send(ClientboundBlockUpdatePacket(world, clickedPos))
                    player.server.execute {
                        player.inventoryMenu.sendAllDataToRemote()
                    }
                }
                return@register InteractionResult.FAIL
            }
            InteractionResult.PASS
        }
    }

    private fun canBypass(player: Player): Boolean {
        if (!player.hasPermissions(2)) return false
        return !adminBypassDisabled.contains(player.uuid)
    }

    @Suppress("UNCHECKED_CAST")
    private fun hasConstructorBadge(player: Player): Boolean {
        val checker = FabricLoader.getInstance().objectShare.get("desgraciamon:has_badge") as? BiPredicate<UUID, String>
        return checker?.test(player.uuid, "constructor") == true
    }

    fun isBlockedForPlayer(player: Player, dimension: String, bx: Int, bz: Int): Boolean {
        if (canBypass(player)) return false
        val matching = synchronized(zones) {
            zones.filter { it.containsXZ(dimension, bx, bz) }
        }
        if (matching.isEmpty()) return false
        if (hasConstructorBadge(player) && matching.all { it.isSpawnZone() }) {
            return false
        }
        return true
    }

    fun isProtected(dimension: String, bx: Int, bz: Int): Boolean {
        synchronized(zones) {
            return zones.any { it.containsXZ(dimension, bx, bz) }
        }
    }

    fun onServerStart(server: MinecraftServer) {
        activeServer = server
        load(server)
    }

    fun onServerStop() {
        activeServer?.let { save(it) }
        activeServer = null
        synchronized(zones) { zones.clear() }
        adminBypassDisabled.clear()
    }

    private fun load(server: MinecraftServer) {
        try {
            val file = getFile(server)
            if (!file.exists()) return
            val type = object : TypeToken<List<ProtectedZone>>() {}.type
            val loaded: List<ProtectedZone> = gson.fromJson(file.readText(), type) ?: emptyList()
            synchronized(zones) {
                zones.clear()
                zones.addAll(loaded)
            }
        } catch (_: Exception) {}
    }

    private fun save(server: MinecraftServer? = activeServer) {
        val s = server ?: return
        try {
            val file = getFile(s)
            file.parentFile?.mkdirs()
            val snapshot = synchronized(zones) { zones.toList() }
            file.writeText(gson.toJson(snapshot))
        } catch (_: Exception) {}
    }

    fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val registerCmd = { cmdName: String ->
            dispatcher.register(
                Commands.literal(cmdName)
                    .requires { it.hasPermission(2) }
                    .then(
                        Commands.literal("crear")
                            .then(
                                Commands.argument("nombre", StringArgumentType.word())
                                    .then(
                                        Commands.argument("desde", BlockPosArgument.blockPos())
                                            .then(
                                                Commands.argument("hasta", BlockPosArgument.blockPos())
                                                    .executes { ctx ->
                                                        val name = StringArgumentType.getString(ctx, "nombre")
                                                        val p1 = BlockPosArgument.getBlockPos(ctx, "desde")
                                                        val p2 = BlockPosArgument.getBlockPos(ctx, "hasta")
                                                        val dim = ctx.source.level.dimension().location().toString()
                                                        val zone = ProtectedZone(
                                                            name = name,
                                                            dimension = dim,
                                                            minX = minOf(p1.x, p2.x),
                                                            minZ = minOf(p1.z, p2.z),
                                                            maxX = maxOf(p1.x, p2.x),
                                                            maxZ = maxOf(p1.z, p2.z),
                                                            isSpawn = name.equals("spawn", ignoreCase = true)
                                                        )
                                                        synchronized(zones) {
                                                            zones.removeAll { it.name.equals(name, ignoreCase = true) }
                                                            zones.add(zone)
                                                        }
                                                        save(ctx.source.server)
                                                        ctx.source.sendSuccess(
                                                            {
                                                                Component.literal(
                                                                    "§a[Protección] §fZona §e$name §fcreada en §7$dim §f(X: ${zone.minX}..${zone.maxX}, Z: ${zone.minZ}..${zone.maxZ}, altura completa Y)."
                                                                )
                                                            },
                                                            true
                                                        )
                                                        1
                                                    }
                                            )
                                    )
                            )
                    )
                    .then(
                        Commands.literal("spawn")
                            .then(
                                Commands.argument("desde", BlockPosArgument.blockPos())
                                    .then(
                                        Commands.argument("hasta", BlockPosArgument.blockPos())
                                            .executes { ctx ->
                                                val p1 = BlockPosArgument.getBlockPos(ctx, "desde")
                                                val p2 = BlockPosArgument.getBlockPos(ctx, "hasta")
                                                val dim = ctx.source.level.dimension().location().toString()
                                                val zone = ProtectedZone(
                                                    name = "spawn",
                                                    dimension = dim,
                                                    minX = minOf(p1.x, p2.x),
                                                    minZ = minOf(p1.z, p2.z),
                                                    maxX = maxOf(p1.x, p2.x),
                                                    maxZ = maxOf(p1.z, p2.z),
                                                    isSpawn = true
                                                )
                                                synchronized(zones) {
                                                    zones.removeAll { it.isSpawnZone() }
                                                    zones.add(zone)
                                                }
                                                save(ctx.source.server)
                                                ctx.source.sendSuccess(
                                                    {
                                                        Component.literal(
                                                            "§a[Spawn] §fZona protegida de Spawn definida en §7$dim §f(X: ${zone.minX}..${zone.maxX}, Z: ${zone.minZ}..${zone.maxZ}, Y completa de arriba a abajo). §eLos portadores de la insignia Constructor tienen bypass aquí."
                                                        )
                                                    },
                                                    true
                                                )
                                                1
                                            }
                                    )
                            )
                            .then(
                                Commands.literal("eliminar")
                                    .executes { ctx ->
                                        val removed = synchronized(zones) {
                                            zones.removeAll { it.isSpawnZone() }
                                        }
                                        if (!removed) {
                                            ctx.source.sendFailure(Component.literal("§cNo hay ninguna zona de Spawn configurada."))
                                            return@executes 0
                                        }
                                        save(ctx.source.server)
                                        ctx.source.sendSuccess({ Component.literal("§a[Spawn] §fZona protegida de Spawn eliminada.") }, true)
                                        1
                                    }
                            )
                            .then(
                                Commands.literal("info")
                                    .executes { ctx ->
                                        val spawn = synchronized(zones) { zones.firstOrNull { it.isSpawnZone() } }
                                        if (spawn == null) {
                                            ctx.source.sendSuccess({ Component.literal("§7No hay zona protegida de Spawn configurada.") }, false)
                                        } else {
                                            ctx.source.sendSuccess(
                                                { Component.literal("§6[Spawn] §7${spawn.dimension}: X[${spawn.minX}..${spawn.maxX}] Z[${spawn.minZ}..${spawn.maxZ}] (Altura completa Y)") },
                                                false
                                            )
                                        }
                                        1
                                    }
                            )
                    )
                    .then(
                        Commands.literal("eliminar")
                            .then(
                                Commands.argument("nombre", StringArgumentType.word())
                                    .suggests { _, builder ->
                                        synchronized(zones) {
                                            zones.forEach { z ->
                                                if (z.name.startsWith(builder.remaining, ignoreCase = true)) {
                                                    builder.suggest(z.name)
                                                }
                                            }
                                        }
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        val name = StringArgumentType.getString(ctx, "nombre")
                                        val removed = synchronized(zones) {
                                            zones.removeAll { it.name.equals(name, ignoreCase = true) }
                                        }
                                        if (!removed) {
                                            ctx.source.sendFailure(Component.literal("§cNo existe ninguna zona protegida llamada '$name'."))
                                            return@executes 0
                                        }
                                        save(ctx.source.server)
                                        ctx.source.sendSuccess(
                                            { Component.literal("§a[Protección] §fZona §e$name §feliminada.") },
                                            true
                                        )
                                        1
                                    }
                            )
                    )
                    .then(
                        Commands.literal("lista")
                            .executes { ctx ->
                                val list = synchronized(zones) { zones.toList() }
                                if (list.isEmpty()) {
                                    ctx.source.sendSuccess({ Component.literal("§7No hay zonas protegidas configuradas.") }, false)
                                } else {
                                    ctx.source.sendSuccess({ Component.literal("§b[Zonas Protegidas (${list.size})]:") }, false)
                                    list.forEach { z ->
                                        val tag = if (z.isSpawnZone()) " §6[SPAWN]" else ""
                                        ctx.source.sendSuccess(
                                            { Component.literal(" §e- ${z.name}$tag §7(${z.dimension}): X[${z.minX}..${z.maxX}] Z[${z.minZ}..${z.maxZ}] (Todas las Y)") },
                                            false
                                        )
                                    }
                                }
                                1
                            }
                    )
                    .then(
                        Commands.literal("limpiar")
                            .executes { ctx ->
                                synchronized(zones) { zones.clear() }
                                save(ctx.source.server)
                                ctx.source.sendSuccess({ Component.literal("§a[Protección] §fTodas las zonas protegidas fueron eliminadas.") }, true)
                                1
                            }
                    )
                    .then(
                        Commands.literal("test")
                            .executes { ctx ->
                                val player = ctx.source.playerOrException
                                if (adminBypassDisabled.remove(player.uuid)) {
                                    ctx.source.sendSuccess({ Component.literal("§a[Protección] §fModo prueba desactivado (vuelves a tener bypass de OP).") }, false)
                                } else {
                                    adminBypassDisabled.add(player.uuid)
                                    ctx.source.sendSuccess({ Component.literal("§e[Protección] §fModo prueba activado: ahora estás sujeto a las protecciones como jugador normal.") }, false)
                                }
                                1
                            }
                    )
            )
        }
        registerCmd("zonaprotegida")
        registerCmd("zp")
    }
}
