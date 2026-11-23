plugins {
    id("fabric-loom") version "1.11-SNAPSHOT"
    kotlin("jvm") version "2.2.20"
}

group = "com.desgraciamon"
version = "1.0.0"

base {
    archivesName.set("desgraciamon4-visuals")
}

repositories {
    maven("https://api.modrinth.com/maven")
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.17.2")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.116.6+1.21.1")
    modImplementation("net.fabricmc:fabric-language-kotlin:1.13.6+kotlin.2.2.20")
    modCompileOnly("maven.modrinth:cobblemon:PdOzYHKU")
    modCompileOnly("maven.modrinth:watermedia:3.0.1")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

kotlin {
    jvmToolchain(21)
}

