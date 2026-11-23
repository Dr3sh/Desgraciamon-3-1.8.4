pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.minecraftforge.net/")
        gradlePluginPortal()
    }
}

rootProject.name = "desgraciamon-guilds"

apply(from = "../gradle/jar-output.init.gradle")
