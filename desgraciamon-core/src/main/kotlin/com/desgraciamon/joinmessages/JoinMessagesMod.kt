package com.desgraciamon.joinmessages

import com.desgraciamon.badges.BadgeData
import com.desgraciamon.badges.RichText
import com.desgraciamon.badges.TextCatalog
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class JoinMessagesMod : ModInitializer {
    override fun onInitialize() {
        loadConfig()
        LOGGER.info("Desgraciamon Join Messages inicializado correctamente con soporte de insignias y formato de chat.")
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("DesgraciamonJoinMessages")
        private val configPath: Path = Path.of("config", "desgraciamon4-join-messages", "messages.properties")
        private var joinTemplate = "{player} se conectó"
        private var joinColor = 0x3E74D1
        private var leaveTemplate = "{player} se desconectó"
        private var leaveColor = 0x9E9E9E

        fun loadConfig() {
            try {
                Files.createDirectories(configPath.parent)
                val properties = Properties()
                if (Files.exists(configPath)) {
                    Files.newBufferedReader(configPath, StandardCharsets.UTF_8).use(properties::load)
                }

                val rawJoin = properties.getProperty("join.message", joinTemplate)
                val rawJoinColor = properties.getProperty("join.color")
                val rawLeave = properties.getProperty("leave.message", leaveTemplate)
                val rawLeaveColor = properties.getProperty("leave.color")

                joinTemplate = if (rawJoin == "{player} se ha conectado") "{player} se conectó" else rawJoin
                joinColor = if (rawJoinColor.equals("#55FF55", ignoreCase = true)) 0x3E74D1 else parseColor(rawJoinColor, joinColor)
                leaveTemplate = if (rawLeave == "{player} se ha desconectado") "{player} se desconectó" else rawLeave
                leaveColor = if (rawLeaveColor.equals("#FF5555", ignoreCase = true)) 0x9E9E9E else parseColor(rawLeaveColor, leaveColor)

                properties.setProperty("join.message", joinTemplate)
                properties.setProperty("join.color", "#%06X".format(joinColor))
                properties.setProperty("leave.message", leaveTemplate)
                properties.setProperty("leave.color", "#%06X".format(leaveColor))
                Files.newBufferedWriter(configPath, StandardCharsets.UTF_8).use { writer ->
                    properties.store(writer, "Personalizar mensajes de conexion y desconexion. Variables: {player}, {badges}")
                }
            } catch (e: Exception) {
                LOGGER.error("Error al cargar la configuracion de join/leave messages", e)
            }
        }

        private fun parseColor(value: String?, fallback: Int): Int {
            val raw = value?.trim() ?: return fallback
            val hex = raw.removePrefix("#")
            val normalized = when {
                hex.matches(Regex("[0-9a-fA-F]{3}")) -> hex.map { "$it$it" }.joinToString("")
                hex.matches(Regex("[0-9a-fA-F]{6}")) -> hex
                else -> return fallback
            }
            return normalized.toInt(16)
        }

        fun joinMessage(player: ServerPlayer): Component = format(joinTemplate, joinColor, player)

        fun leaveMessage(player: ServerPlayer): Component = format(leaveTemplate, leaveColor, player)

        private fun format(template: String, defaultColor: Int, player: ServerPlayer): Component {
            val visible = runCatching { BadgeData.visibleBadges(player.uuid) }.getOrDefault(emptyList())
            val badgesComp: MutableComponent = if (visible.isNotEmpty()) {
                runCatching { TextCatalog.formatBadgesSuffix(visible, withHover = true) }.getOrDefault(Component.empty())
            } else {
                Component.empty()
            }

            val customNameComp = com.desgraciamon.customnames.CustomNamesMod.getNameComponent(player.uuid, false)
            val baseNameComp = customNameComp ?: Component.literal(player.gameProfile.name)
            val nameStyle = Style.EMPTY
                .withColor(TextColor.fromRgb(defaultColor))
                .withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tell ${player.gameProfile.name} "))
                .withHoverEvent(
                    HoverEvent(
                        HoverEvent.Action.SHOW_ENTITY,
                        HoverEvent.EntityTooltipInfo(player.type, player.uuid, Component.literal(player.gameProfile.name))
                    )
                )
            val playerNameComp = if (customNameComp != null) {
                baseNameComp.copy().withStyle {
                    it.withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tell ${player.gameProfile.name} "))
                        .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_ENTITY, HoverEvent.EntityTooltipInfo(player.type, player.uuid, Component.literal(player.gameProfile.name))))
                }
            } else {
                baseNameComp.copy().withStyle(nameStyle)
            }

            val effectiveTemplate = if (visible.isEmpty()) {
                template.replace(" {badges}", "").replace("{badges} ", "").replace("{badges}", "")
            } else {
                if (template.contains("{badges}")) {
                    template
                } else {
                    "$template {badges}"
                }
            }

            val colorHex = "#%06X".format(defaultColor)
            val styledTemplate = if (!effectiveTemplate.contains("<color=", ignoreCase = true) && !effectiveTemplate.contains("<prism=", ignoreCase = true)) {
                "<color=$colorHex>$effectiveTemplate</color>"
            } else {
                effectiveTemplate
            }

            return RichText.parse(
                styledTemplate,
                mapOf(
                    "badges" to badgesComp,
                    "player" to playerNameComp
                )
            )
        }
    }
}
