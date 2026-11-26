package com.desgraciamon.catchassistant

import com.cobblemon.mod.common.Cobblemon
import com.google.gson.Gson
import net.minecraft.server.MinecraftServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

object DiscordWebhookSender {
    private val gson = Gson()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun send(
        server: MinecraftServer,
        speciesName: String,
        playerName: String,
        playerUuid: UUID,
        nationalDexNumber: Int,
        capturedAt: Instant,
        nextCaptureAt: Instant,
        shiny: Boolean,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val config = DesgraciamonConfig.current
        val url = config.discordWebhookUrl.trim().ifBlank {
            onResult?.invoke(false, "discordWebhookUrl está vacío en config/desgraciamon-catch-assistant.properties")
            return
        }

        val discordId = runCatching {
            Class.forName("com.desgraciamon.discordbridge.DiscordLinkData")
                .getMethod("discordId", MinecraftServer::class.java, UUID::class.java)
                .invoke(null, server, playerUuid) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val capturedEpoch = capturedAt.epochSecond
        val nextEpoch = nextCaptureAt.epochSecond
        val animatedSprite = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/showdown/${if (shiny) "shiny/" else ""}$nationalDexNumber.gif"
        val artwork = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/$nationalDexNumber.png"
        val identity = if (discordId == null) playerName else "$playerName (<@$discordId>)"
        val webhookUsername = config.discordWebhookName.trim().ifBlank { TextCatalog.string("discord.username").trim() }
        val webhookAvatarUrl = config.discordWebhookAvatarUrl.trim().ifBlank { TextCatalog.string("discord.avatar_url").trim() }
        val contentKey = if (shiny) "discord.content.shiny" else "discord.content.normal"
        val descKey = if (shiny) "discord.description.shiny" else "discord.description.normal"

        val allowedMentions = linkedMapOf<String, Any>("parse" to emptyList<String>())
        if (discordId != null) {
            allowedMentions["users"] = listOf(discordId)
        }

        val embed = linkedMapOf<String, Any>(
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
        )

        val payloadMap = linkedMapOf<String, Any>(
            "content" to TextCatalog.string(
                contentKey,
                "identity" to identity,
                "species" to speciesName
            ),
            "allowed_mentions" to allowedMentions,
            "embeds" to listOf(embed)
        )
        if (webhookUsername.isNotBlank()) {
            payloadMap["username"] = webhookUsername.take(80)
        }
        if (webhookAvatarUrl.startsWith("http://") || webhookAvatarUrl.startsWith("https://")) {
            payloadMap["avatar_url"] = webhookAvatarUrl
        }
        val body = gson.toJson(payloadMap)

        CompletableFuture.runAsync {
            runCatching {
                var response = postJson(url, body)
                if (response.statusCode() == 429) {
                    val retryAfterMs = response.headers().firstValue("Retry-After")
                        .orElse("1")
                        .toDoubleOrNull()
                        ?.let { (it * 1000.0).toLong().coerceIn(500L, 5000L) }
                        ?: 1200L
                    Thread.sleep(retryAfterMs)
                    response = postJson(url, body)
                }

                val code = response.statusCode()
                if (code in 200..299) {
                    onResult?.invoke(true, "HTTP $code")
                } else {
                    val errBody = response.body().orEmpty().take(400)
                    Cobblemon.LOGGER.warn("Discord webhook devolvió HTTP $code: $errBody")
                    onResult?.invoke(false, "HTTP $code: $errBody")
                }
            }.onFailure { err ->
                Cobblemon.LOGGER.warn("Discord webhook request failed", err)
                onResult?.invoke(false, err.message ?: err.javaClass.simpleName)
            }
        }
    }

    private fun postJson(url: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .header("User-Agent", "DiscordBot (https://desgraciamon.local, 1.0)")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    }
}
