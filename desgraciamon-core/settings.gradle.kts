pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}

rootProject.name = "desgraciamon-core"

apply(from = "../gradle/jar-output.init.gradle")

