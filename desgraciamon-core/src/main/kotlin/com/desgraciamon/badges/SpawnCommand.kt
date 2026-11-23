package com.desgraciamon.badges

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

internal object SpawnCommand {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-spawn")

    data class SpawnPoint(
        val dimension: String = "minecraft:overworld",
        val x: Double = 0.0,
        val y: Double = 64.0,
        val z: Double = 0.0,
        val yaw: Float = 0.0f,
        val pitch: Float = 0.0f
    )

    private val defaultTexts: Map<String, String> = linkedMapOf(
        "spawn.teleported" to "<color=#55FF55>Has sido teletransportado al </color><prism=#FFD700,#38BDF8><bold>Spawn</bold></prism><color=#55FF55>.</color>",
        "spawn.set" to "<color=#55FF55>Spawn del servidor guardado en </color><color=#FFFFFF>{coords}</color> <color=#9CA3AF>({dimension})</color><color=#55FF55>.</color>",
        "spawn.invalid_dimension" to "<color=#EF4444>No se pudo encontrar la dimensión del spawn ({dimension}).</color>"
    )
    private var texts: Map<String, String> = defaultTexts

    private fun loadTexts() {
        val dir = File("config/desgraciamon4_spawn/.texts")
        dir.mkdirs()
        runCatching { Files.setAttribute(dir.toPath(), "dos:hidden", true) }
        val file = File(dir, "texts.json")
        runCatching {
            if (!file.exists()) file.writeText(gson.toJson(defaultTexts), Charsets.UTF_8)
            val type = object : TypeToken<Map<String, String>>() {}.type
            val overrides: Map<String, String> = file.reader(Charsets.UTF_8).use { gson.fromJson(it, type) ?: emptyMap() }
            texts = defaultTexts + overrides
            file.writeText(gson.toJson(texts), Charsets.UTF_8)
        }.onFailure {
            texts = defaultTexts
            logger.error("Could not load spawn texts from ${file.path}", it)
        }
    }

    private fun text(key: String, vararg values: Pair<String, Any?>): MutableComponent {
        val template = texts[key] ?: defaultTexts[key] ?: key
        return RichText.parse(template, values.associate { it.first to it.second })
    }

    private fun dataFile(server: MinecraftServer): File {
        val dir = server.getWorldPath(LevelResource.ROOT).resolve("data").toFile()
        dir.mkdirs()
        return File(dir, "desgraciamon_spawn.json")
    }

    private fun loadSpawn(server: MinecraftServer): SpawnPoint? {
        val file = dataFile(server)
        if (!file.exists()) return null
        return runCatching {
            file.reader(Charsets.UTF_8).use { gson.fromJson(it, SpawnPoint::class.java) }
        }.getOrNull()
    }

    private fun saveSpawn(server: MinecraftServer, point: SpawnPoint) {
        runCatching {
            dataFile(server).writeText(gson.toJson(point), Charsets.UTF_8)
        }.onFailure {
            logger.error("Could not save server spawn", it)
        }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        loadTexts()

        dispatcher.register(
            Commands.literal("spawn")
                .executes { ctx -> teleportToSpawn(ctx.source.playerOrException) }
                .then(
                    Commands.literal("set")
                        .requires { it.hasPermission(2) }
                        .executes { ctx -> setWorldSpawn(ctx.source.playerOrException) }
                )
        )

        dispatcher.register(
            Commands.literal("setspawn")
                .requires { it.hasPermission(2) }
                .executes { ctx -> setWorldSpawn(ctx.source.playerOrException) }
        )
    }

    private fun setWorldSpawn(player: ServerPlayer): Int {
        val dim = player.serverLevel().dimension().location().toString()
        val point = SpawnPoint(
            dimension = dim,
            x = player.x,
            y = player.y,
            z = player.z,
            yaw = player.yRot,
            pitch = player.xRot
        )
        saveSpawn(player.server, point)
        val coords = String.format("%.1f, %.1f, %.1f", point.x, point.y, point.z)
        player.sendSystemMessage(text("spawn.set", "coords" to coords, "dimension" to dim))
        return 1
    }

    private fun teleportToSpawn(player: ServerPlayer): Int {
        val server = player.server
        val saved = loadSpawn(server)
        if (saved == null) {
            val overworld = server.overworld()
            val pos = overworld.sharedSpawnPos
            player.teleportTo(overworld, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, overworld.sharedSpawnAngle, 0.0f)
            player.sendSystemMessage(text("spawn.teleported"))
            return 1
        }

        val loc = ResourceLocation.tryParse(saved.dimension)
        val targetLevel = loc?.let { server.getLevel(ResourceKey.create(Registries.DIMENSION, it)) }
        if (targetLevel == null) {
            player.sendSystemMessage(text("spawn.invalid_dimension", "dimension" to saved.dimension))
            return 0
        }

        player.teleportTo(targetLevel, saved.x, saved.y, saved.z, saved.yaw, saved.pitch)
        player.sendSystemMessage(text("spawn.teleported"))
        return 1
    }
}
