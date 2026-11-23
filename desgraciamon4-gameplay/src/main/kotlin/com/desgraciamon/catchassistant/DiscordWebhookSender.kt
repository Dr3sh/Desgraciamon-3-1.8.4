package com.desgraciamon.catchassistant

import net.minecraft.server.MinecraftServer
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import com.google.gson.Gson

object DiscordWebhookSender {
  private val gson = Gson()

    fun send(
      server: MinecraftServer,
      speciesName: String,
      playerName: String,
      playerUuid: UUID,
      nationalDexNumber: Int,
      capturedAt: Instant,
      nextCaptureAt: Instant,
      shiny: Boolean
    ) {
        val config = DesgraciamonConfig.current
        val url = config.discordWebhookUrl.ifBlank { return }
      val discordId = runCatching {
        Class.forName("com.desgraciamon.discordbridge.DiscordLinkData")
          .getMethod("discordId", MinecraftServer::class.java, UUID::class.java)
          .invoke(null, server, playerUuid) as? String
      }.getOrNull()
      val capturedEpoch = capturedAt.epochSecond
      val nextEpoch = nextCaptureAt.epochSecond
      val animatedSprite = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/showdown/${if (shiny) "shiny/" else ""}$nationalDexNumber.gif"
      val artwork = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/$nationalDexNumber.png"
      val identity = if (discordId == null) playerName else "$playerName (<@$discordId>)"
      val webhookUsername = config.discordWebhookName.trim().ifBlank { TextCatalog.string("discord.username").trim() }
      val webhookAvatarUrl = config.discordWebhookAvatarUrl.trim().ifBlank { TextCatalog.string("discord.avatar_url").trim() }
      val contentKey = if (shiny) "discord.content.shiny" else "discord.content.normal"
      val descKey = if (shiny) "discord.description.shiny" else "discord.description.normal"
      val payloadMap = linkedMapOf<String, Any>(
        "content" to TextCatalog.string(
          contentKey,
          "identity" to identity,
          "species" to speciesName
        ),
        "allowed_mentions" to mapOf("parse" to emptyList<String>(), "users" to listOfNotNull(discordId)),
        "embeds" to listOf(mapOf(
              "title" to TextCatalog.string(if (shiny) "discord.title.shiny" else "discord.title.legendary", "species" to speciesName),
                "description" to TextCatalog.string(
                  descKey,
                  "identity" to identity,
                  "species" to speciesName,
                  "captured" to capturedEpoch,
                  "next" to nextEpoch
                ),
              "color" to TextCatalog.color(if (shiny) "discord.color.shiny" else "discord.color.legendary", if (shiny) 0xE3B341 else 0x42C98A),
          "thumbnail" to mapOf("url" to artwork),
          "image" to mapOf("url" to animatedSprite),
          "footer" to mapOf("text" to TextCatalog.string("discord.footer")),
          "timestamp" to capturedAt.toString()
        ))
      )
      if (webhookUsername.isNotBlank()) {
        payloadMap["username"] = webhookUsername
      }
      if (webhookAvatarUrl.startsWith("http://") || webhookAvatarUrl.startsWith("https://")) {
        payloadMap["avatar_url"] = webhookAvatarUrl
      }
      val body = gson.toJson(payloadMap)

    CompletableFuture.runAsync {
      runCatching {
                (java.net.URI(url).toURL().openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = 5000
          readTimeout = 5000
          doOutput = true
          setRequestProperty("Content-Type", "application/json; charset=utf-8")
          setRequestProperty("User-Agent", "DesgraciamonCatchAssistant")
          outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
          responseCode
          disconnect()
        }
      }.onFailure {
        com.cobblemon.mod.common.Cobblemon.LOGGER.warn("Discord webhook request failed", it)
      }
        }
    }
}
