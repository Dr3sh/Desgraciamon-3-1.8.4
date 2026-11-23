package com.desgraciamon.cinematics

import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object CinematicRouteStorage {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-cinematics")
    private val routesDir = File("config/desgraciamon4_cinematics/routes")

    private val cachedRoutes = ConcurrentHashMap<String, CinematicRoute>()

    fun init() {
        routesDir.mkdirs()
        seedExampleRouteIfEmpty()
        reloadAll()
    }

    fun reloadAll() {
        cachedRoutes.clear()
        routesDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            runCatching {
                file.reader(Charsets.UTF_8).use { reader ->
                    val route = gson.fromJson(reader, CinematicRoute::class.java)
                    if (route != null) {
                        cachedRoutes[route.name.lowercase()] = route
                    }
                }
            }.onFailure {
                logger.error("Error al cargar ruta cinemática desde ${file.name}", it)
            }
        }
        logger.info("Cargadas ${cachedRoutes.size} rutas cinemáticas.")
    }

    fun getRoute(name: String): CinematicRoute? = cachedRoutes[name.lowercase()]

    fun getAllRoutes(): List<CinematicRoute> = cachedRoutes.values.toList()

    fun saveRoute(route: CinematicRoute) {
        cachedRoutes[route.name.lowercase()] = route
        runCatching {
            routesDir.mkdirs()
            val file = File(routesDir, "${route.name}.json")
            file.writer(Charsets.UTF_8).use { writer ->
                gson.toJson(route, writer)
            }
        }.onFailure {
            logger.error("Error al guardar ruta cinemática ${route.name}", it)
        }
    }

    fun deleteRoute(name: String): Boolean {
        cachedRoutes.remove(name.lowercase())
        val file = File(routesDir, "$name.json")
        return file.delete()
    }

    fun toJson(route: CinematicRoute): String = gson.toJson(route)

    fun fromJson(json: String): CinematicRoute? = runCatching {
        gson.fromJson(json, CinematicRoute::class.java)
    }.getOrNull()

    private fun seedExampleRouteIfEmpty() {
        val existing = routesDir.listFiles { f -> f.extension == "json" }
        if (existing.isNullOrEmpty()) {
            val example = CinematicRoute(
                name = "temporadas_recuerdo",
                description = "Cinemática histórica oficial: De la Temporada 1 a la Temporada 3",
                letterbox = true,
                hideHud = true,
                freezePlayers = true,
                loop = false,
                keyframes = mutableListOf(
                    Keyframe(
                        id = 1,
                        x = 0.0, y = 75.0, z = 0.0,
                        yaw = 0f, pitch = 15f,
                        durationSeconds = 5.0,
                        easing = EasingMode.EASE_IN,
                        subtitle = "§e§lReunión de Aventureros §7- Inicio de la Transmisión",
                        shaderEffect = "color_fade",
                        shaderDurationSeconds = 2.0
                    ),
                    Keyframe(
                        id = 2,
                        x = 45.0, y = 110.0, z = -35.0,
                        yaw = -45f, pitch = 25f,
                        durationSeconds = 6.0,
                        easing = EasingMode.EASE_IN_OUT,
                        subtitle = "§bAscenso a los Cielos §7- Mirando hacia el horizonte...",
                        animationTrigger = "logo_desgraciamon"
                    ),
                    Keyframe(
                        id = 3,
                        x = 120.0, y = 90.0, z = -150.0,
                        yaw = -90f, pitch = 5f,
                        durationSeconds = 7.0,
                        easing = EasingMode.EASE_IN_OUT,
                        subtitle = "§6§l[Temporada 1] §eEl Inicio de la Leyenda",
                        shaderEffect = "nostalgia",
                        shaderDurationSeconds = 6.0,
                        isSeasonJump = true
                    ),
                    Keyframe(
                        id = 4,
                        x = 240.0, y = 85.0, z = -220.0,
                        yaw = -135f, pitch = 10f,
                        durationSeconds = 8.0,
                        easing = EasingMode.EASE_IN_OUT,
                        subtitle = "§d§l[Temporada 2] §dLas Grandes Ciudadelas y Torneos",
                        shaderEffect = "swirl",
                        shaderDurationSeconds = 3.0,
                        isSeasonJump = true
                    ),
                    Keyframe(
                        id = 5,
                        x = 0.0, y = 72.0, z = 0.0,
                        yaw = 0f, pitch = 0f,
                        durationSeconds = 5.0,
                        easing = EasingMode.EASE_OUT,
                        subtitle = "§a§l[Temporada 3] §f¡El Renacer de Desgraciamon comienza ahora!",
                        shaderEffect = "flash_gold",
                        shaderDurationSeconds = 2.5
                    )
                )
            )
            saveRoute(example)
        }
    }
}

