package com.desgraciamon.mirror

import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory
import java.io.File

data class MirrorClientConfig(
    var motionBlur: Boolean = true
)

object MirrorClientSettings {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-mirror-client")
    private val file = File("config/desgraciamon4_mirror/client.json")

    @JvmStatic
    var motionBlur: Boolean = true
        private set

    init {
        load()
    }

    @JvmStatic
    fun isMotionBlurEnabled(): Boolean = motionBlur

    @JvmStatic
    fun toggleMotionBlur(): Boolean {
        motionBlur = !motionBlur
        save()
        return motionBlur
    }

    @JvmStatic
    fun setMotionBlur(value: Boolean) {
        motionBlur = value
        save()
    }

    fun load() {
        runCatching {
            if (file.exists()) {
                file.reader(Charsets.UTF_8).use {
                    val cfg = gson.fromJson(it, MirrorClientConfig::class.java)
                    if (cfg != null) {
                        motionBlur = cfg.motionBlur
                    }
                }
            } else {
                save()
            }
        }.onFailure {
            logger.warn("No se pudo cargar la configuración de cliente del Mundo Espejo, usando valores por defecto", it)
        }
    }

    fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writer(Charsets.UTF_8).use {
                gson.toJson(MirrorClientConfig(motionBlur), it)
            }
        }.onFailure {
            logger.warn("No se pudo guardar la configuración de cliente del Mundo Espejo", it)
        }
    }
}
