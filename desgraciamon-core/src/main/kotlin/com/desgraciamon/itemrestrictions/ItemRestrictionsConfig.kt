package com.desgraciamon.itemrestrictions

import com.google.gson.GsonBuilder
import java.io.File
import java.nio.charset.StandardCharsets

data class ItemRestrictionsConfig(
    val restrictedItems: LinkedHashSet<String> = linkedSetOf("minecraft:ender_pearl"),
    val restrictedTags: LinkedHashSet<String> = linkedSetOf(),
    val playerRestrictions: LinkedHashMap<String, LinkedHashSet<String>> = linkedMapOf(),
    val tagRestrictions: LinkedHashMap<String, LinkedHashSet<String>> = linkedMapOf(),
    val bypassPermissionLevel: Int = 2,
    val blockedMessage: String = "<color=#FF5555>El uso de este objeto ha sido deshabilitado.</color>",
    val showActionbarNotice: Boolean = true,
    val cooldownNoticeSeconds: Int = 2
) {
    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        private val file = File("config/desgraciamon4_item_restrictions.json")
        var current: ItemRestrictionsConfig = ItemRestrictionsConfig()

        private fun normalizeMap(raw: Map<String, Collection<String>>?): LinkedHashMap<String, LinkedHashSet<String>> {
            val out = LinkedHashMap<String, LinkedHashSet<String>>()
            if (raw == null) return out
            for ((k, v) in raw) {
                val cleanKey = k.lowercase().trim()
                if (cleanKey.isBlank()) continue
                val cleanSet = LinkedHashSet(v.map { it.lowercase().trim() }.filter { it.isNotBlank() })
                if (cleanSet.isNotEmpty()) out[cleanKey] = cleanSet
            }
            return out
        }

        fun load(): ItemRestrictionsConfig {
            return runCatching {
                file.parentFile?.mkdirs()
                if (!file.exists()) {
                    val def = ItemRestrictionsConfig()
                    file.writeText(gson.toJson(def), StandardCharsets.UTF_8)
                    current = def
                    return def
                }
                val loaded = file.reader(StandardCharsets.UTF_8).use {
                    gson.fromJson(it, ItemRestrictionsConfig::class.java)
                } ?: ItemRestrictionsConfig()
                current = loaded.copy(
                    restrictedItems = LinkedHashSet((loaded.restrictedItems as Collection<String>?).orEmpty().map { it.lowercase().trim() }),
                    restrictedTags = LinkedHashSet((loaded.restrictedTags as Collection<String>?).orEmpty().map { it.lowercase().trim() }),
                    playerRestrictions = normalizeMap(loaded.playerRestrictions as Map<String, Collection<String>>?),
                    tagRestrictions = normalizeMap(loaded.tagRestrictions as Map<String, Collection<String>>?)
                )
                current
            }.getOrElse {
                current = ItemRestrictionsConfig()
                current
            }
        }

        fun save() {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(gson.toJson(current), StandardCharsets.UTF_8)
            }
        }
    }
}
