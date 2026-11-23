package com.desgraciamon.discordbridge

internal object TextCatalogReload {
    data class Result(val reloaded: Int)

    private val catalogClasses = listOf(
        "com.desgraciamon.discordbridge.TextCatalog",
        "com.addoncobblemon.legendarycooldown.TextCatalog",
        "com.desgraciamon.catchassistant.TextCatalog",
        "com.desgraciamon.chatassistant.TextCatalog",
        "com.desgraciamon.announcements.TextCatalog",
        "com.addoncobblemon.legendarybestiary.TextCatalog",
        "com.desgraciamon.showdownarena.TextCatalog"
    )

    fun reloadAll(): Result {
        var reloaded = 0
        catalogClasses.forEach { className ->
            val catalogClass = runCatching { Class.forName(className) }.getOrNull() ?: return@forEach
            runCatching {
                val instance = catalogClass.getField("INSTANCE").get(null)
                catalogClass.getMethod("reload").invoke(instance) as Boolean
            }.onSuccess { if (it) reloaded++ }
                .onFailure { DiscordBridgeMod.logger.warn("Could not reload text catalog $className", it) }
        }
        return Result(reloaded)
    }
}
