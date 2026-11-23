package com.desgraciamon.announcements

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class CleanMissionDisplayAndEmojiTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun bootstrapMinecraft() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun `typePairName consume labels and category tags format cleanly without emojis`() {
        assertEquals("Agua / Volador", CleanMissionDisplay.typePairName("water", "flying"))
        assertEquals("Fuego", CleanMissionDisplay.typePairName("fire", "fire"))
        assertEquals("Planta", CleanMissionDisplay.typePairName("cobblemon:grass", ""))
        assertEquals("Cualquiera", CleanMissionDisplay.typePairName("", ""))

        assertEquals("Entrega", CleanMissionDisplay.consumeLabel(true))
        assertEquals("", CleanMissionDisplay.consumeLabel(false))
        assertEquals("Entrega objetos al reclamar", CleanMissionDisplay.consumeDetailLabel(true))
        assertEquals("", CleanMissionDisplay.consumeDetailLabel(false))

        assertEquals("CAPTURA", CleanMissionDisplay.categoryTagLabel(Mission(dailySlot = 2)))
        assertEquals("ITEMS", CleanMissionDisplay.categoryTagLabel(Mission(dailySlot = 1)))
        assertEquals("COMBATE", CleanMissionDisplay.categoryTagLabel(Mission(dailySlot = 3)))
        assertEquals(
            "ESPECIAL",
            CleanMissionDisplay.categoryTagLabel(
                Mission(
                    objectives = mutableListOf(
                        MissionObjective(type = ObjectiveType.CATCH_TYPE, amount = 2, pokemonType = "water"),
                        MissionObjective(type = ObjectiveType.DEFEAT_POKEMON, amount = 3, pokemonType = "fire")
                    )
                )
            )
        )
    }

    @Test
    fun `describeConstraints describeObjective and simpleMissionSummary render clean spanish text`() {
        val captureObj = MissionObjective(
            id = "o1",
            type = ObjectiveType.CATCH_TYPE,
            amount = 5,
            pokemonType = "water",
            secondaryPokemonType = "ground",
            minLevel = 20,
            minEvolutionStage = 2,
            pokemonProperties = "gender=female"
        )
        val constraints = CleanMissionDisplay.describeConstraints(captureObj)
        assertEquals(listOf("Nivel min. 20", "Etapa evolutiva min. 2", "Hembra"), constraints)

        val detailed = CleanMissionDisplay.describeObjective(captureObj)
        assertTrue(detailed.contains("Agua / Tierra"))
        assertTrue(detailed.contains("Nivel min. 20"))
        assertTrue(detailed.contains("Etapa evolutiva min. 2"))
        assertTrue(detailed.contains("Hembra"))
        assertFalse(containsForbiddenEmoji(detailed))

        val itemObj = MissionObjective(
            id = "o2",
            type = ObjectiveType.GET_ITEM,
            amount = 16,
            itemId = "minecraft:diamond",
            consumeOnClaim = true
        )
        val itemSummary = CleanMissionDisplay.simpleObjectiveSummary(itemObj, progress = 4, targetAmount = 16)
        assertTrue(itemSummary.startsWith("Entrega 16 "))
        assertTrue(itemSummary.endsWith("4/16"))
        assertFalse(containsForbiddenEmoji(itemSummary))

        val mission = Mission(
            id = "m_daily_1",
            dailySlot = 1,
            objectives = mutableListOf(itemObj)
        )
        val missionSummary = CleanMissionDisplay.simpleMissionSummary(mission, mapOf("o2" to 4), 0)
        assertEquals(itemSummary, missionSummary)
    }

    @Test
    fun `all source config and documentation files have zero emojis or legacy dingbats`() {
        val srcRoot = Path.of("src").toAbsolutePath().normalize()
        val configRoot = Path.of("..", "config", "desgraciamon4-announcements").toAbsolutePath().normalize()
        val estadoDoc = Path.of("..", "ESTADO_DEL_PROYECTO.md").toAbsolutePath().normalize()

        assertTrue(Files.isDirectory(srcRoot), "Missing src directory: $srcRoot")
        assertTrue(Files.isRegularFile(estadoDoc), "Missing ESTADO_DEL_PROYECTO.md: $estadoDoc")

        val allowedExtensions = setOf("kt", "java", "json", "properties")
        val filesToScan = mutableListOf<Path>()

        Files.walk(srcRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().substringAfterLast('.', "").lowercase() in allowedExtensions }
                .forEach { filesToScan.add(it) }
        }
        if (Files.isDirectory(configRoot)) {
            Files.walk(configRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().substringAfterLast('.', "").lowercase() in allowedExtensions }
                    .forEach { filesToScan.add(it) }
            }
        }
        filesToScan.add(estadoDoc)

        val violations = mutableListOf<String>()
        for (file in filesToScan) {
            val content = Files.readString(file, StandardCharsets.UTF_8)
            val bad = findForbiddenCodepoints(content)
            if (bad.isNotEmpty()) {
                violations.add("${file.fileName}: $bad")
            }
        }
        assertTrue(violations.isEmpty(), "Emoji/dingbat violations found: $violations")
    }

    private fun containsForbiddenEmoji(text: String): Boolean = findForbiddenCodepoints(text).isNotEmpty()

    private fun findForbiddenCodepoints(text: String): List<String> {
        val found = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val isForbidden = cp == 0x2726 || cp == 0x2714 ||
                cp == 0x2022 || cp == 0x25CF ||
                cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D ||
                cp == 0x2B50 || cp == 0x2B55 || cp == 0x2934 || cp == 0x2935 ||
                cp in 0x1F000..0x1FAFF ||
                cp in 0x2600..0x27BF ||
                cp in 0x2300..0x23FF
            if (isForbidden) {
                found.add("U+${cp.toString(16).uppercase()}")
            }
            i += Character.charCount(cp)
        }
        return found
    }
}
