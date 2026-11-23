plugins {
    id("dev.architectury.loom") version "1.11-SNAPSHOT"
}

group = "net.desgraciamon"
version = "1.0.1"

base {
    archivesName.set("desgraciamon-guilds")
}

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://api.modrinth.com/maven")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.17.2")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.116.6+1.21.1")
    modCompileOnly("net.fabricmc:fabric-language-kotlin:1.13.6+kotlin.2.2.20")
    modCompileOnly("maven.modrinth:cobblemon:PdOzYHKU")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
