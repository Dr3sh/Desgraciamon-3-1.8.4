plugins {
    id("fabric-loom") version "1.11-SNAPSHOT"
    kotlin("jvm") version "2.2.20"
}

group = "com.desgraciamon"
version = "1.0.0"

base {
    archivesName.set("desgraciamon-core")
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
    modCompileOnly(files("libs/CobbleDollars.jar"))
    modCompileOnly("maven.modrinth:cobblemon:PdOzYHKU")
    modCompileOnly("maven.modrinth:watermedia:3.0.1")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.test {
    useJUnitPlatform()
}

val copyRemappedJarToBuildLibs by tasks.registering {
    dependsOn(tasks.remapJar)
    val remappedJar = tasks.remapJar.flatMap { it.archiveFile }
    val targetDir = layout.buildDirectory.dir("libs")
    inputs.file(remappedJar)
    onlyIf {
        val src = remappedJar.get().asFile
        val dst = targetDir.get().file(src.name).asFile
        src.exists() && src.canonicalFile != dst.canonicalFile
    }
    doLast {
        val src = remappedJar.get().asFile
        val dstDir = targetDir.get().asFile
        dstDir.mkdirs()
        src.copyTo(dstDir.resolve(src.name), overwrite = true)
    }
}

tasks.named("assemble") {
    dependsOn(copyRemappedJarToBuildLibs)
}

tasks.register("printJar") {
    doLast {
        val f = tasks.remapJar.get().archiveFile.get().asFile
        println("REMAP_JAR_OUT: " + f.absolutePath + " exists=" + f.exists())
    }
}
