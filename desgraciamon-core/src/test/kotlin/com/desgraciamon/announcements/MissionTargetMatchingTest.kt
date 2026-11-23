package com.desgraciamon.announcements

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MissionTargetMatchingTest {

    @Test
    fun `matches single and dual types for both catch and defeat`() {
        val dualTarget = MissionObjective(
            type = ObjectiveType.CATCH_TYPE,
            pokemonType = "water",
            secondaryPokemonType = "flying"
        )
        val gyarados = PokemonTargetSnapshot("cobblemon:gyarados", setOf("water", "flying"), level = 25, evolutionStage = 2)
        val magikarp = PokemonTargetSnapshot("cobblemon:magikarp", setOf("water"), level = 10, evolutionStage = 1)
        val pidgey = PokemonTargetSnapshot("cobblemon:pidgey", setOf("normal", "flying"), level = 10, evolutionStage = 1)

        assertTrue(BulletinStore.matchesPokemonTargetPure(dualTarget, gyarados))
        assertFalse(BulletinStore.matchesPokemonTargetPure(dualTarget, magikarp))
        assertFalse(BulletinStore.matchesPokemonTargetPure(dualTarget, pidgey))

        val defeatTypeTarget = MissionObjective(
            type = ObjectiveType.DEFEAT_POKEMON,
            pokemonType = "fire",
            secondaryPokemonType = "flying"
        )
        val charizard = PokemonTargetSnapshot("cobblemon:charizard", setOf("fire", "flying"), level = 36, evolutionStage = 3)
        val charmander = PokemonTargetSnapshot("cobblemon:charmander", setOf("fire"), level = 12, evolutionStage = 1)

        assertTrue(BulletinStore.matchesPokemonTargetPure(defeatTypeTarget, charizard))
        assertFalse(BulletinStore.matchesPokemonTargetPure(defeatTypeTarget, charmander))
    }

    @Test
    fun `matches minLevel minEvolutionStage pokemonId and gender`() {
        val evolvedDefeat = MissionObjective(
            type = ObjectiveType.DEFEAT_POKEMON,
            pokemonType = "poison",
            minLevel = 20,
            minEvolutionStage = 2
        )
        val golbat = PokemonTargetSnapshot("cobblemon:golbat", setOf("poison", "flying"), level = 22, evolutionStage = 2)
        val lowGolbat = PokemonTargetSnapshot("cobblemon:golbat", setOf("poison", "flying"), level = 19, evolutionStage = 2)
        val zubat = PokemonTargetSnapshot("cobblemon:zubat", setOf("poison", "flying"), level = 25, evolutionStage = 1)

        assertTrue(BulletinStore.matchesPokemonTargetPure(evolvedDefeat, golbat))
        assertFalse(BulletinStore.matchesPokemonTargetPure(evolvedDefeat, lowGolbat))
        assertFalse(BulletinStore.matchesPokemonTargetPure(evolvedDefeat, zubat))

        val genderedCatch = MissionObjective(
            type = ObjectiveType.CATCH_TYPE,
            pokemonType = "normal",
            pokemonProperties = "gender=female"
        )
        assertTrue(BulletinStore.matchesPokemonTargetPure(genderedCatch, PokemonTargetSnapshot("cobblemon:eevee", setOf("normal"), 10, 1, "female")))
        assertFalse(BulletinStore.matchesPokemonTargetPure(genderedCatch, PokemonTargetSnapshot("cobblemon:eevee", setOf("normal"), 10, 1, "male")))

        val speciesTarget = MissionObjective(type = ObjectiveType.CATCH_SPECIES, pokemonId = "zubat")
        assertTrue(BulletinStore.matchesPokemonTargetPure(speciesTarget, zubat))
        assertFalse(BulletinStore.matchesPokemonTargetPure(speciesTarget, golbat))
    }

    @Test
    fun `mission isExpired evaluates against america caracas timezone`() {
        val caracas = ZoneId.of("America/Caracas")
        val mission = Mission(id = "m1", expirationDate = "2026-11-18")

        val beforeMidnightCaracas = ZonedDateTime.of(2026, 11, 18, 23, 59, 59, 0, caracas)
        val afterMidnightCaracas = ZonedDateTime.of(2026, 11, 19, 0, 0, 0, 0, caracas)

        assertFalse(mission.isExpired(caracas, beforeMidnightCaracas))
        assertTrue(mission.isExpired(caracas, afterMidnightCaracas))

        // Same instant as 2026-11-18 21:00:00 America/Caracas represented in UTC (2026-11-19T01:00:00Z) must NOT be expired
        val utcSameDayCaracas = ZonedDateTime.of(2026, 11, 19, 1, 0, 0, 0, ZoneId.of("UTC"))
        assertFalse(mission.isExpired(caracas, utcSameDayCaracas))

        // ISO 'T' separator support in expirationDate
        val isoMission = Mission(id = "m2", expirationDate = "2026-11-18T22:00:00")
        assertFalse(isoMission.isExpired(caracas, utcSameDayCaracas))
        assertTrue(isoMission.isExpired(caracas, ZonedDateTime.of(2026, 11, 19, 2, 0, 1, 0, ZoneId.of("UTC"))))

        assertEquals("ITEMS", Mission(dailySlot = 1).resolvedCategoryTag())
        assertEquals("CAPTURA", Mission(dailySlot = 2).resolvedCategoryTag())
        assertEquals("COMBATE", Mission(dailySlot = 3).resolvedCategoryTag())
    }

    @Test
    fun `legacy objectivesFor preserves targetUsesMemberCount and claim check aggregates duplicate itemIds`() {
        val legacyGuildMission = Mission(
            id = "legacy_guild",
            isGuildMission = true,
            targetUsesMemberCount = true,
            objective = ObjectiveType.GET_ITEM,
            itemId = "cobblemon:rare_candy",
            amount = 5
        )
        val resolved = BulletinStore.objectivesFor(legacyGuildMission)
        assertEquals(1, resolved.size)
        assertTrue(resolved.first().targetUsesMemberCount)

        val duplicateConsumeObjectives = listOf(
            MissionObjective(id = "o1", type = ObjectiveType.GET_ITEM, itemId = "cobblemon:rare_candy", amount = 5, consumeOnClaim = true),
            MissionObjective(id = "o2", type = ObjectiveType.GET_ITEM, itemId = "rare_candy".let { "cobblemon:$it" }, amount = 5, consumeOnClaim = true)
        )
        val required = BulletinStore.aggregateRequiredConsumedItems(duplicateConsumeObjectives, isGuildMission = false, guildMemberCount = 1)
        assertEquals(10, required["cobblemon:rare_candy"])
        assertEquals("cobblemon:rare_candy", BulletinStore.findMissingConsumedItem(required, mapOf("cobblemon:rare_candy" to 6)))
        assertEquals(null, BulletinStore.findMissingConsumedItem(required, mapOf("cobblemon:rare_candy" to 10)))
    }
}
