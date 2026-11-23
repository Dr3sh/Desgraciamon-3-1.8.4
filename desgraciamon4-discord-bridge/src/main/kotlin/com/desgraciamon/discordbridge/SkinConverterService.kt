package com.desgraciamon.discordbridge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.regex.Pattern

object SkinConverterService {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val NAMEMC_PROFILE_REGEX = Pattern.compile("namemc\\.com/(?:profile|u)/([A-Za-z0-9_]{1,36})", Pattern.CASE_INSENSITIVE)
    private val NAMEMC_SKIN_REGEX = Pattern.compile("namemc\\.com/skin/([a-f0-9]{16,64})", Pattern.CASE_INSENSITIVE)
    private val TEXTURE_NET_REGEX = Pattern.compile("textures\\.minecraft\\.net/texture/([a-f0-9]{64})", Pattern.CASE_INSENSITIVE)

    data class SkinResult(
        val textureUrl: String,
        val textureHash: String,
        val model: String
    )

    fun convertFromBytes(imageBytes: ByteArray, fileName: String = "skin.png"): CompletableFuture<SkinResult> {
        return CompletableFuture.supplyAsync {
            uploadToMineskinMultipart(imageBytes, fileName)
        }
    }

    fun convertFromUrlOrText(input: String): CompletableFuture<SkinResult> {
        return CompletableFuture.supplyAsync {
            val trimmed = input.trim()

            // 1. Direct textures.minecraft.net URL
            val textureMatch = TEXTURE_NET_REGEX.matcher(trimmed)
            if (textureMatch.find()) {
                val hash = textureMatch.group(1)
                val fullUrl = "http://textures.minecraft.net/texture/$hash"
                return@supplyAsync SkinResult(fullUrl, hash, "classic")
            }

            // 2. NameMC Profile URL (e.g. namemc.com/profile/fkdr3sh)
            val nameMcProfileMatch = NAMEMC_PROFILE_REGEX.matcher(trimmed)
            if (nameMcProfileMatch.find()) {
                val userOrUuid = nameMcProfileMatch.group(1)
                return@supplyAsync resolveFromPlayerNameOrUuid(userOrUuid)
            }

            // 3. NameMC Skin URL (if 64-char hex hash)
            val nameMcSkinMatch = NAMEMC_SKIN_REGEX.matcher(trimmed)
            if (nameMcSkinMatch.find()) {
                val skinId = nameMcSkinMatch.group(1)
                if (skinId.length == 64) {
                    val fullUrl = "http://textures.minecraft.net/texture/$skinId"
                    return@supplyAsync SkinResult(fullUrl, skinId, "classic")
                }
            }

            // 4. Direct image link (Discord CDN, Imgur, etc.)
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                val bytes = downloadBytes(trimmed)
                return@supplyAsync uploadToMineskinMultipart(bytes, "skin.png")
            }

            // 5. Raw player username or UUID
            if (trimmed.matches(Regex("^[A-Za-z0-9_]{2,16}$"))) {
                return@supplyAsync resolveFromPlayerNameOrUuid(trimmed)
            }

            throw IllegalArgumentException("No se reconoció un enlace o imagen válida. Por favor sube el archivo .png de la skin o un enlace de perfil de NameMC.")
        }
    }

    private fun resolveFromPlayerNameOrUuid(identifier: String): SkinResult {
        val uuidStr = if (identifier.length <= 16) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/$identifier"))
                .header("User-Agent", "DesgraciamonSkinBot/1.0")
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                throw IllegalStateException("No se encontró el jugador de Minecraft: $identifier")
            }
            val json = JsonParser.parseString(resp.body()).asJsonObject
            json.get("id").asString
        } else {
            identifier.replace("-", "")
        }

        val sessionReq = HttpRequest.newBuilder()
            .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/$uuidStr"))
            .header("User-Agent", "DesgraciamonSkinBot/1.0")
            .timeout(Duration.ofSeconds(6))
            .GET()
            .build()
        val sessionResp = httpClient.send(sessionReq, HttpResponse.BodyHandlers.ofString())
        if (sessionResp.statusCode() != 200) {
            throw IllegalStateException("No se pudo obtener el perfil de Mojang para UUID: $uuidStr")
        }

        val profileJson = JsonParser.parseString(sessionResp.body()).asJsonObject
        val properties = profileJson.getAsJsonArray("properties") ?: throw IllegalStateException("Propiedades de skin no encontradas.")
        var encodedTextures = ""
        for (elem in properties) {
            val obj = elem.asJsonObject
            if (obj.get("name")?.asString == "textures") {
                encodedTextures = obj.get("value").asString
                break
            }
        }
        if (encodedTextures.isBlank()) {
            throw IllegalStateException("El jugador no tiene una skin configurada.")
        }

        val decoded = String(Base64.getDecoder().decode(encodedTextures), StandardCharsets.UTF_8)
        val texJson = JsonParser.parseString(decoded).asJsonObject
        val texturesObj = texJson.getAsJsonObject("textures") ?: throw IllegalStateException("Estructura de texturas inválida.")
        val skinObj = texturesObj.getAsJsonObject("SKIN") ?: throw IllegalStateException("El jugador no tiene skin.")

        val url = skinObj.get("url").asString
        val hash = url.substringAfterLast("/")
        val model = skinObj.getAsJsonObject("metadata")?.get("model")?.asString ?: "classic"

        return SkinResult(url, hash, model)
    }

    private fun uploadToMineskinMultipart(bytes: ByteArray, fileName: String): SkinResult {
        val boundary = "---MineskinBoundary" + UUID.randomUUID().toString().replace("-", "")
        val body = buildMultipartBody(boundary, "file", fileName, "image/png", bytes)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.mineskin.org/v2/queue"))
            .header("User-Agent", "DesgraciamonSkinBot/1.0")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..202) {
            throw IllegalStateException("Error al contactar con la API de MineSkin (HTTP ${response.statusCode()}): ${response.body()}")
        }

        val json = JsonParser.parseString(response.body()).asJsonObject
        val jobObj = json.getAsJsonObject("job") ?: throw IllegalStateException("Respuesta inesperada de MineSkin: ${response.body()}")
        var status = jobObj.get("status")?.asString ?: "unknown"
        var resultSkinUuid = jobObj.get("result")?.takeIf { !it.isJsonNull }?.asString

        // Poll if waiting/processing
        var attempts = 0
        val jobId = jobObj.get("id").asString
        while ((resultSkinUuid == null || status != "completed") && attempts < 10) {
            Thread.sleep(1200)
            attempts++
            val pollReq = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mineskin.org/v2/queue/$jobId"))
                .header("User-Agent", "DesgraciamonSkinBot/1.0")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
            val pollResp = httpClient.send(pollReq, HttpResponse.BodyHandlers.ofString())
            if (pollResp.statusCode() == 200) {
                val pollJson = JsonParser.parseString(pollResp.body()).asJsonObject
                val pollJob = pollJson.getAsJsonObject("job") ?: break
                status = pollJob.get("status")?.asString ?: status
                resultSkinUuid = pollJob.get("result")?.takeIf { !it.isJsonNull }?.asString
            }
        }

        if (resultSkinUuid.isNullOrBlank()) {
            throw IllegalStateException("MineSkin tardó demasiado en procesar la skin (estado: $status).")
        }

        // Fetch skin details
        val skinReq = HttpRequest.newBuilder()
            .uri(URI.create("https://api.mineskin.org/v2/skins/$resultSkinUuid"))
            .header("User-Agent", "DesgraciamonSkinBot/1.0")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val skinResp = httpClient.send(skinReq, HttpResponse.BodyHandlers.ofString())
        if (skinResp.statusCode() != 200) {
            throw IllegalStateException("No se pudo obtener el resultado final de la skin: ${skinResp.body()}")
        }

        val skinJson = JsonParser.parseString(skinResp.body()).asJsonObject
        val skinData = skinJson.getAsJsonObject("skin") ?: throw IllegalStateException("Estructura de skin inválida.")
        val textureObj = skinData.getAsJsonObject("texture") ?: throw IllegalStateException("Textura no encontrada en respuesta.")

        val fullUrl = textureObj.getAsJsonObject("url")?.get("skin")?.asString
            ?: "http://textures.minecraft.net/texture/" + (textureObj.getAsJsonObject("hash")?.get("skin")?.asString ?: "")
        val hash = textureObj.getAsJsonObject("hash")?.get("skin")?.asString ?: fullUrl.substringAfterLast("/")
        val variant = skinData.get("variant")?.asString ?: "classic"

        return SkinResult(fullUrl, hash, variant)
    }

    private fun downloadBytes(url: String): ByteArray {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "DesgraciamonSkinBot/1.0")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() != 200) {
            throw IllegalStateException("No se pudo descargar la imagen (HTTP ${resp.statusCode()})")
        }
        return resp.body()
    }

    private fun buildMultipartBody(boundary: String, fieldName: String, fileName: String, contentType: String, fileBytes: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\nContent-Type: $contentType\r\n\r\n"
        baos.write(prefix.toByteArray(StandardCharsets.UTF_8))
        baos.write(fileBytes)
        baos.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
        return baos.toByteArray()
    }

    fun buildEmbed(result: SkinResult, user: User, linkedMcName: String? = null): MessageEmbed {
        val isSlim = result.model.equals("slim", ignoreCase = true)
        val modelArg = if (isSlim) "slim" else "classic"
        val modelDisplay = if (isSlim) "Alex (Slim - 3px)" else "Steve (Classic - 4px)"
        val renderUrl = "https://visage.surgeplay.com/full/512/${result.textureHash}"
        val headUrl = "https://visage.surgeplay.com/face/128/${result.textureHash}"

        val commandStr = "/skin set web $modelArg \"${result.textureUrl}\""

        val descriptionText = if (linkedMcName != null) {
            "Tu skin ha sido convertida exitosamente.\n" +
            "Puedes aplicarla directamente con el botón de abajo a **$linkedMcName** o copiar el comando:"
        } else {
            "Tu skin ha sido convertida exitosamente.\n" +
            "Copia el comando de abajo y pégalo directamente en Minecraft:\n" +
            "*(Vincula tu cuenta con `/discord vincular` en el juego para aplicártelas con 1 clic)*"
        }

        return EmbedBuilder()
            .setTitle("Skin Convertida Exitosamente")
            .setColor(0x5BC0DE)
            .setThumbnail(headUrl)
            .setImage(renderUrl)
            .setDescription(descriptionText)
            .addField("Comando para el juego (SkinRestorer)", "```\n$commandStr\n```", false)
            .addField("Modelo de brazos", modelDisplay, true)
            .addField("Hash Mojang", "`" + result.textureHash.take(16) + "...`", true)
            .setFooter("Solicitado por ${user.effectiveName} • Desgraciamon Skins", user.effectiveAvatarUrl)
            .build()
    }
}
