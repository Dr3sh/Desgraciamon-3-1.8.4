package com.desgraciamon.economy

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import org.slf4j.LoggerFactory
import java.math.BigInteger

object CobbleDollarsBridge {
    private const val API = "fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt"
    private val logger = LoggerFactory.getLogger("desgraciamon4-economy")

    fun isAvailable(): Boolean {
        return FabricLoader.getInstance().isModLoaded("cobbledollars")
    }

    fun getBalance(player: ServerPlayer): BigInteger {
        if (!isAvailable()) return BigInteger.ZERO
        return try {
            val apiClass = Class.forName(API)
            val method = apiClass.getMethod("getCobbleDollars", Player::class.java)
            (method.invoke(null, player) as? BigInteger) ?: BigInteger.ZERO
        } catch (e: Throwable) {
            logger.warn("Could not get CobbleDollars for ${player.scoreboardName}", e)
            BigInteger.ZERO
        }
    }

    fun deposit(player: ServerPlayer, amount: Long): Boolean {
        if (amount <= 0) return true
        if (!isAvailable()) {
            logger.warn("CobbleDollars is not loaded; cannot deposit $amount to ${player.scoreboardName}")
            return false
        }
        return try {
            val apiClass = Class.forName(API)
            val getMethod = apiClass.getMethod("getCobbleDollars", Player::class.java)
            val setMethod = apiClass.getMethod("setCobbleDollars", Player::class.java, BigInteger::class.java)
            val updateMethod = runCatching { apiClass.getMethod("updateCobbleDollarsAccount", ServerPlayer::class.java) }.getOrNull()

            val current = (getMethod.invoke(null, player) as? BigInteger) ?: BigInteger.ZERO
            val updated = current.add(BigInteger.valueOf(amount))
            setMethod.invoke(null, player, updated)
            updateMethod?.invoke(null, player)
            true
        } catch (e: Throwable) {
            logger.error("Failed to deposit $amount CobbleDollars to ${player.scoreboardName}", e)
            false
        }
    }

    fun withdraw(player: ServerPlayer, amount: Long): Boolean {
        if (amount <= 0) return true
        if (!isAvailable()) {
            logger.warn("CobbleDollars is not loaded; cannot withdraw $amount from ${player.scoreboardName}")
            return false
        }
        return try {
            val apiClass = Class.forName(API)
            val getMethod = apiClass.getMethod("getCobbleDollars", Player::class.java)
            val setMethod = apiClass.getMethod("setCobbleDollars", Player::class.java, BigInteger::class.java)
            val updateMethod = runCatching { apiClass.getMethod("updateCobbleDollarsAccount", ServerPlayer::class.java) }.getOrNull()

            val current = (getMethod.invoke(null, player) as? BigInteger) ?: BigInteger.ZERO
            val amountBig = BigInteger.valueOf(amount)
            if (current < amountBig) return false
            val updated = current.subtract(amountBig)
            setMethod.invoke(null, player, updated)
            updateMethod?.invoke(null, player)
            true
        } catch (e: Throwable) {
            logger.error("Failed to withdraw $amount CobbleDollars from ${player.scoreboardName}", e)
            false
        }
    }
}

