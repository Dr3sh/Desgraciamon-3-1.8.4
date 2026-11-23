package com.desgraciamon.economy

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object MarketServerPreferences {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-economy")
        private var file = File("config/desgraciamon4_economy/alert_preferences.json")

    fun initForServer(server: net.minecraft.server.MinecraftServer) {
        val worldFolder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
        val dir = worldFolder.resolve("data/desgraciamon4_economy").toFile()
        dir.mkdirs()
        file = File(dir, "alert_preferences.json")
        load()
    }
    private val disabledAlerts = ConcurrentHashMap.newKeySet<UUID>()

    fun load() {
        if (!file.exists()) return
        runCatching {
            file.reader().use {
                val type = object : TypeToken<Set<String>>() {}.type
                val set: Set<String> = gson.fromJson(it, type) ?: emptySet()
                disabledAlerts.clear()
                for (s in set) {
                    runCatching { disabledAlerts.add(UUID.fromString(s)) }
                }
            }
        }.onFailure {
            logger.warn("No se pudieron cargar las preferencias de alertas: ${it.message}")
        }
    }

    fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val list = disabledAlerts.map { it.toString() }
            file.writeText(gson.toJson(list))
        }.onFailure {
            logger.warn("No se pudieron guardar las preferencias de alertas: ${it.message}")
        }
    }

    fun isAlertsEnabled(uuid: UUID): Boolean {
        return !disabledAlerts.contains(uuid)
    }

    fun setAlertsEnabled(uuid: UUID, enabled: Boolean) {
        if (enabled) {
            disabledAlerts.remove(uuid)
        } else {
            disabledAlerts.add(uuid)
        }
        save()
    }
}

object MarketClientConfig {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file = File("config/desgraciamon4_economy/client.json")
    private var alertsEnabled: Boolean = true

    init {
        load()
    }

    private data class ClientData(val alertsEnabled: Boolean = true)

    fun load() {
        if (!file.exists()) return
        runCatching {
            file.reader().use {
                val data = gson.fromJson(it, ClientData::class.java)
                if (data != null) {
                    alertsEnabled = data.alertsEnabled
                }
            }
        }
    }

    fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(ClientData(alertsEnabled)))
        }
    }

    @JvmStatic
    fun isAlertsEnabled(): Boolean = alertsEnabled

    @JvmStatic
    fun toggleAlerts(): Boolean {
        alertsEnabled = !alertsEnabled
        save()
        runCatching {
            if (ClientPlayNetworking.canSend(MarketAlertTogglePayload.TYPE)) {
                ClientPlayNetworking.send(MarketAlertTogglePayload(alertsEnabled))
            }
        }
        return alertsEnabled
    }
}

