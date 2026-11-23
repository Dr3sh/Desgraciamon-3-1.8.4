package com.desgraciamon.announcements

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DailyMissionCatalogTest {

    @Test
    fun `seeds config file from classpath and validates 500 cobblemon missions`(@TempDir tempDir: Path) {
        val file = DailyMissionCatalog.load(tempDir)
        assertTrue(Files.exists(tempDir.resolve(DailyMissionCatalog.FILE_NAME)))

        assertEquals(166, file.itemsMissions.size)
        assertEquals(167, file.captureMissions.size)
        assertEquals(167, file.combatMissions.size)
        assertEquals(500, file.allMissions().size)

        val validationErrors = DailyMissionCatalog.validateStrict(file)
        assertTrue(validationErrors.isEmpty(), "Validation errors: $validationErrors")

        // All 125 excluded species absent
        val allMissions = file.allMissions()
        assertTrue(allMissions.none { m ->
            m.objectives.any { it.pokemonId.lowercase().substringAfter(':') in DailyMissionCatalog.EXCLUDED_SPECIES }
        })

        // Zero Pokemon rewards
        assertTrue(allMissions.all { it.pokemonReward == null && it.pokemonRewards.isEmpty() })

        // charcoal_stick present, cobblemon:charcoal absent
        assertTrue(file.itemsMissions.any { m -> m.objectives.any { it.itemId == "cobblemon:charcoal_stick" } })
        assertFalse(allMissions.any { m -> m.objectives.any { it.itemId == "cobblemon:charcoal" } })

        // Mix of consumeOnClaim = true and false
        assertTrue(file.itemsMissions.any { m -> m.objectives.any { it.consumeOnClaim } })
        assertTrue(file.itemsMissions.any { m -> m.objectives.any { !it.consumeOnClaim } })

        // Rare R3 species scaled 1..2
        val rareSpecies = setOf(
            "eevee", "riolu", "ralts", "abra", "bagon", "gible", "larvitar",
            "beldum", "dratini", "porygon", "mimikyu", "lapras", "snorlax"
        )
        val rareMissions = (file.captureMissions + file.combatMissions).filter { m ->
            m.objectives.any { it.pokemonId.lowercase().substringAfter(':') in rareSpecies }
        }
        assertTrue(rareMissions.size >= 26)
        assertTrue(rareMissions.all { m -> m.objectives.all { it.amount in 1..2 } })

        // Config defaults & theme
        assertEquals("America/Caracas", file.cycle.timezone)
        assertEquals("08:00", file.cycle.resetTime)
        assertEquals("08:00", file.cycle.slot1UnlockTime)
        assertEquals("13:30", file.cycle.slot2UnlockTime)
        assertEquals("19:00", file.cycle.slot3UnlockTime)
        assertEquals("#0E1117", file.uiTheme.bgBaseHex)
        assertEquals("#161B22", file.uiTheme.bgCardHex)
        assertEquals("#2D333B", file.uiTheme.borderHex)
        assertEquals("#38BDF8", file.uiTheme.dailyAccentHex)
        assertEquals("#38BDF8", file.uiTheme.notificationColorHex)
        assertTrue(file.dailyBonus.moneyReward > 0L)
        assertTrue(file.dailyBonus.itemRewards.isNotEmpty())

        // Zero emojis in raw JSON
        val rawJson = Files.readString(tempDir.resolve(DailyMissionCatalog.FILE_NAME))
        assertFalse(DailyMissionCatalog.containsEmoji(rawJson))
    }
}
