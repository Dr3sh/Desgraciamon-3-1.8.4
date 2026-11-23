package com.desgraciamon.hud

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.io.File

object CustomHudConfig {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File = FabricLoader.getInstance().configDir.resolve("desgraciamon_hud.json").toFile()

    data class Data(
        var enabled: Boolean = true
    )

    var data: Data = Data()
        private set

    init {
        load()
    }

    fun isEnabled(): Boolean = data.enabled

    fun setEnabled(value: Boolean) {
        data.enabled = value
        save()
    }

    fun toggle(): Boolean {
        data.enabled = !data.enabled
        save()
        return data.enabled
    }

    fun load() {
        if (!configFile.exists()) {
            save()
            return
        }
        try {
            configFile.bufferedReader().use { reader ->
                data = gson.fromJson(reader, Data::class.java) ?: Data()
            }
        } catch (_: Exception) {
            data = Data()
        }
    }

    fun save() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.bufferedWriter().use { writer ->
                gson.toJson(data, writer)
            }
        } catch (_: Exception) {}
    }
}

