package com.desgraciamon.training

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.pokemon.experience.CandyExperienceSource
import com.google.gson.GsonBuilder
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

data class XpBaseConfig(
    var bonusPercent: Double = 0.0
)

object XpMultiplierManager {
    private val logger = LoggerFactory.getLogger("desgraciamon4-xpbase")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var config = XpBaseConfig()

    @Volatile
    private var activeServer: MinecraftServer? = null

    @Volatile
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true

        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            activeServer = server
            load(server)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            save(server)
            activeServer = null
        }

        // Runs at HIGHEST priority so guild synergy and catch-up multipliers (NORMAL) use this value as their base.
        // Affects all XP sources (battles, capture XP, sidemods, commands) EXCEPT candies (Rare Candy / Exp Candy).
        CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.subscribe(Priority.HIGHEST) { event ->
            if (event.source is CandyExperienceSource) return@subscribe
            val pct = config.bonusPercent
            if (pct == 0.0) return@subscribe
            val base = event.experience
            if (base <= 0) return@subscribe

            val factor = (1.0 + pct / 100.0).coerceAtLeast(0.0)
            val scaled = (base * factor).roundToInt().coerceAtLeast(1)
            event.experience = scaled
        }
    }

    private fun configFile(server: MinecraftServer): File {
        val dir = server.getWorldPath(LevelResource.ROOT).resolve("data").toFile()
        dir.mkdirs()
        return File(dir, "desgraciamon_xp_base.json")
    }

    fun load(server: MinecraftServer) {
        runCatching {
            val file = configFile(server)
            if (file.exists()) {
                file.reader(StandardCharsets.UTF_8).use { reader ->
                    config = gson.fromJson(reader, XpBaseConfig::class.java) ?: XpBaseConfig()
                }
            } else {
                config = XpBaseConfig()
                save(server)
            }
        }.onFailure {
            logger.warn("No se pudo cargar desgraciamon_xp_base.json, usando valores por defecto.", it)
            config = XpBaseConfig()
        }
    }

    fun save(server: MinecraftServer? = activeServer) {
        val s = server ?: return
        runCatching {
            val file = configFile(s)
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(config), StandardCharsets.UTF_8)
        }.onFailure {
            logger.warn("No se pudo guardar desgraciamon_xp_base.json.", it)
        }
    }

    fun getBonusPercent(): Double = config.bonusPercent

    fun getMultiplier(): Double = (1.0 + config.bonusPercent / 100.0).coerceAtLeast(0.0)

    fun setBonusPercent(percent: Double, server: MinecraftServer? = activeServer) {
        config.bonusPercent = percent.coerceIn(-90.0, 10000.0)
        save(server)
    }

    fun buildAdminNode(): com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("xpbase")
            .requires { com.desgraciamon.badges.AdminAuth.isAdminSource(it) }
            .executes { ctx ->
                val pct = getBonusPercent()
                val mult = String.format(Locale.ROOT, "%.2f", getMultiplier())
                val sign = if (pct >= 0) "+" else ""
                ctx.source.sendSuccess(
                    { Component.literal("§e[XP Base] Bono actual: §f$sign$pct% §7(multiplicador base x$mult, excluye caramelos)") },
                    false
                )
                1
            }
            .then(
                Commands.literal("default")
                    .executes { ctx ->
                        setBonusPercent(0.0, ctx.source.server)
                        ctx.source.sendSuccess(
                            { Component.literal("§a[XP Base] Multiplicador base restablecido al valor por defecto §f(+0.0% / x1.00)§a.") },
                            true
                        )
                        1
                    }
            )
            .then(
                Commands.argument("porcentaje", DoubleArgumentType.doubleArg(-90.0, 10000.0))
                    .executes { ctx ->
                        val pct = DoubleArgumentType.getDouble(ctx, "porcentaje")
                        setBonusPercent(pct, ctx.source.server)
                        val mult = String.format(Locale.ROOT, "%.2f", getMultiplier())
                        val sign = if (pct >= 0) "+" else ""
                        ctx.source.sendSuccess(
                            { Component.literal("§a[XP Base] Bono base establecido en §f$sign$pct% §7(x$mult sobre la experiencia original, sin afectar caramelos).") },
                            true
                        )
                        1
                    }
            )
    }
}
