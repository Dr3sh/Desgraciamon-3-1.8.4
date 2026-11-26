package com.desgraciamon.badges

import com.desgraciamon.announcements.BulletinStore
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AdminAuth {
    const val ADMIN_BADGE_ID = "admin"

    private val adminModeEnabled = ConcurrentHashMap.newKeySet<UUID>()

    @JvmStatic
    fun hasAdminBadge(uuid: UUID?): Boolean {
        if (uuid == null || !BadgeData.isLoaded) return false
        return BadgeData.hasUnlocked(uuid, ADMIN_BADGE_ID)
    }

    @JvmStatic
    fun isAdmin(player: ServerPlayer?): Boolean {
        return player != null && hasAdminBadge(player.uuid)
    }

    @JvmStatic
    fun isAdminSource(source: CommandSourceStack): Boolean {
        val player = source.player
        if (player != null) {
            return isAdminModeActive(player)
        }
        return source.hasPermission(2)
    }

    @JvmStatic
    fun canManageAdminBadge(source: CommandSourceStack): Boolean {
        val player = source.player
        if (player != null) {
            return isAdminModeActive(player)
        }
        return source.hasPermission(4)
    }

    @JvmStatic
    fun isAdminModeActive(player: ServerPlayer?): Boolean {
        if (player == null) return false
        if (!hasAdminBadge(player.uuid)) {
            adminModeEnabled.remove(player.uuid)
            return false
        }
        return adminModeEnabled.contains(player.uuid)
    }

    @JvmStatic
    fun toggleAdminMode(player: ServerPlayer): Boolean {
        if (!hasAdminBadge(player.uuid)) {
            adminModeEnabled.remove(player.uuid)
            return false
        }
        val active = if (adminModeEnabled.contains(player.uuid)) {
            adminModeEnabled.remove(player.uuid)
            false
        } else {
            adminModeEnabled.add(player.uuid)
            true
        }
        runCatching {
            val state = BulletinStore.stateFor(player)
            state.adminMode = active
            BulletinStore.sync(player)
        }
        refreshPermissions(player.server, player)
        return active
    }

    @JvmStatic
    fun clearAdminMode(uuid: UUID) {
        adminModeEnabled.remove(uuid)
    }

    @JvmStatic
    fun clearAll() {
        adminModeEnabled.clear()
    }

    @JvmStatic
    fun onPlayerJoin(server: MinecraftServer, player: ServerPlayer) {
        if (!server.isDedicatedServer &&
            server.isSingleplayerOwner(player.gameProfile) &&
            server.worldData.isAllowCommands &&
            !BadgeData.hasAnyAdmin()
        ) {
            BadgeData.grant(player.uuid, ADMIN_BADGE_ID)
        }
        if (!hasAdminBadge(player.uuid)) {
            adminModeEnabled.remove(player.uuid)
        }
        refreshPermissions(server, player)
    }

    @JvmStatic
    fun onPlayerDisconnect(uuid: UUID) {
        adminModeEnabled.remove(uuid)
    }

    @JvmStatic
    fun refreshPermissions(server: MinecraftServer, player: ServerPlayer) {
        if (!hasAdminBadge(player.uuid)) {
            adminModeEnabled.remove(player.uuid)
            runCatching {
                BulletinStore.stateFor(player).adminMode = false
                BulletinStore.sync(player)
            }
        }
        server.playerList.sendPlayerPermissionLevel(player)
        server.commands.sendCommands(player)
    }
}
