package com.desgraciamon.mirror

import com.google.gson.GsonBuilder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

data class ReturnPoint(
    var dimension: String = "minecraft:overworld",
    var x: Double = 0.0,
    var y: Double = 64.0,
    var z: Double = 0.0,
    var yaw: Float = 0f,
    var pitch: Float = 0f,
)

data class PlotData(
    /** uuid del dueño -> índice base de sus zonas en la dimensión */
    var plots: MutableMap<String, Int> = mutableMapOf(),
    /** uuid del dueño -> último tema visitado (0..14) */
    var plotThemes: MutableMap<String, Int> = mutableMapOf(),
    /** índices cuyo terreno ya se construyó */
    var generated: MutableSet<Int> = mutableSetOf(),
    var nextIndex: Int = 0,
    /** uuid del jugador -> dónde estaba antes de entrar al Mundo Espejo */
    var returnPoints: MutableMap<String, ReturnPoint> = mutableMapOf(),
    var generatorVersion: Int = 0
)

/**
 * Cada dueño tiene una zona propia en la dimensión, separada [SPACING] bloques de las demás
 * para que nunca se vean entre sí ni compartan chunks cargados.
 */
object PlotManager {
    const val SPACING = 2048

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-mirror")
    private var data = PlotData()
    private var file: File? = null

    const val CURRENT_GENERATOR_VERSION = 2

    fun load(server: MinecraftServer) {
        val dir = server.getWorldPath(LevelResource.ROOT).resolve("desgraciamon4_mirror").toFile()
        dir.mkdirs()
        val target = File(dir, "plots.json")
        file = target
        data = runCatching {
            if (target.exists()) target.reader(Charsets.UTF_8).use { gson.fromJson(it, PlotData::class.java) } ?: PlotData() else PlotData()
        }.onFailure { logger.error("No se pudo leer ${target.path}", it) }.getOrDefault(PlotData())

        if (data.generatorVersion < CURRENT_GENERATOR_VERSION) {
            logger.info("Migrando Mundo Espejo a v$CURRENT_GENERATOR_VERSION (120x120 plano con montaña y lago). Limpiando parcelas para regeneración automática...")
            data.generated.clear()
            data.generatorVersion = CURRENT_GENERATOR_VERSION
            save()
        }
    }

    fun save() {
        val target = file ?: return
        runCatching { target.writeText(gson.toJson(data), Charsets.UTF_8) }
            .onFailure { logger.error("No se pudo guardar ${target.path}", it) }
    }

    fun indexOf(owner: UUID): Int = currentPlotFor(owner)

    fun nextThemeIndex(owner: UUID): Int = currentPlotFor(owner)

    fun currentPlotFor(owner: UUID): Int {
        val baseIndex = data.plots.getOrPut(owner.toString()) {
            val idx = data.nextIndex
            data.nextIndex += 1
            save()
            idx
        }
        return baseIndex
    }

    /** Asigna una zona nueva (terreno recién generado) al dueño. */
    fun reassign(owner: UUID): Int {
        val plot = currentPlotFor(owner)
        data.generated.remove(plot)
        save()
        return plot
    }

    fun ownerOf(index: Int): UUID? =
        data.plots.entries.firstOrNull { it.value == index || (it.value <= index && index < it.value + 15) }?.key?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun isGenerated(index: Int) = index in data.generated

    fun markGenerated(index: Int) {
        data.generated += index
        save()
    }

    fun centerX(index: Int): Int = index * SPACING
    fun centerZ(@Suppress("UNUSED_PARAMETER") index: Int): Int = 0

    /** Zona en la que está una posición de la dimensión espejo. */
    fun plotAt(x: Double): Int = (x / SPACING).roundToInt()

    fun saveReturnPoint(player: ServerPlayer) {
        val level = player.serverLevel()
        if (MirrorRules.isMirror(level)) return
        data.returnPoints[player.uuid.toString()] = ReturnPoint(
            level.dimension().location().toString(), player.x, player.y, player.z, player.yRot, player.xRot
        )
        save()
    }

    /** Devuelve al jugador a donde estaba antes de entrar (o al spawn del mundo si no hay registro). */
    fun sendBack(player: ServerPlayer) {
        val server = player.server
        val point = data.returnPoints.remove(player.uuid.toString())
        save()
        val level: ServerLevel = point?.let {
            ResourceLocation.tryParse(it.dimension)?.let { id -> server.getLevel(ResourceKey.create(Registries.DIMENSION, id)) }
        } ?: server.overworld()
        if (point != null && !MirrorRules.isMirror(level)) {
            player.teleportTo(level, point.x, point.y, point.z, point.yaw, point.pitch)
        } else {
            val spawn = server.overworld().sharedSpawnPos
            player.teleportTo(server.overworld(), spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, player.yRot, player.xRot)
        }
    }
}
