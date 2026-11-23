package com.desgraciamon.cinematics

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CinematicCommands {

    // Guarda qué ruta exacta está editando cada jugador en su sesión de director
    val activeEditingRoutes = ConcurrentHashMap<UUID, String>()

    fun getPlayerActiveRoute(player: ServerPlayer): CinematicRoute? {
        val name = activeEditingRoutes[player.uuid] ?: return null
        return CinematicRouteStorage.getRoute(name)
    }

    private fun btn(
        label: String,
        command: String,
        hoverText: String = "",
        color: ChatFormatting = ChatFormatting.YELLOW,
        suggest: Boolean = false
    ): MutableComponent {
        val action = if (suggest) ClickEvent.Action.SUGGEST_COMMAND else ClickEvent.Action.RUN_COMMAND
        var style = Style.EMPTY.withColor(color).withClickEvent(ClickEvent(action, command))
        if (hoverText.isNotBlank()) {
            style = style.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hoverText)))
        }
        return Component.literal(label).withStyle(style)
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        // -------------------------------------------------------------
        // COMANDO DE TRANSMISIÓN OFICIAL: /cinematic
        // -------------------------------------------------------------
        val rootCinematica = Commands.literal("cinematicas")
                .requires { it.hasPermission(2) }
                .then(
                    Commands.literal("toolbox")
                        .executes { ctx ->
                            DirectorToolbox.giveToolbox(ctx.source.playerOrException)
                            1
                        }
                )
                .then(
                    Commands.literal("reproducir")
                        .then(
                            Commands.argument("ruta", StringArgumentType.word())
                                .suggests { _, builder ->
                                    CinematicRouteStorage.getAllRoutes().forEach { builder.suggest(it.name) }
                                    builder.buildFuture()
                                }
                                .executes { ctx ->
                                    val routeName = StringArgumentType.getString(ctx, "ruta")
                                    val route = CinematicRouteStorage.getRoute(routeName)
                                        ?: run {
                                            ctx.source.sendFailure(Component.literal("§cNo existe ninguna ruta llamada '$routeName'."))
                                            return@executes 0
                                        }
                                    CinematicServerManager.playCinematic(ctx.source.server, route)
                                    1
                                }
                                .then(
                                    Commands.argument("objetivo", EntityArgument.player())
                                        .executes { ctx ->
                                            val routeName = StringArgumentType.getString(ctx, "ruta")
                                            val target = EntityArgument.getPlayer(ctx, "objetivo")
                                            val route = CinematicRouteStorage.getRoute(routeName)
                                                ?: run {
                                                    ctx.source.sendFailure(Component.literal("§cNo existe ninguna ruta llamada '$routeName'."))
                                                    return@executes 0
                                                }
                                            CinematicServerManager.playCinematic(ctx.source.server, route, target)
                                            ctx.source.sendSuccess({ Component.literal("§aReproduciendo '$routeName' para ${target.scoreboardName}.") }, true)
                                            1
                                        }
                                )
                        )
                )
                .then(
                    Commands.literal("detener")
                        .executes { ctx ->
                            CinematicServerManager.stopCinematic(ctx.source.server, "manual_stop")
                            ctx.source.sendSuccess({ Component.literal("§eCinemática detenida manualmente.") }, true)
                            1
                        }
                )
                .then(
                    Commands.literal("pausar")
                        .executes { ctx ->
                            if (CinematicServerManager.pauseCinematic(ctx.source.server)) {
                                ctx.source.sendSuccess({ Component.literal("§eCinemática pausada.") }, true)
                            } else {
                                ctx.source.sendFailure(Component.literal("§cNo hay ninguna cinemática activa para pausar."))
                            }
                            1
                        }
                )
                .then(
                    Commands.literal("reanudar")
                        .executes { ctx ->
                            if (CinematicServerManager.resumeCinematic(ctx.source.server)) {
                                ctx.source.sendSuccess({ Component.literal("§aCinemática reanudada.") }, true)
                            } else {
                                ctx.source.sendFailure(Component.literal("§cLa cinemática no está pausada."))
                            }
                            1
                        }
                )
                .then(
                    Commands.literal("saltar_a")
                        .then(
                            Commands.argument("segundos", DoubleArgumentType.doubleArg(0.0))
                                .executes { ctx ->
                                    val sec = DoubleArgumentType.getDouble(ctx, "segundos")
                                    if (CinematicServerManager.seekCinematic(ctx.source.server, sec)) {
                                        ctx.source.sendSuccess({ Component.literal("§bCinemática adelantada a ${sec}s.") }, true)
                                    } else {
                                        ctx.source.sendFailure(Component.literal("§cNo hay ninguna cinemática activa."))
                                    }
                                    1
                                }
                        )
                )
                .then(
                    Commands.literal("lista")
                        .executes { ctx ->
                            val routes = CinematicRouteStorage.getAllRoutes()
                            ctx.source.sendSuccess({ Component.literal("§6=== Rutas Cinemáticas Disponibles (${routes.size}) ===") }, false)
                            routes.forEach { r ->
                                ctx.source.sendSuccess({ Component.literal("§e- §f${r.name} §7(${r.keyframes.size} puntos, ~${r.totalDurationSeconds().toInt()}s): §8${r.description}") }, false)
                            }
                            1
                        }
                )
                .then(
                    Commands.literal("recargar")
                        .executes { ctx ->
                            CinematicRouteStorage.reloadAll()
                            ctx.source.sendSuccess({ Component.literal("§aRutas cinemáticas recargadas desde disco.") }, true)
                            1
                        }
                )

        val directorNode = Commands.literal("director")
            .requires { it.hasPermission(2) }
                .then(
                    Commands.literal("toolbox")
                        .executes { ctx ->
                            DirectorToolbox.giveToolbox(ctx.source.playerOrException)
                            1
                        }
                )
                .then(
                    Commands.literal("gui")
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            val route = getPlayerActiveRoute(player)
                                ?: run {
                                    ctx.source.sendFailure(Component.literal("§cPrimero entra en modo director con §e/cinematicas director editar <nombre>"))
                                    return@executes 0
                                }
                            CinematicPackets.openEditorGui(player, CinematicRouteStorage.toJson(route))
                            1
                        }
                )
                .then(
                    Commands.literal("editar")
                        .then(
                            Commands.argument("nombre", StringArgumentType.word())
                                .suggests { _, builder ->
                                    CinematicRouteStorage.getAllRoutes().forEach { builder.suggest(it.name) }
                                    builder.buildFuture()
                                }
                                .executes { ctx ->
                                    val player = ctx.source.playerOrException
                                    val name = StringArgumentType.getString(ctx, "nombre").lowercase()
                                    var route = CinematicRouteStorage.getRoute(name)
                                    if (route == null) {
                                        route = CinematicRoute(name = name, description = "Ruta creada con modo director")
                                        CinematicRouteStorage.saveRoute(route)
                                        player.sendSystemMessage(Component.literal("§aCreada nueva ruta '§e$name§a'."))
                                    }

                                    // Registrar al jugador editando esta ruta específica
                                    activeEditingRoutes[player.uuid] = route.name.lowercase()

                                    CinematicPackets.play(player, route.name, CinematicRouteStorage.toJson(route), -1L) // -1L = modo editor

                                    player.sendSystemMessage(Component.literal("§6═════════════════════════════════════════════════════"))
                                    player.sendSystemMessage(Component.literal("§6§lMODO DIRECTOR: §fEditando '§e${route.name}§f' §7(${route.keyframes.size} puntos, ~${route.totalDurationSeconds().toInt()}s)"))
                                    player.sendSystemMessage(
                                        Component.literal("  ")
                                            .append(btn("[Añadir Punto]", "/cinematicas director agregar", "Añadir punto clave en tu posición y vista actual", ChatFormatting.GREEN))
                                            .append("  ")
                                            .append(btn("[Ver Puntos]", "/cinematicas director puntos", "Ver y configurar cada punto de la ruta", ChatFormatting.AQUA))
                                            .append("  ")
                                            .append(btn("[GUI]", "/cinematicas director gui", "Abrir editor visual de ruta", ChatFormatting.LIGHT_PURPLE))
                                            .append("  ")
                                            .append(btn("[Toolbox]", "/cinematicas toolbox", "Obtener herramientas en mano", ChatFormatting.YELLOW))
                                            .append("  ")
                                            .append(btn("[Probar]", "/cinematicas director probar", "Reproducir cinemática en tiempo real", ChatFormatting.GOLD))
                                            .append("  ")
                                            .append(btn("[Salir]", "/cinematicas director salir", "Guardar cambios y salir", ChatFormatting.RED))
                                    )
                                    player.sendSystemMessage(Component.literal("§7Acércate a cualquier punto para ver sus datos e índices flotantes."))
                                    player.sendSystemMessage(Component.literal("§6═════════════════════════════════════════════════════"))
                                    1
                                }
                        )
                )
                .then(
                    Commands.literal("agregar")
                        .executes { ctx -> addKeyframe(ctx, 4.0) }
                        .then(
                            Commands.argument("duracion", DoubleArgumentType.doubleArg(0.2, 60.0))
                                .executes { ctx -> addKeyframe(ctx, DoubleArgumentType.getDouble(ctx, "duracion")) }
                        )
                )
                .then(
                    Commands.literal("insertar")
                        .then(
                            Commands.argument("indice", IntegerArgumentType.integer(1))
                                .executes { ctx -> insertKeyframe(ctx, 4.0) }
                                .then(
                                    Commands.argument("duracion", DoubleArgumentType.doubleArg(0.2, 60.0))
                                        .executes { ctx -> insertKeyframe(ctx, DoubleArgumentType.getDouble(ctx, "duracion")) }
                                )
                        )
                )
                .then(
                    Commands.literal("remover")
                        .then(
                            Commands.argument("indice", IntegerArgumentType.integer(1))
                                .executes { ctx ->
                                    val player = ctx.source.playerOrException
                                    val active = getPlayerActiveRoute(player) ?: run {
                                        player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
                                        return@executes 0
                                    }
                                    val idx = IntegerArgumentType.getInteger(ctx, "indice") - 1
                                    if (idx in active.keyframes.indices) {
                                        active.keyframes.removeAt(idx)
                                        active.keyframes.forEachIndexed { i, k -> k.id = i + 1 }
                                        CinematicRouteStorage.saveRoute(active)
                                        CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
                                        player.sendSystemMessage(Component.literal("§cEliminado punto #${idx + 1} de '${active.name}'. Quedan ${active.keyframes.size} puntos."))
                                    } else {
                                        player.sendSystemMessage(Component.literal("§cÍndice fuera de rango (1..${active.keyframes.size})."))
                                    }
                                    1
                                }
                        )
                )
                .then(
                    Commands.literal("mover")
                        .executes { ctx ->
                            // Si no se especifica índice, mover el punto más cercano al jugador
                            val player = ctx.source.playerOrException
                            val active = getPlayerActiveRoute(player) ?: run {
                                player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
                                return@executes 0
                            }
                            if (active.keyframes.isEmpty()) {
                                player.sendSystemMessage(Component.literal("§cNo hay puntos para mover. Añade uno con /director add"))
                                return@executes 0
                            }
                            val pPos = player.position()
                            var bestIdx = 0
                            var minDist = Double.MAX_VALUE
                            for ((i, k) in active.keyframes.withIndex()) {
                                val d = pPos.distanceTo(k.position())
                                if (d < minDist) {
                                    minDist = d
                                    bestIdx = i
                                }
                            }
                            moveKeyframeIndex(player, active, bestIdx)
                            1
                        }
                        .then(
                            Commands.argument("indice", IntegerArgumentType.integer(1))
                                .executes { ctx ->
                                    val player = ctx.source.playerOrException
                                    val active = getPlayerActiveRoute(player) ?: run {
                                        player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
                                        return@executes 0
                                    }
                                    val idx = IntegerArgumentType.getInteger(ctx, "indice") - 1
                                    if (idx in active.keyframes.indices) {
                                        moveKeyframeIndex(player, active, idx)
                                    } else {
                                        player.sendSystemMessage(Component.literal("§cÍndice fuera de rango (1..${active.keyframes.size})."))
                                    }
                                    1
                                }
                        )
                )
                .then(
                    Commands.literal("tp")
                        .then(
                            Commands.argument("indice", IntegerArgumentType.integer(1))
                                .executes { ctx ->
                                    val player = ctx.source.playerOrException
                                    val active = getPlayerActiveRoute(player) ?: run {
                                        player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
                                        return@executes 0
                                    }
                                    val idx = IntegerArgumentType.getInteger(ctx, "indice") - 1
                                    if (idx in active.keyframes.indices) {
                                        val k = active.keyframes[idx]
                                        player.teleportTo(k.x, k.y - player.eyeHeight, k.z)
                                        player.yRot = k.yaw
                                        player.xRot = k.pitch
                                        player.sendSystemMessage(Component.literal("§aTeletransportado a la vista del Punto #${idx + 1}."))
                                    } else {
                                        player.sendSystemMessage(Component.literal("§cÍndice fuera de rango (1..${active.keyframes.size})."))
                                    }
                                    1
                                }
                        )
                )
                .then(
                    Commands.literal("transicion")
                        .then(
                            Commands.argument("indice", IntegerArgumentType.integer(1))
                                .then(
                                    Commands.argument("color", StringArgumentType.word())
                                        .suggests { _, builder ->
                                            listOf("black", "white", "gold", "red", "blue", "cyan", "purple", "dark", "none", "#000000", "#ffffff", "#ffd700").forEach { builder.suggest(it) }
                                            builder.buildFuture()
                                        }
                                        .executes { ctx ->
                                            setFade(ctx, 2.0)
                                        }
                                        .then(
                                            Commands.argument("duracion", DoubleArgumentType.doubleArg(0.2, 10.0))
                                                .executes { ctx ->
                                                    setFade(ctx, DoubleArgumentType.getDouble(ctx, "duracion"))
                                                }
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("probar_transicion")
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            CinematicPackets.trigger(player, "fade", "black", 2.0f)
                            player.sendSystemMessage(Component.literal("§eProbando fade de color §0[Negro]§e por 2.0s."))
                            1
                        }
                        .then(
                            Commands.argument("color", StringArgumentType.word())
                                .suggests { _, builder ->
                                    listOf("black", "white", "gold", "red", "blue", "cyan", "purple", "dark", "#000000", "#ffffff", "#ffd700").forEach { builder.suggest(it) }
                                    builder.buildFuture()
                                }
                                .executes { ctx ->
                                    val player = ctx.source.playerOrException
                                    val color = StringArgumentType.getString(ctx, "color")
                                    CinematicPackets.trigger(player, "fade", color, 2.0f)
                                    player.sendSystemMessage(Component.literal("§eProbando fade de color '§f$color§e' por 2.0s."))
                                    1
                                }
                                .then(
                                    Commands.argument("duracion", DoubleArgumentType.doubleArg(0.2, 10.0))
                                        .executes { ctx ->
                                            val player = ctx.source.playerOrException
                                            val color = StringArgumentType.getString(ctx, "color")
                                            val dur = DoubleArgumentType.getDouble(ctx, "duracion").toFloat()
                                            CinematicPackets.trigger(player, "fade", color, dur)
                                            player.sendSystemMessage(Component.literal("§eProbando fade de color '§f$color§e' por ${dur}s."))
                                            1
                                        }
                                )
                        )
                )
                .then(
                    Commands.literal("tiempo")
                        .then(
                            Commands.argument("indice", IntegerArgumentType.integer(1))
                                .then(
                                    Commands.argument("segundos", DoubleArgumentType.doubleArg(0.2, 60.0))
                                        .executes { ctx ->
                                            val player = ctx.source.playerOrException
                                            val active = getPlayerActiveRoute(player) ?: run {
                                                player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
                                                return@executes 0
                                            }
                                            val idx = IntegerArgumentType.getInteger(ctx, "indice") - 1
                                            val dur = DoubleArgumentType.getDouble(ctx, "segundos")
                                            if (idx in active.keyframes.indices) {
                                                active.keyframes[idx].durationSeconds = dur
                                                CinematicRouteStorage.saveRoute(active)
                                                CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
                                                player.sendSystemMessage(Component.literal("§aDuración del Punto #${idx + 1} actualizada a §e${dur}s§a."))
                                            } else {
                                                player.sendSystemMessage(Component.literal("§cÍndice fuera de rango (1..${active.keyframes.size})."))
                                            }
                                            1
                                        }
                                )
                        )
                )
                .then(
                    Commands.literal("curva")
                        .then(
                            Commands.argument("indice", IntegerArgumentType.integer(1))
                                .then(
                                    Commands.argument("modo", StringArgumentType.word())
                                        .suggests { _, builder ->
                                            EasingMode.entries.forEach { builder.suggest(it.name.lowercase()) }
                                            builder.buildFuture()
                                        }
                                        .executes { ctx ->
                                            val player = ctx.source.playerOrException
                                            val active = getPlayerActiveRoute(player) ?: run {
                                                player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
                                                return@executes 0
                                            }
                                            val idx = IntegerArgumentType.getInteger(ctx, "indice") - 1
                                            val modeStr = StringArgumentType.getString(ctx, "modo").uppercase()
                                            val mode = runCatching { EasingMode.valueOf(modeStr) }.getOrNull() ?: EasingMode.EASE_IN_OUT
                                            if (idx in active.keyframes.indices) {
                                                active.keyframes[idx].easing = mode
                                                CinematicRouteStorage.saveRoute(active)
                                                CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
                                                player.sendSystemMessage(Component.literal("§aSuavizado del punto #${idx + 1} cambiado a §e$mode§a."))
                                            }
                                            1
                                        }
                                )
                        )
                )
                .then(
                    Commands.literal("shader")
                        .then(
                            Commands.argument("indice", IntegerArgumentType.integer(1))
                                .then(
                                    Commands.argument("efecto", StringArgumentType.word())
                                        .suggests { _, builder ->
                                            listOf("swirl", "shake", "nostalgia", "flash_gold", "flash_white", "none").forEach { builder.suggest(it) }
                                            builder.buildFuture()
                                        }
                                        .executes { ctx ->
                                            val player = ctx.source.playerOrException
                                            val active = getPlayerActiveRoute(player) ?: run {
                                                player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
                                                return@executes 0
                                            }
                                            val idx = IntegerArgumentType.getInteger(ctx, "indice") - 1
                                            val eff = StringArgumentType.getString(ctx, "efecto")
                                            if (idx in active.keyframes.indices) {
                                                active.keyframes[idx].shaderEffect = if (eff == "none") null else eff
                                                CinematicRouteStorage.saveRoute(active)
                                                CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
                                                player.sendSystemMessage(Component.literal("§aEfecto de shader del punto #${idx + 1}: §e$eff§a."))
                                            }
                                            1
                                        }
                                )
                        )
                )
                .then(
                    Commands.literal("animacion")
                        .then(
                            Commands.argument("indice", IntegerArgumentType.integer(1))
                                .then(
                                    Commands.argument("animacion", StringArgumentType.string())
                                        .executes { ctx ->
                                            val player = ctx.source.playerOrException
                                            val active = getPlayerActiveRoute(player) ?: run {
                                                player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
                                                return@executes 0
                                            }
                                            val idx = IntegerArgumentType.getInteger(ctx, "indice") - 1
                                            val anim = StringArgumentType.getString(ctx, "animacion")
                                            if (idx in active.keyframes.indices) {
                                                active.keyframes[idx].animationTrigger = if (anim.isBlank() || anim == "none") null else anim
                                                CinematicRouteStorage.saveRoute(active)
                                                CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
                                                player.sendSystemMessage(Component.literal("§aAnimación 2D vinculada al punto #${idx + 1}: §e$anim§a."))
                                            }
                                            1
                                        }
                                )
                        )
                )
                .then(
                    Commands.literal("saltar")
                        .then(
                            Commands.argument("indice", IntegerArgumentType.integer(1))
                                .executes { ctx ->
                                    toggleJump(ctx, null)
                                }
                                .then(
                                    Commands.argument("activo", BoolArgumentType.bool())
                                        .executes { ctx ->
                                            toggleJump(ctx, BoolArgumentType.getBool(ctx, "activo"))
                                        }
                                )
                        )
                )
                .then(
                    Commands.literal("subtitulo")
                        .then(
                            Commands.argument("indice", IntegerArgumentType.integer(1))
                                .then(
                                    Commands.argument("texto", StringArgumentType.greedyString())
                                        .executes { ctx ->
                                            val player = ctx.source.playerOrException
                                            val active = getPlayerActiveRoute(player) ?: run {
                                                player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
                                                return@executes 0
                                            }
                                            val idx = IntegerArgumentType.getInteger(ctx, "indice") - 1
                                            val text = StringArgumentType.getString(ctx, "texto")
                                            if (idx in active.keyframes.indices) {
                                                active.keyframes[idx].subtitle = if (text == "none" || text.isBlank()) null else text
                                                CinematicRouteStorage.saveRoute(active)
                                                CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
                                                player.sendSystemMessage(Component.literal("§aSubtítulo del punto #${idx + 1}: §f\"${active.keyframes[idx].subtitle}\""))
                                            }
                                            1
                                        }
                                )
                        )
                )
                .then(
                    Commands.literal("puntos")
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            val active = getPlayerActiveRoute(player) ?: run {
                                player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
                                return@executes 0
                            }
                            player.sendSystemMessage(Component.literal("§6══════════ PUNTOS DE '${active.name}' (${active.keyframes.size}) ══════════"))
                            if (active.keyframes.isEmpty()) {
                                player.sendSystemMessage(Component.literal("§7(Sin puntos guardados aún. Usa /director add)"))
                            } else {
                                for ((i, k) in active.keyframes.withIndex()) {
                                    val id = i + 1
                                    val effectsList = mutableListOf<String>()
                                    if (k.fadeColor != null) effectsList += "Fade:${k.fadeColor}"
                                    if (k.animationTrigger != null) effectsList += "Anim:${k.animationTrigger}"
                                    if (k.isSeasonJump) effectsList += "Salto"
                                    if (k.shaderEffect != null) effectsList += "Shader:${k.shaderEffect}"
                                    val effStr = if (effectsList.isEmpty()) "§8(sin efectos)" else "§b[${effectsList.joinToString(", ")}]"

                                    val line = Component.literal("§e#$id §7(${String.format("%.1f", k.durationSeconds)}s) $effStr  ")
                                        .append(btn("[TP]", "/cinematicas director tp $id", "Teletransportarse a este punto", ChatFormatting.GREEN))
                                        .append(" ")
                                        .append(btn("[Mover]", "/cinematicas director mover $id", "Mover este punto a tu posición actual", ChatFormatting.YELLOW))
                                        .append(" ")
                                        .append(btn("[Fade]", "/cinematicas director transicion $id ", "Asignar fade de color", ChatFormatting.GOLD, suggest = true))
                                        .append(" ")
                                        .append(btn("[Anim]", "/cinematicas director animacion $id ", "Vincular animación", ChatFormatting.LIGHT_PURPLE, suggest = true))
                                        .append(" ")
                                        .append(btn("[Borrar]", "/cinematicas director remover $id", "Eliminar este punto", ChatFormatting.RED))
                                    player.sendSystemMessage(line)
                                }
                            }
                            player.sendSystemMessage(Component.literal("§6═════════════════════════════════════════════════════"))
                            1
                        }
                )
                .then(
                    Commands.literal("probar")
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            val active = getPlayerActiveRoute(player) ?: run {
                                ctx.source.sendFailure(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
                                return@executes 0
                            }
                            CinematicServerManager.playCinematic(ctx.source.server, active, player)
                            1
                        }
                )
                .then(
                    Commands.literal("salir")
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            val routeName = activeEditingRoutes.remove(player.uuid)
                            CinematicPackets.stop(player, "editor_exit")
                            if (routeName != null) {
                                player.sendSystemMessage(Component.literal("§eModo director cerrado. Edición de '$routeName' guardada."))
                            } else {
                                player.sendSystemMessage(Component.literal("§eModo director cerrado."))
                            }
                            1
                        }
                )

        val mainNode = dispatcher.register(rootCinematica.then(directorNode))
        dispatcher.register(Commands.literal("cinematica").requires { it.hasPermission(2) }.redirect(mainNode))
    }

    private fun addKeyframe(ctx: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, duration: Double): Int {
        val player = ctx.source.playerOrException
        val active = getPlayerActiveRoute(player) ?: run {
            ctx.source.sendFailure(Component.literal("§cNo estás editando ninguna ruta. Inicia edición con /director edit <nombre>"))
            return 0
        }

        val nextId = active.keyframes.size + 1
        val kf = Keyframe(
            id = nextId,
            x = player.x,
            y = player.y + player.eyeHeight,
            z = player.z,
            yaw = player.yRot,
            pitch = player.xRot,
            durationSeconds = duration
        )
        active.keyframes.add(kf)
        CinematicRouteStorage.saveRoute(active)

        // Sincronizar en vivo al cliente del director para actualizar la línea visual en 3D
        CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)

        val msg = Component.literal("§a§lPunto Clave #$nextId añadido §7(${String.format("%.1f", duration)}s)\n  ")
            .append(btn("[Poner Fade]", "/cinematicas director transicion $nextId ", "Asigna un color de Fade (ej. black, white, gold, #ff0000)", ChatFormatting.GOLD, suggest = true))
            .append("  ")
            .append(btn("[Animación]", "/cinematicas director animacion $nextId ", "Vincular una animación 2D en pantalla", ChatFormatting.LIGHT_PURPLE, suggest = true))
            .append("  ")
            .append(btn("[Shader]", "/cinematicas director shader $nextId ", "Vincular un shader especial", ChatFormatting.AQUA, suggest = true))
            .append("  ")
            .append(btn("[Tiempo]", "/cinematicas director tiempo $nextId ", "Modificar duración en segundos", ChatFormatting.YELLOW, suggest = true))
            .append("  ")
            .append(btn("[Borrar]", "/cinematicas director remover $nextId", "Eliminar este punto", ChatFormatting.RED))

        player.sendSystemMessage(msg)
        return 1
    }

    private fun insertKeyframe(ctx: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, duration: Double): Int {
        val player = ctx.source.playerOrException
        val active = getPlayerActiveRoute(player) ?: run {
            ctx.source.sendFailure(Component.literal("§cNo estás editando ninguna ruta. Inicia edición con /director edit <nombre>"))
            return 0
        }
        val targetIdx = (IntegerArgumentType.getInteger(ctx, "indice") - 1).coerceIn(0, active.keyframes.size)
        val kf = Keyframe(
            id = targetIdx + 1,
            x = player.x,
            y = player.y + player.eyeHeight,
            z = player.z,
            yaw = player.yRot,
            pitch = player.xRot,
            durationSeconds = duration
        )
        active.keyframes.add(targetIdx, kf)
        active.keyframes.forEachIndexed { i, k -> k.id = i + 1 }
        CinematicRouteStorage.saveRoute(active)
        CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)

        player.sendSystemMessage(Component.literal("§aPunto Clave insertado en la posición #${targetIdx + 1}. Total puntos: ${active.keyframes.size}."))
        return 1
    }

    private fun moveKeyframeIndex(player: ServerPlayer, active: CinematicRoute, idx: Int) {
        val k = active.keyframes[idx]
        k.x = player.x
        k.y = player.y + player.eyeHeight
        k.z = player.z
        k.yaw = player.yRot
        k.pitch = player.xRot
        CinematicRouteStorage.saveRoute(active)
        CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
        player.sendSystemMessage(Component.literal("§aPunto #${idx + 1} de '${active.name}' trasladado a tu posición y vista actual."))
    }

    private fun setFade(ctx: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, duration: Double): Int {
        val player = ctx.source.playerOrException
        val active = getPlayerActiveRoute(player) ?: run {
            player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
            return 0
        }
        val idx = IntegerArgumentType.getInteger(ctx, "indice") - 1
        val color = StringArgumentType.getString(ctx, "color")
        if (idx in active.keyframes.indices) {
            val k = active.keyframes[idx]
            if (color.lowercase() == "none") {
                k.fadeColor = null
                player.sendSystemMessage(Component.literal("§eFade eliminado del punto #${idx + 1}."))
            } else {
                k.fadeColor = color
                k.fadeDurationSeconds = duration
                val msg = Component.literal("§aFade de color '§f$color§a' (${String.format("%.1f", duration)}s) asignado al punto #${idx + 1}.  ")
                    .append(btn("[Probar Fade en pantalla]", "/cinematicas director probar_transicion $color $duration", "Ver cómo se ve este fade en tu pantalla", ChatFormatting.GOLD))
                player.sendSystemMessage(msg)
            }
            CinematicRouteStorage.saveRoute(active)
            CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
        } else {
            player.sendSystemMessage(Component.literal("§cÍndice fuera de rango (1..${active.keyframes.size})."))
        }
        return 1
    }

    private fun toggleJump(ctx: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, explicit: Boolean?): Int {
        val player = ctx.source.playerOrException
        val active = getPlayerActiveRoute(player) ?: run {
            player.sendSystemMessage(Component.literal("§cNo estás editando ninguna ruta. Usa /director edit <nombre>"))
            return 0
        }
        val idx = IntegerArgumentType.getInteger(ctx, "indice") - 1
        if (idx in active.keyframes.indices) {
            val k = active.keyframes[idx]
            k.isSeasonJump = explicit ?: (!k.isSeasonJump)
            CinematicRouteStorage.saveRoute(active)
            CinematicPackets.play(player, active.name, CinematicRouteStorage.toJson(active), -1L)
            val state = if (k.isSeasonJump) "§aACTIVADO" else "§cDESACTIVADO"
            player.sendSystemMessage(Component.literal("§aSalto de temporada $state §aen el punto #${idx + 1}."))
        } else {
            player.sendSystemMessage(Component.literal("§cÍndice fuera de rango (1..${active.keyframes.size})."))
        }
        return 1
    }
}
