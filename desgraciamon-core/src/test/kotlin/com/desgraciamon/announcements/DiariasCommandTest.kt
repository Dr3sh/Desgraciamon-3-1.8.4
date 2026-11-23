package com.desgraciamon.announcements

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.SharedConstants
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DiariasCommandTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun bootstrapMinecraft() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private class CapturingCommandSource : CommandSource {
        val messages = mutableListOf<String>()

        override fun sendSystemMessage(component: Component) {
            messages.add(component.string)
        }

        override fun acceptsSuccess(): Boolean = true
        override fun acceptsFailure(): Boolean = true
        override fun shouldInformAdmins(): Boolean = false
    }

    private fun makeSource(permissionLevel: Int, capture: CapturingCommandSource = CapturingCommandSource()): CommandSourceStack {
        return CommandSourceStack(
            capture,
            Vec3.ZERO,
            Vec2.ZERO,
            null,
            permissionLevel,
            "tester",
            Component.literal("tester"),
            null,
            null
        )
    }

    @Test
    fun `argument normalizers accept valid inputs and reject invalid ones`() {
        assertEquals("1", DiariasCommands.normalizeRotateTarget("1"))
        assertEquals("2", DiariasCommands.normalizeRotateTarget(" 2 "))
        assertEquals("3", DiariasCommands.normalizeRotateTarget("3"))
        assertEquals("todas", DiariasCommands.normalizeRotateTarget("TODAS"))
        assertEquals("todas", DiariasCommands.normalizeRotateTarget("all"))
        assertEquals("recompensa", DiariasCommands.normalizeRotateTarget("RECOMPENSA"))
        assertEquals("recompensa", DiariasCommands.normalizeRotateTarget("bonus"))
        assertNull(DiariasCommands.normalizeRotateTarget("4"))
        assertNull(DiariasCommands.normalizeRotateTarget("invalid"))

        assertEquals(1, DiariasCommands.normalizeOpenSlot(1))
        assertEquals(2, DiariasCommands.normalizeOpenSlot(2))
        assertEquals(3, DiariasCommands.normalizeOpenSlot(3))
        assertNull(DiariasCommands.normalizeOpenSlot(0))
        assertNull(DiariasCommands.normalizeOpenSlot(4))

        assertEquals("08:00", DiariasCommands.normalizeSimulateMode("08:00"))
        assertEquals("08:00", DiariasCommands.normalizeSimulateMode("8:00"))
        assertEquals("13:30", DiariasCommands.normalizeSimulateMode("\"13:30\""))
        assertEquals("19:00", DiariasCommands.normalizeSimulateMode(" 19:00 "))
        assertEquals("reset", DiariasCommands.normalizeSimulateMode("RESET"))
        assertNull(DiariasCommands.normalizeSimulateMode("12:00"))
    }

    @Test
    fun `permission check requires OP level 2 or higher`() {
        val nonOp0 = makeSource(0)
        val nonOp1 = makeSource(1)
        val op2 = makeSource(2)
        val op4 = makeSource(4)

        assertFalse(DiariasCommands.hasPermission(nonOp0))
        assertFalse(DiariasCommands.hasPermission(nonOp1))
        assertTrue(DiariasCommands.hasPermission(op2))
        assertTrue(DiariasCommands.hasPermission(op4))

        val dispatcher = CommandDispatcher<CommandSourceStack>()
        DiariasCommands.register(dispatcher)

        assertFalse(dispatcher.parse("diarias pausa", nonOp1).context.nodes.let { nodes ->
            val rootNode = dispatcher.root.getChild("diarias")
            rootNode.canUse(nonOp1)
        })
        assertTrue(dispatcher.root.getChild("diarias").canUse(op2))
    }

    @Test
    fun `brigadier registration provides suggestions and executes end to end bulletin store actions`() {
        val dispatcher = CommandDispatcher<CommandSourceStack>()
        DiariasCommands.register(dispatcher)

        val capture = CapturingCommandSource()
        val opSource = makeSource(2, capture)

        // Check suggestions on subcommands
        val rotarParse = dispatcher.parse("diarias rotar ", opSource)
        val rotarSuggestions = dispatcher.getCompletionSuggestions(rotarParse).get().list.map { it.text }
        assertTrue(rotarSuggestions.containsAll(listOf("1", "2", "3", "todas", "recompensa")))

        val simularParse = dispatcher.parse("diarias simular ", opSource)
        val simularSuggestions = dispatcher.getCompletionSuggestions(simularParse).get().list.map { it.text }
        assertTrue(simularSuggestions.containsAll(listOf("08:00", "13:30", "19:00", "reset")))

        val abrirParse = dispatcher.parse("diarias abrir ", opSource)
        val abrirSuggestions = dispatcher.getCompletionSuggestions(abrirParse).get().list.map { it.text }
        assertTrue(abrirSuggestions.containsAll(listOf("1", "2", "3")))

        // Execute end-to-end commands against BulletinStore and always restore clean state
        val initiallyPaused = BulletinStore.toggleDailyPause(opSource)
        // Restore to unpaused if we just paused it, or record original state cleanly:
        val originalPaused = !initiallyPaused
        if (initiallyPaused) {
            BulletinStore.toggleDailyPause(opSource) // now unpaused
        }

        try {
            capture.messages.clear()
            assertEquals(1, dispatcher.execute("diarias simular 08:00", opSource))
            assertTrue(capture.messages.last().contains("08:00"))

            // Force open slot 2 at 08:00 -> succeeds once, then fails because already open
            assertEquals(1, dispatcher.execute("diarias abrir 2", opSource))
            assertTrue(capture.messages.last().contains("desbloqueada"))
            assertEquals(0, dispatcher.execute("diarias abrir 2", opSource))

            // Rotate slot 1 and all slots
            assertEquals(1, dispatcher.execute("diarias rotar 1", opSource))
            assertTrue(capture.messages.last().contains("1"))
            assertEquals(1, dispatcher.execute("diarias rotar todas", opSource))
            assertTrue(capture.messages.last().contains("1, 2, 3"))
            assertEquals(1, dispatcher.execute("diarias recompensa", opSource))
            assertTrue(capture.messages.last().contains("Recompensa del dia reseteada"))
            assertEquals(1, dispatcher.execute("diarias rotar recompensa", opSource))
            assertTrue(capture.messages.last().contains("Recompensa del dia reseteada"))

            // Pause & unpause via command
            assertEquals(1, dispatcher.execute("diarias pausa", opSource))
            assertTrue(capture.messages.last().contains("pausadas"))
            assertEquals(1, dispatcher.execute("diarias pausa", opSource))
            assertTrue(capture.messages.last().contains("reanudadas"))

            assertEquals(1, dispatcher.execute("diarias simular reset", opSource))
            assertTrue(capture.messages.last().contains("restablecida"))
        } finally {
            BulletinStore.simulateDailyTime("reset")
            val currentPaused = BulletinStore.toggleDailyPause(opSource)
            if (currentPaused != originalPaused) {
                BulletinStore.toggleDailyPause(opSource)
            }
        }
    }

    @Test
    fun `pausa and resume preserve venezuela schedule alignment across elapsed hours`() {
        val caracas = ZoneId.of("America/Caracas")
        var now = ZonedDateTime.of(2026, 11, 18, 8, 5, 0, 0, caracas).toInstant()
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = now
        }
        val scheduler = DailyScheduleManager(clock, DailyCycleConfig())
        val items = listOf(Mission(id = "i1", daily = true, dailySlot = 1, categoryTag = "ITEMS"))
        val capture = listOf(Mission(id = "c1", daily = true, dailySlot = 2, categoryTag = "CAPTURA"))
        val combat = listOf(Mission(id = "b1", daily = true, dailySlot = 3, categoryTag = "COMBATE"))
        scheduler.configure(DailyCycleConfig(), items, capture, combat)
        scheduler.loadState(DailyPersistedState(), markInitiallyUnlockedAsAnnounced = true)

        assertTrue(scheduler.setPaused(true).paused)
        // Advance 11 hours to 19:05 Caracas time while paused
        now = ZonedDateTime.of(2026, 11, 18, 19, 5, 0, 0, caracas).toInstant()
        assertTrue(scheduler.snapshot().slots.none { it.unlocked })

        // Resuming immediately unlocks all 3 slots matching 19:05 Caracas time
        val resume = scheduler.setPaused(false)
        assertFalse(resume.paused)
        assertTrue(scheduler.snapshot().slots.all { it.unlocked })
        assertEquals(12 * 3600L + 55 * 60L, scheduler.snapshot().resetRemainingSeconds)
    }
}
