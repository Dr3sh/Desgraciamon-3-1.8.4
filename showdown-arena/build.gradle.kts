plugins {
    id("fabric-loom") version "1.11-SNAPSHOT"
    kotlin("jvm") version "2.2.20"
}

group = "com.desgraciamon"
version = "1.4.1"

base {
    archivesName.set("desgraciamon4-showdown")
}

repositories {
    maven("https://api.modrinth.com/maven")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.ladysnake.org/releases")
    mavenCentral()
}

loom {
    mixin {
        defaultRefmapName = "showdown_arena.refmap.json"
    }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.17.2")
    modImplementation("net.fabricmc:fabric-language-kotlin:1.13.6+kotlin.2.2.20")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.116.6+1.21.1")
    modImplementation("org.ladysnake:satin:2.0.0")
    include("org.ladysnake:satin:2.0.0")
    modCompileOnly("maven.modrinth:cobblemon:PdOzYHKU")
    compileOnly(
        files(
            "C:/Users/Administrator/AppData/Roaming/PrismLauncher/instances/1.21.1/minecraft/mods/dr3sh-voice-chat-fabric-1.21.1.jar"
        )
    )
    annotationProcessor("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.remapJar {
    archiveClassifier.set("")
    destinationDirectory.set(file("build/libs"))
}

tasks.register("printJar") {
    doLast {
        val f = tasks.remapJar.get().archiveFile.get().asFile
        println("REMAP_JAR_OUT: " + f.absolutePath)
        println("REMAP_JAR_EXISTS: " + f.exists())
    }
}

kotlin {
    jvmToolchain(21)
}
