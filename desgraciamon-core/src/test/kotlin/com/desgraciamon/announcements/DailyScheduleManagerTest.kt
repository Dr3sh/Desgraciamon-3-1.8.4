package com.desgraciamon.announcements

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyScheduleManagerTest {

    private class MutableClock(var currentInstant: Instant, private val zone: ZoneId = ZoneId.of("UTC")) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)
        override fun instant(): Instant = currentInstant
    }

    private fun samplePool(prefix: String, count: Int, slot: Int, tag: String): List<Mission> =
        (1..count).map { i ->
            Mission(
                id = "${prefix}_${i.toString().padStart(3, '0')}",
                title = "Mision $prefix $i",
                body = "Descripcion $i",
                daily = true,
                dailySlot = slot,
                categoryTag = tag
            )
        }

    private fun caracasInstant(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneId.of("America/Caracas")).toInstant()

    private fun createManager(clock: Clock, items: Int = 166, capture: Int = 167, combat: Int = 167): DailyScheduleManager {
        val mgr = DailyScheduleManager(clock, DailyCycleConfig())
        mgr.configure(
            DailyCycleConfig(),
            samplePool("item", items, 1, "ITEMS"),
            samplePool("catch", capture, 2, "CAPTURA"),
            samplePool("battle", combat, 3, "COMBATE")
        )
        mgr.loadState(DailyPersistedState(), markInitiallyUnlockedAsAnnounced = false)
        return mgr
    }

    @Test
    fun `venezuela staggered schedule unlocks at 0800 1330 1900 and stays open until 075959`() {
        val clock = MutableClock(caracasInstant(2026, 11, 18, 8, 0, 0))
        val mgr = createManager(clock)

        var snap = mgr.snapshot()
        assertEquals("2026-11-18", snap.cycleId)
        assertTrue(snap.slots[0].unlocked)
        assertFalse(snap.slots[1].unlocked)
        assertFalse(snap.slots[2].unlocked)
        assertNotNull(snap.slots[0].mission)
        assertNull(snap.slots[1].mission)
        assertNull(snap.slots[2].mission)
        assertEquals(5 * 3600L + 30 * 60L, snap.slots[1].unlockRemainingSeconds)
        assertEquals(11 * 3600L, snap.slots[2].unlockRemainingSeconds)
        assertEquals(24 * 3600L, snap.resetRemainingSeconds)

        clock.currentInstant = caracasInstant(2026, 11, 18, 13, 29, 59)
        snap = mgr.snapshot()
        assertTrue(snap.slots[0].unlocked)
        assertFalse(snap.slots[1].unlocked)
        assertEquals(1L, snap.slots[1].unlockRemainingSeconds)

        clock.currentInstant = caracasInstant(2026, 11, 18, 13, 30, 0)
        val eval1330 = mgr.evaluateTick()
        assertEquals(listOf(1, 2), eval1330.newlyUnlockedSlots)
        snap = mgr.snapshot()
        assertTrue(snap.slots[0].unlocked)
        assertTrue(snap.slots[1].unlocked)
        assertFalse(snap.slots[2].unlocked)
        assertEquals(5 * 3600L + 30 * 60L, snap.slots[2].unlockRemainingSeconds)

        clock.currentInstant = caracasInstant(2026, 11, 18, 19, 0, 0)
        val eval1900 = mgr.evaluateTick()
        assertEquals(listOf(3), eval1900.newlyUnlockedSlots)
        snap = mgr.snapshot()
        assertTrue(snap.slots.all { it.unlocked && it.mission != null })
        assertEquals(13 * 3600L, snap.resetRemainingSeconds)

        // Overnight 02:00 and 07:59:59 keep all 3 active in cycle 2026-11-18
        clock.currentInstant = caracasInstant(2026, 11, 19, 2, 0, 0)
        snap = mgr.snapshot()
        assertEquals("2026-11-18", snap.cycleId)
        assertTrue(snap.slots.all { it.unlocked && it.mission != null })
        assertEquals(6 * 3600L, snap.resetRemainingSeconds)

        clock.currentInstant = caracasInstant(2026, 11, 19, 7, 59, 59)
        snap = mgr.snapshot()
        assertEquals("2026-11-18", snap.cycleId)
        assertTrue(snap.slots.all { it.unlocked })
        assertEquals(1L, snap.resetRemainingSeconds)

        // Next day 08:00 rolls to 2026-11-19 and rotates all 3 missions
        val prevIds = snap.slots.map { it.mission!!.id }
        clock.currentInstant = caracasInstant(2026, 11, 19, 8, 0, 0)
        val resetEval = mgr.evaluateTick()
        assertTrue(resetEval.cycleReset)
        assertEquals("2026-11-19", resetEval.cycleId)
        snap = mgr.snapshot()
        assertTrue(snap.slots[0].unlocked)
        assertFalse(snap.slots[1].unlocked)
        assertFalse(snap.slots[2].unlocked)
        assertNotEquals(prevIds[0], snap.slots[0].mission!!.id)
    }

    @Test
    fun `schedule produces identical venezuela results regardless of host clock timezone`() {
        val instant = caracasInstant(2026, 11, 18, 14, 15, 0)
        val utcMgr = createManager(Clock.fixed(instant, ZoneId.of("UTC")))
        val tokyoMgr = createManager(Clock.fixed(instant, ZoneId.of("Asia/Tokyo")))

        val utcSnap = utcMgr.snapshot()
        val tokyoSnap = tokyoMgr.snapshot()

        assertEquals(utcSnap.cycleId, tokyoSnap.cycleId)
        assertEquals(utcSnap.resetRemainingSeconds, tokyoSnap.resetRemainingSeconds)
        assertEquals(utcSnap.slots.map { it.unlocked }, tokyoSnap.slots.map { it.unlocked })
        assertEquals(utcSnap.slots.map { it.unlockRemainingSeconds }, tokyoSnap.slots.map { it.unlockRemainingSeconds })
        assertEquals(utcSnap.slots.map { it.mission?.id }, tokyoSnap.slots.map { it.mission?.id })
    }

    @Test
    fun `pause hides all slots and resume realigns to real venezuela time without desync`() {
        val clock = MutableClock(caracasInstant(2026, 11, 18, 10, 0, 0))
        val mgr = createManager(clock)
        mgr.evaluateTick()

        val pauseRes = mgr.setPaused(true)
        assertTrue(pauseRes.changed)
        assertTrue(pauseRes.paused)
        var snap = mgr.snapshot()
        assertTrue(snap.paused)
        assertTrue(snap.slots.all { !it.unlocked && it.mission == null && it.unlockRemainingSeconds == 0L })

        // Advance real time to 15:00 while paused; evaluateTick does not advance
        clock.currentInstant = caracasInstant(2026, 11, 18, 15, 0, 0)
        val pausedTick = mgr.evaluateTick()
        assertFalse(pausedTick.cycleReset)
        assertTrue(pausedTick.newlyUnlockedSlots.isEmpty())

        // Resume at 15:00: slots 1 & 2 immediately active, slot 3 has 4h left
        val resumeRes = mgr.setPaused(false)
        assertTrue(resumeRes.changed)
        assertFalse(resumeRes.paused)
        snap = mgr.snapshot()
        assertFalse(snap.paused)
        assertTrue(snap.slots[0].unlocked)
        assertTrue(snap.slots[1].unlocked)
        assertFalse(snap.slots[2].unlocked)
        assertEquals(4 * 3600L, snap.slots[2].unlockRemainingSeconds)
        assertEquals(17 * 3600L, snap.resetRemainingSeconds)
    }

    @Test
    fun `simulateTime forceOpen and rotate behave deterministically`() {
        val clock = MutableClock(caracasInstant(2026, 11, 18, 9, 0, 0))
        val mgr = createManager(clock)
        mgr.evaluateTick()

        // Force open slot 2 early
        assertTrue(mgr.forceOpen(2))
        assertFalse(mgr.forceOpen(2))
        assertTrue(mgr.snapshot().slots[1].unlocked)
        assertFalse(mgr.snapshot().slots[2].unlocked)

        // Simulate 19:00 -> all 3 unlocked
        val sim19 = mgr.simulateTime("19:00")
        assertTrue(sim19.ok)
        assertTrue(mgr.snapshot().slots.all { it.unlocked })

        // Simulate 08:00 -> slots 2 and 3 locked again
        val sim08 = mgr.simulateTime("08:00")
        assertTrue(sim08.ok)
        val snap08 = mgr.snapshot()
        assertTrue(snap08.slots[0].unlocked)
        assertFalse(snap08.slots[1].unlocked)
        assertFalse(snap08.slots[2].unlocked)

        // Reset restores 09:00 real clock
        val reset = mgr.simulateTime("reset")
        assertTrue(reset.ok)
        assertTrue(mgr.snapshot().slots[0].unlocked)
        assertFalse(mgr.snapshot().slots[1].unlocked)

        // Rotate slot 1 and todas
        val beforeId = mgr.snapshot().slots[0].mission!!.id
        val rot1 = mgr.rotate("1")
        assertTrue(rot1.ok)
        assertEquals(listOf(1), rot1.rotatedSlots)
        val afterId = mgr.snapshot().slots[0].mission!!.id
        assertNotEquals(beforeId, afterId)

        val rotAll = mgr.rotate("todas")
        assertTrue(rotAll.ok)
        assertEquals(listOf(1, 2, 3), rotAll.rotatedSlots)
    }

    @Test
    fun `deterministic shuffle does not repeat across full pool size and avoids boundary repeat`() {
        val clock = MutableClock(caracasInstant(2026, 11, 18, 20, 0, 0))
        val mgr = createManager(clock, items = 166, capture = 167, combat = 167)

        val seenSlot1 = mutableListOf<String>()
        val seenSlot2 = mutableListOf<String>()
        val seenSlot3 = mutableListOf<String>()

        for (day in 0 until 166) {
            clock.currentInstant = caracasInstant(2026, 11, 18, 20, 0, 0).plusSeconds(day * 86400L)
            mgr.evaluateTick()
            val snap = mgr.snapshot()
            seenSlot1.add(snap.slots[0].mission!!.id)
            seenSlot2.add(snap.slots[1].mission!!.id)
            seenSlot3.add(snap.slots[2].mission!!.id)
        }

        assertEquals(166, seenSlot1.toSet().size)
        assertEquals(166, seenSlot2.toSet().size)
        assertEquals(166, seenSlot3.toSet().size)

        // Day 167 wraps slot 1 epoch and must not equal day 166's mission
        val lastSlot1 = seenSlot1.last()
        clock.currentInstant = caracasInstant(2026, 11, 18, 20, 0, 0).plusSeconds(166 * 86400L)
        mgr.evaluateTick()
        val wrappedSnap = mgr.snapshot()
        assertNotEquals(lastSlot1, wrappedSnap.slots[0].mission!!.id)
        // Slot 2 & 3 finish their 167th unique mission
        seenSlot2.add(wrappedSnap.slots[1].mission!!.id)
        seenSlot3.add(wrappedSnap.slots[2].mission!!.id)
        assertEquals(167, seenSlot2.toSet().size)
        assertEquals(167, seenSlot3.toSet().size)

        // Day 168: epoch 1 cursor 1 must not duplicate cursor 0
        val epoch1Day0Slot1 = wrappedSnap.slots[0].mission!!.id
        clock.currentInstant = caracasInstant(2026, 11, 18, 20, 0, 0).plusSeconds(167 * 86400L)
        mgr.evaluateTick()
        assertNotEquals(epoch1Day0Slot1, mgr.snapshot().slots[0].mission!!.id)
    }

    @Test
    fun `persisted state round trips cleanly with gson`(@TempDir tempDir: Path) {
        val clock = MutableClock(caracasInstant(2026, 11, 18, 10, 0, 0))
        val mgr = createManager(clock)
        mgr.forceOpen(2)
        mgr.rotate("1")

        val stateFile = tempDir.resolve("daily-state.json")
        Files.writeString(stateFile, BulletinJson.gson.toJson(mgr.exportState()))

        val reloadedState = BulletinJson.gson.fromJson(Files.readString(stateFile), DailyPersistedState::class.java)
        val mgr2 = createManager(clock)
        mgr2.loadState(reloadedState, markInitiallyUnlockedAsAnnounced = true)

        assertEquals(mgr.snapshot().slots.map { it.mission?.id }, mgr2.snapshot().slots.map { it.mission?.id })
        assertTrue(mgr2.snapshot().slots[1].unlocked)
    }
}
