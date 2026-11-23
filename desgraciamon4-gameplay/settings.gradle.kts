pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}

rootProject.name = "desgraciamon4-gameplay"

apply(from = "../gradle/jar-output.init.gradle")

