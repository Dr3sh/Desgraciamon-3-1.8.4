pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/")
		gradlePluginPortal()
		mavenCentral()
	}
}

rootProject.name = "desgraciamon4-discord-bridge"

apply(from = "../gradle/jar-output.init.gradle")