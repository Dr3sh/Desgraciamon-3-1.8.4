package com.desgraciamon.vortex.economy

import com.desgraciamon.vortex.config.VortexConfig
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.math.BigInteger

object CobbleDollarsIntegration {
    private val LOGGER = LoggerFactory.getLogger("Desgraciamon-Economy")

    /**
     * Awards CobbleDollars to the player using direct API reflection if available,
     * falling back to the configurable CobbleDollars command on the server dispatcher.
     */
    fun depositCobbleDollars(player: ServerPlayer, amount: Int): Boolean {
        if (tryDirectReflection(player, amount)) {
            return true
        }
        return runCommandFallback(player, amount)
    }

    private fun tryDirectReflection(player: ServerPlayer, amount: Int): Boolean {
        return try {
            val extClass = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt")
            val getMethod = extClass.methods.firstOrNull {
                it.name == "getCobbleDollars" && it.parameterCount == 1
            }
            val setMethod = extClass.methods.firstOrNull {
                it.name == "setCobbleDollars" && it.parameterCount == 2
            }
            if (getMethod != null && setMethod != null) {
                val current = getMethod.invoke(null, player) as? BigInteger ?: BigInteger.ZERO
                val updated = current.add(BigInteger.valueOf(amount.toLong()))
                setMethod.invoke(null, player, updated)
                LOGGER.info("Deposited {} CobbleDollars to {} via direct API.", amount, player.scoreboardName)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun runCommandFallback(player: ServerPlayer, amount: Int): Boolean {
        return try {
            val server = player.server
            val rawCmd = VortexConfig.INSTANCE.cobbleDollarsCommandTemplate
                .replace("%player%", player.scoreboardName)
                .replace("%amount%", amount.toString())
                .removePrefix("/")
            server.commands.performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput().withPermission(4),
                rawCmd
            )
            LOGGER.info("Deposited {} CobbleDollars to {} via command '/{}'.", amount, player.scoreboardName, rawCmd)
            true
        } catch (e: Exception) {
            LOGGER.error("Failed to deposit CobbleDollars to ${player.scoreboardName}", e)
            false
        }
    }
}

