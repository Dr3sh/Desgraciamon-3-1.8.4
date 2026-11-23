plugins {
    id("fabric-loom") version "1.11-SNAPSHOT"
    kotlin("jvm") version "2.2.20"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.desgraciamon"
version = "2.0.1"

base {
    archivesName.set("desgraciamon4-discord-bridge")
}

repositories {
    maven("https://api.modrinth.com/maven")
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

configurations {
    create("shade")
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.17.2")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.116.6+1.21.1")
    modImplementation("net.fabricmc:fabric-language-kotlin:1.13.6+kotlin.2.2.20")
    modCompileOnly("maven.modrinth:cobblemon:PdOzYHKU")
    implementation("net.dv8tion:JDA:5.2.2")
    add("shade", "net.dv8tion:JDA:5.2.2")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shade"))
    archiveClassifier.set("all")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
    exclude("com/sun/jna/**")
    relocate("net.dv8tion.jda", "com.desgraciamon.discordbridge.internal.jda")
}

tasks.remapJar {
    dependsOn(tasks.shadowJar)
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    archiveClassifier.set("")
}

tasks.register("deployMod") {
    dependsOn(tasks.remapJar)
    doLast {
        val f = tasks.remapJar.get().archiveFile.get().asFile
        println("REMAP_JAR_PATH: ${f.absolutePath} exists=${f.exists()} size=${if (f.exists()) f.length() else 0}")
        if (f.exists()) {
            val distDir = file("../dist")
            distDir.mkdirs()
            f.copyTo(File(distDir, f.name), overwrite = true)
            println("Copied to ${distDir.absolutePath}/${f.name}")
            val prismMods = File("C:/Users/Administrator/AppData/Roaming/PrismLauncher/instances/1.21.1/minecraft/mods")
            if (prismMods.exists()) {
                val target = File(prismMods, f.name)
                val copied = runCatching {
                    f.copyTo(target, overwrite = true)
                    true
                }.getOrElse { false }
                if (copied) {
                    println("Copied to PrismLauncher mods/${f.name}")
                } else {
                    println("WARNING: Could not overwrite ${target.name} directly (Minecraft is currently running).")
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

