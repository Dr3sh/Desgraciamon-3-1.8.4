package com.addoncobblemon.legendarycooldown

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.world.item.ItemStack

object RefundTracker {
    private data class TrackedBall(val stack: ItemStack, val timestamp: Long = System.currentTimeMillis())
    private val markedBalls = ConcurrentHashMap<UUID, TrackedBall>()

    fun mark(ballId: UUID, itemStack: ItemStack) {
        cleanExpired()
        markedBalls.putIfAbsent(ballId, TrackedBall(itemStack.copy()))
    }

    fun consume(ballId: UUID): ItemStack? {
        cleanExpired()
        return markedBalls.remove(ballId)?.stack
    }

    private fun cleanExpired() {
        val now = System.currentTimeMillis()
        if (markedBalls.size > 20) {
            markedBalls.entries.removeIf { now - it.value.timestamp > 60_000L }
        }
    }
}
