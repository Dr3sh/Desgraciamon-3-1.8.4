package com.desgraciamon.announcements

import io.netty.buffer.Unpooled
import net.minecraft.SharedConstants
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.KeybindContents
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class NotificationAndSyncPayloadTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun bootstrapMinecraft() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private fun collectKeybindContents(component: Component, out: MutableList<KeybindContents>) {
        val contents = component.contents
        if (contents is KeybindContents) {
            out.add(contents)
        }
        component.siblings.forEach { collectKeybindContents(it, out) }
    }

    @Test
    fun `daily unlock notification embeds open keybind and 38BDF8 color`() {
        val comp = BulletinStore.buildDailyUnlockNotification("#38BDF8")
        val keybinds = mutableListOf<KeybindContents>()
        collectKeybindContents(comp, keybinds)

        assertEquals(1, keybinds.size)
        assertEquals("key.desgraciamon4_announcements.open", keybinds.first().name)
        assertTrue(comp.string.contains("Hay una nueva misión diaria"))
        assertTrue(comp.string.contains("revísala con la tecla ["))

        val coloredPart = comp.siblings.firstOrNull { it.style.color != null }
        assertEquals(0x38BDF8, coloredPart?.style?.color?.value)
    }

    @Test
    fun `redacted daily snapshot omits locked missions and stays well below packet limit`() {
        val slot1Mission = Mission(id = "daily_item_001", title = "Cosecha", daily = true, dailySlot = 1, categoryTag = "ITEMS")
        val cycle = DailyCycleSnapshot(
            paused = false,
            cycleId = "2026-11-18",
            resetRemainingSeconds = 80000L,
            serverTimeEpochMs = 1795000000000L,
            slots = listOf(
                DailySlotSnapshot(1, "ITEMS", unlocked = true, unlockRemainingSeconds = 0L, mission = slot1Mission),
                DailySlotSnapshot(2, "CAPTURA", unlocked = false, unlockRemainingSeconds = 19800L, mission = null),
                DailySlotSnapshot(3, "COMBATE", unlocked = false, unlockRemainingSeconds = 39600L, mission = null)
            ),
            bonus = DailyBonusSnapshot(0, 3, false, 2500L, listOf(ItemMissionReward("cobblemon:rare_candy", 2)))
        )
        val snapshot = ClientBulletinSnapshot(
            document = BulletinDocument(),
            state = PlayerBulletinState(),
            dailyCycle = cycle,
            uiTheme = DailyUiThemeConfig()
        )
        val json = BulletinJson.gson.toJson(snapshot)
        assertTrue(json.length < 262_144)

        val decoded = BulletinJson.gson.fromJson(json, ClientBulletinSnapshot::class.java)
        assertNull(decoded.dailyCycle.slots[1].mission)
        assertNull(decoded.dailyCycle.slots[2].mission)
        assertEquals("daily_item_001", decoded.dailyCycle.slots[0].mission?.id)
    }

    @Test
    fun `text catalog strips legacy symbols and emojis from existing overrides`(@TempDir tempDir: Path) {
        val textsFile = tempDir.resolve("texts.json")
        val dirtyValue = "<color=#38BDF8>\u2726</color> Mision \u2714 lista \uD83C\uDF89"
        val jsonPayload = BulletinJson.gson.toJson(
            mapOf(
                "custom.key" to dirtyValue,
                "daily.unlocked" to "<color={color}>¡Nueva misión diaria disponible! Pulsa [{key}] para verla.</color>"
            )
        )
        Files.writeString(textsFile, jsonPayload)
        TextCatalog.load(mapOf("custom.key" to "Mision lista"), textsFile.toFile())
        val cleaned = TextCatalog.raw("custom.key")
        assertFalse(DailyMissionCatalog.containsEmoji(cleaned))
        assertFalse(cleaned.contains("\u2726"))
        assertFalse(cleaned.contains("\u2714"))
    }

    @Test
    fun `parseThemeColor TextCatalog and RichText validate and normalize hex colors consistently`() {
        val fallback = 0xFF112233.toInt()
        assertEquals(fallback, parseThemeColor("-12345", fallback))
        assertEquals(fallback, parseThemeColor("+38BDF", fallback))
        assertEquals(fallback, parseThemeColor("#ZZZZZZ", fallback))
        assertEquals(fallback, parseThemeColor("#12", fallback))
        assertEquals(fallback, parseThemeColor("", fallback))

        assertEquals(0xFF38BDF8.toInt(), parseThemeColor("#38BDF8", fallback))
        assertEquals(0xFF38BDF8.toInt(), parseThemeColor("38BDF8", fallback))
        assertEquals(0xFFAABBCC.toInt(), parseThemeColor("#abc", fallback))
        assertEquals(0xFFAABBCC.toInt(), parseThemeColor("abc", fallback))
        assertEquals(0x8038BDF8.toInt(), parseThemeColor("#8038BDF8", fallback))
        assertEquals(0x00FF00FF, parseThemeColor("00FF00FF", fallback))

        val unlockNoHash = TextCatalog.dailyUnlockComponent("FF5555")
        val unlockColor = unlockNoHash.siblings.firstOrNull { it.style.color != null }?.style?.color?.value
        assertEquals(0xFF5555, unlockColor)

        val unlockInvalid = TextCatalog.dailyUnlockComponent("-12345")
        val fallbackColor = unlockInvalid.siblings.firstOrNull { it.style.color != null }?.style?.color?.value
        assertEquals(0x38BDF8, fallbackColor)

        val rich6 = RichText.parse("<color=FF5555>Hola</color>", emptyMap())
        assertEquals("Hola", rich6.string)
        assertEquals(0xFF5555, rich6.siblings.first().style.color?.value)

        val rich3 = RichText.parse("<color=abc>Hola</color>", emptyMap())
        assertEquals("Hola", rich3.string)
        assertEquals(0xAABBCC, rich3.siblings.first().style.color?.value)

        val richInvalid = RichText.parse("<color=-12345>Hola", emptyMap())
        assertEquals("<color=-12345>Hola", richInvalid.string)
    }

    @Test
    fun `bulletin sync payload codec handles large multi-image payloads above 262144 chars`() {
        val img = "data:image/png;base64," + "A".repeat(179_000)
        val doc = BulletinDocument(
            announcements = mutableListOf(
                Announcement(id = "a1", title = "Img 1", imageUrl = img),
                Announcement(id = "a2", title = "Img 2", imageUrl = img)
            )
        )
        val json = BulletinJson.gson.toJson(ClientBulletinSnapshot(document = doc, state = PlayerBulletinState()))
        assertTrue(json.length > 350_000, "Expected > 350k payload, got ${json.length}")

        val buf = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        BulletinSyncPayload.CODEC.encode(buf, BulletinSyncPayload(json))
        val decoded = BulletinSyncPayload.CODEC.decode(buf)
        assertEquals(json, decoded.json)
    }
}
