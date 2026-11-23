package com.desgraciamon.announcements

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class MissionAnimationSettings(
    val enabled: Boolean = false,
    val newMissionAnimation: String = "nuevamision",
    val completedAnimation: String = "misioncompletada"
) {
    companion object {
        @Volatile
        var current = MissionAnimationSettings()
            private set

        fun load(path: Path) {
            runCatching {
                if (Files.notExists(path)) {
                    Files.createDirectories(path.parent)
                    Files.writeString(
                        path,
                        """
                        # Mission screen animations are disabled by default.
                        # Set enabled=true to use folders in config/desgraciamon4_screen_animations/animations.
                        enabled=false
                        newMissionAnimation=nuevamision
                        completedAnimation=misioncompletada
                        """.trimIndent() + "\n"
                    )
                }
                val properties = Properties().apply { Files.newInputStream(path).use(::load) }
                val enabled = properties.getProperty("enabled", "false").trim().toBooleanStrictOrNull() ?: false
                current = MissionAnimationSettings(
                    enabled = enabled,
                    newMissionAnimation = properties.getProperty("newMissionAnimation", "nuevamision").trim().takeIf(::validAnimationName) ?: "nuevamision",
                    completedAnimation = properties.getProperty("completedAnimation", "misioncompletada").trim().takeIf(::validAnimationName) ?: "misioncompletada"
                )
            }.onFailure {
                AnnouncementsMod.logger.error("Could not load mission animation settings from $path", it)
                current = MissionAnimationSettings()
            }
        }

        private fun validAnimationName(name: String) = name.matches(Regex("[a-z0-9_-]{1,32}"))
    }
}