pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}

rootProject.name = "desgraciamon4-visuals"

apply(from = "../gradle/jar-output.init.gradle")

