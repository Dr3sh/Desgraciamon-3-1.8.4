package com.desgraciamon.announcements

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class BulletinSourceSettings(
    val mode: String = "local",
    val githubUrl: String = "",
    val githubToken: String = ""
) {
    companion object {
        fun load(path: Path): BulletinSourceSettings {
            if (Files.notExists(path)) {
                Files.createDirectories(path.parent)
                Files.writeString(
                    path,
                    """
                    # local reads config/desgraciamon4-announcements/announcements.json
                    # github accepts a GitHub Contents API URL or a raw.githubusercontent.com URL
                    # private repositories require a personal access token with Contents: Read access
                    source=local
                    githubUrl=
                    githubToken=
                    """.trimIndent() + "\n"
                )
            }
            val properties = Properties().apply { Files.newInputStream(path).use(::load) }
            val mode = properties.getProperty("source", "local").trim().lowercase()
            return BulletinSourceSettings(
                mode = if (mode == "github") "github" else "local",
                githubUrl = properties.getProperty("githubUrl", "").trim(),
                githubToken = properties.getProperty("githubToken", "").trim()
            )
        }
    }
}