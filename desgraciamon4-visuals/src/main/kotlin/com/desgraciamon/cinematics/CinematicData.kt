package com.desgraciamon.cinematics

import net.minecraft.world.phys.Vec3
import kotlin.math.*

enum class EasingMode {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT;

    fun apply(t: Double): Double {
        val clamped = t.coerceIn(0.0, 1.0)
        return when (this) {
            LINEAR -> clamped
            EASE_IN -> clamped * clamped * clamped
            EASE_OUT -> 1.0 - (1.0 - clamped).pow(3)
            EASE_IN_OUT -> if (clamped < 0.5) 4.0 * clamped * clamped * clamped else 1.0 - (-2.0 * clamped + 2.0).pow(3) / 2.0
        }
    }
}

enum class TransitionMode {
    DIRECT,
    FADE,
    PLAYER_LERP_IF_NEAR
}

data class Keyframe(
    var id: Int = 1,
    var x: Double = 0.0,
    var y: Double = 64.0,
    var z: Double = 0.0,
    var yaw: Float = 0f,
    var pitch: Float = 0f,
    var roll: Float = 0f,
    var durationSeconds: Double = 4.0,
    var easing: EasingMode = EasingMode.EASE_IN_OUT,
    var animationTrigger: String? = null,
    var shaderEffect: String? = null,
    var shaderDurationSeconds: Double = 2.5,
    var soundTrigger: String? = null,
    var subtitle: String? = null,
    var isSeasonJump: Boolean = false,
    var fadeColor: String? = null,
    var fadeDurationSeconds: Double = 2.0,
    var lookAtX: Double? = null,
    var lookAtY: Double? = null,
    var lookAtZ: Double? = null,
    var lookAtEntity: String? = null,
) {
    fun position(): Vec3 = Vec3(x, y, z)
    fun hasLookAtPoint(): Boolean = lookAtX != null && lookAtY != null && lookAtZ != null
    fun lookAtPoint(): Vec3? = if (hasLookAtPoint()) Vec3(lookAtX!!, lookAtY!!, lookAtZ!!) else null
}

data class CinematicRoute(
    var name: String = "nueva_cinematica",
    var description: String = "Ruta cinemática para evento de servidor",
    var dimension: String = "minecraft:overworld",
    var letterbox: Boolean = true,
    var hideHud: Boolean = true,
    var freezePlayers: Boolean = true,
    var loop: Boolean = false,
    var skippable: Boolean = false,
    var entryMode: TransitionMode = TransitionMode.FADE,
    var exitMode: TransitionMode = TransitionMode.FADE,
    var transitionFadeColor: String = "#000000",
    var transitionFadeSeconds: Double = 0.8,
    var lerpNearDistance: Double = 12.0,
    var musicSound: String? = null,
    var musicVolume: Float = 1.0f,
    var musicPitch: Float = 1.0f,
    var keyframes: MutableList<Keyframe> = mutableListOf(),
) {
    fun totalDurationSeconds(): Double {
        if (keyframes.size < 2) return 0.0
        return keyframes.dropLast(1).sumOf { it.durationSeconds }
    }

    fun collectRequiredAnimations(): List<String> {
        return keyframes.mapNotNull { it.animationTrigger?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    }

    private fun segmentAt(timeSec: Double): Triple<Int, Double, Double> {
        val totalTime = totalDurationSeconds()
        val clampedTime = if (loop && totalTime > 0.0) ((timeSec % totalTime) + totalTime) % totalTime else timeSec.coerceIn(0.0, totalTime)

        var accumulated = 0.0
        var segmentIndex = 0
        for (i in 0 until keyframes.size - 1) {
            val dur = keyframes[i].durationSeconds
            if (clampedTime <= accumulated + dur || i == keyframes.size - 2) {
                segmentIndex = i
                break
            }
            accumulated += dur
        }
        val k1 = keyframes[segmentIndex]
        val segDuration = k1.durationSeconds.coerceAtLeast(0.05)
        val rawT = ((clampedTime - accumulated) / segDuration).coerceIn(0.0, 1.0)
        val easedT = k1.easing.apply(rawT)
        return Triple(segmentIndex, rawT, easedT)
    }

    /**
     * Evalúa la posición (X, Y, Z) en el segundo exacto `timeSec`
     * usando interpolación Catmull-Rom para curvas orgánicas.
     */
    fun evaluatePosition(timeSec: Double): Vec3 {
        if (keyframes.isEmpty()) return Vec3.ZERO
        if (keyframes.size == 1) return keyframes[0].position()

        val (segmentIndex, _, easedT) = segmentAt(timeSec)
        val k1 = keyframes[segmentIndex]
        val k2 = keyframes[segmentIndex + 1]

        val p0 = controlKeyframe(segmentIndex - 1, k1).position()
        val p1 = k1.position()
        val p2 = k2.position()
        val p3 = controlKeyframe(segmentIndex + 2, k2).position()

        return catmullRom(p0, p1, p2, p3, easedT)
    }

    /**
     * Evalúa orientación (Yaw, Pitch, Roll) usando Catmull-Rom suave con envoltura de ángulo más corto.
     */
    fun evaluateRotationFull(timeSec: Double): Triple<Float, Float, Float> {
        if (keyframes.isEmpty()) return Triple(0f, 0f, 0f)
        if (keyframes.size == 1) return Triple(keyframes[0].yaw, keyframes[0].pitch, keyframes[0].roll)

        val (segmentIndex, _, easedT) = segmentAt(timeSec)
        val k1 = keyframes[segmentIndex]
        val k2 = keyframes[segmentIndex + 1]
        val k0 = controlKeyframe(segmentIndex - 1, k1)
        val k3 = controlKeyframe(segmentIndex + 2, k2)

        // Desenvolver ángulos yaw respecto a k1.yaw para evitar giros de 360° en Catmull-Rom
        val y1 = k1.yaw.toDouble()
        val y0 = y1 + shortestAngleDiff(y1, k0.yaw.toDouble())
        val y2 = y1 + shortestAngleDiff(y1, k2.yaw.toDouble())
        val y3 = y2 + shortestAngleDiff(y2, k3.yaw.toDouble())

        val yaw = catmullRomScalar(y0, y1, y2, y3, easedT).toFloat()
        val pitch = catmullRomScalar(
            k0.pitch.toDouble(),
            k1.pitch.toDouble(),
            k2.pitch.toDouble(),
            k3.pitch.toDouble(),
            easedT
        ).toFloat().coerceIn(-90f, 90f)
        val roll = catmullRomScalar(
            k0.roll.toDouble(),
            k1.roll.toDouble(),
            k2.roll.toDouble(),
            k3.roll.toDouble(),
            easedT
        ).toFloat()

        return Triple(yaw, pitch, roll)
    }

    fun evaluateRotation(timeSec: Double): Pair<Float, Float> {
        val (yaw, pitch, _) = evaluateRotationFull(timeSec)
        return Pair(yaw, pitch)
    }

    fun evaluateLookAtTarget(timeSec: Double): Pair<Vec3?, String?> {
        if (keyframes.isEmpty()) return null to null
        if (keyframes.size == 1) return keyframes[0].lookAtPoint() to keyframes[0].lookAtEntity

        val (segmentIndex, _, easedT) = segmentAt(timeSec)
        val k1 = keyframes[segmentIndex]
        val k2 = keyframes[segmentIndex + 1]

        if (!k1.lookAtEntity.isNullOrBlank()) {
            return null to k1.lookAtEntity
        }
        val p1 = k1.lookAtPoint()
        val p2 = k2.lookAtPoint()
        val point = when {
            p1 != null && p2 != null -> p1.lerp(p2, easedT)
            p1 != null -> p1
            else -> null
        }
        return point to null
    }

    private fun controlKeyframe(idx: Int, fallback: Keyframe): Keyframe {
        if (!loop || keyframes.size < 3) {
            return keyframes.getOrElse(idx.coerceIn(0, keyframes.lastIndex)) { fallback }
        }
        val wrapped = ((idx % keyframes.size) + keyframes.size) % keyframes.size
        return keyframes[wrapped]
    }

    companion object {
        fun catmullRom(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double): Vec3 {
            return Vec3(
                catmullRomScalar(p0.x, p1.x, p2.x, p3.x, t),
                catmullRomScalar(p0.y, p1.y, p2.y, p3.y, t),
                catmullRomScalar(p0.z, p1.z, p2.z, p3.z, t)
            )
        }

        fun catmullRomScalar(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
            val t2 = t * t
            val t3 = t2 * t
            return 0.5 * (
                (2.0 * p1) +
                    (-p0 + p2) * t +
                    (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
                    (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
                )
        }

        fun shortestAngleDiff(from: Double, to: Double): Double {
            var diff = (to - from) % 360.0
            if (diff > 180.0) diff -= 360.0
            if (diff < -180.0) diff += 360.0
            return diff
        }

        fun lerpAngle(a: Float, b: Float, t: Float): Float {
            return a + shortestAngleDiff(a.toDouble(), b.toDouble()).toFloat() * t
        }
    }
}
