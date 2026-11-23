package com.desgraciamon.showdownarena

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

data class SpotlightSample(
	val inward: Float,
	val lat: Float,
	val r: Float,
	val g: Float,
	val b: Float,
	val intensity: Float,
	val widthMult: Float
)

object SpotlightEffectEvaluator {
	private const val TAU = (2.0 * PI)
	private const val BPM_HZ = 2.0 // 120 BPM = 2 beats/sec

	fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
		val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
		return t * t * (3f - 2f * t)
	}

	fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

	fun lerpSample(a: SpotlightSample, b: SpotlightSample, rawT: Float): SpotlightSample {
		val t = smoothStep(0f, 1f, rawT)
		return SpotlightSample(
			inward = lerp(a.inward, b.inward, t),
			lat = lerp(a.lat, b.lat, t),
			r = lerp(a.r, b.r, t),
			g = lerp(a.g, b.g, t),
			b = lerp(a.b, b.b, t),
			intensity = lerp(a.intensity, b.intensity, t),
			widthMult = lerp(a.widthMult, b.widthMult, t)
		)
	}

	fun evaluateBlended(
		prevEffectId: String,
		currEffectId: String,
		blendT: Float,
		sIdx: Int,
		numSpotlights: Int,
		timeSec: Double,
		minR: Float,
		c1R: Float,
		c1G: Float,
		c1B: Float,
		c2R: Float,
		c2G: Float,
		c2B: Float
	): SpotlightSample {
		val curr = evaluate(
			currEffectId, sIdx, numSpotlights, timeSec, minR,
			c1R, c1G, c1B, c2R, c2G, c2B
		)
		if (blendT >= 0.999f || prevEffectId == currEffectId) {
			return curr
		}
		val prev = evaluate(
			prevEffectId, sIdx, numSpotlights, timeSec, minR,
			c1R, c1G, c1B, c2R, c2G, c2B
		)
		return lerpSample(prev, curr, blendT)
	}

	fun evaluate(
		effectId: String,
		sIdx: Int,
		numSpotlights: Int,
		timeSec: Double,
		minR: Float,
		c1R: Float,
		c1G: Float,
		c1B: Float,
		c2R: Float,
		c2G: Float,
		c2B: Float
	): SpotlightSample {
		val n = max(1, numSpotlights)
		val frac = sIdx.toDouble() / n.toDouble()
		val theta = frac * TAU
		val isEven = (sIdx and 1) == 0
		val baseR = if (isEven) c1R else c2R
		val baseG = if (isEven) c1G else c2G
		val baseB = if (isEven) c1B else c2B

		val beatPhase = (timeSec * BPM_HZ) % 1.0
		val beatIndex = floor(timeSec * BPM_HZ).toInt()

		return when (effectId) {
			// --- A. TWO COLORS (27) ---
			"centro" -> {
				val cycle = (sin(timeSec * 0.65) * 0.5 + 0.5).toFloat()
				val highInward = minR * 0.25f
				val lowInward = minR * 0.85f
				val inward = lerp(highInward, lowInward, cycle)
				val lat = (sin(timeSec * 0.9 + theta) * 0.14).toFloat()
				val pulse = 0.82f + 0.18f * sin(timeSec * 2.2).toFloat()
				SpotlightSample(inward, lat, baseR, baseG, baseB, pulse, 1.05f)
			}

			"fan" -> {
				val s = sin(timeSec * 1.4).toFloat()
				val inward = minR * (0.28f - 0.58f * s)
				SpotlightSample(inward, 0f, baseR, baseG, baseB, 0.92f, 1.0f)
			}

			"sweep" -> {
				val sway = sin(timeSec * 1.15)
				val dirX = sway * 0.95
				val dirZ = cos(timeSec * 0.55) * 0.35
				val inward = (-(dirX * cos(theta) + dirZ * sin(theta)) * minR * 0.72).toFloat() + minR * 0.18f
				val lat = ((-dirX * sin(theta) + dirZ * cos(theta)) * 0.85).toFloat()
				SpotlightSample(inward, lat, baseR, baseG, baseB, 0.92f, 1.0f)
			}

			"flyout" -> {
				val p = (timeSec * 0.75) % 1.0
				if (p < 0.28) {
					val k = (p / 0.28).toFloat()
					val intensity = 0.30f + 0.15f * k
					SpotlightSample(minR * 0.72f, 0f, baseR, baseG, baseB, intensity, 0.85f)
				} else {
					val k = ((p - 0.28) / 0.72).toFloat()
					val ease = 1f - (1f - k).pow(3)
					val inward = lerp(minR * 0.72f, -minR * 0.55f, ease)
					val intensity = (1f - k * 0.85f).coerceIn(0.12f, 1f)
					SpotlightSample(inward, 0f, baseR, baseG, baseB, intensity, 1.15f)
				}
			}

			"ballyhoo" -> {
				val inward = minR * (0.26f + 0.42f * sin(timeSec * 1.7 + theta * 2.0).toFloat())
				val lat = (0.72 * cos(timeSec * 3.4 + theta)).toFloat()
				val pulse = 0.78f + 0.22f * sin(timeSec * 2.5 + theta).toFloat()
				SpotlightSample(inward, lat, baseR, baseG, baseB, pulse, 0.95f)
			}

			"xcross" -> {
				val leftHalf = sin(theta) >= 0.0
				val r = if (leftHalf) c1R else c2R
				val g = if (leftHalf) c1G else c2G
				val b = if (leftHalf) c1B else c2B
				val breathe = 0.82f + 0.14f * sin(timeSec * 1.1).toFloat()
				val inward = minR * breathe
				val lat = (0.18 * sin(timeSec * 1.3 + (if (leftHalf) 0.0 else PI))).toFloat()
				SpotlightSample(inward, lat, r, g, b, 0.92f, 1.05f)
			}

			"converge" -> {
				val targetX = cos(timeSec * 0.9) * 0.65
				val targetZ = sin(timeSec * 1.25) * 0.65
				val relX = targetX - cos(theta) * 0.95
				val relZ = targetZ - sin(theta) * 0.95
				val inward = (-(relX * cos(theta) + relZ * sin(theta)) * minR * 0.52).toFloat()
				val lat = ((-relX * sin(theta) + relZ * cos(theta)) * 0.55).toFloat()
				SpotlightSample(inward, lat, baseR, baseG, baseB, 0.96f, 0.92f)
			}

			"chase" -> {
				val head = ((timeSec * 4.5) % n.toDouble())
				val dist = ringDist(sIdx.toDouble(), head, n.toDouble())
				val intensity = when {
					dist < 0.55 -> 1.0f
					dist < 1.55 -> 0.30f
					else -> 0.04f
				}
				val inward = minR * (0.28f + 0.25f * sin(timeSec * 1.2).toFloat())
				SpotlightSample(inward, 0f, baseR, baseG, baseB, intensity, if (dist < 0.55) 1.2f else 0.9f)
			}

			"comet" -> {
				val head = ((timeSec * 3.2) % n.toDouble())
				val trail = ringBehind(sIdx.toDouble(), head, n.toDouble())
				val halfRing = max(2.0, n / 2.0)
				val intensity = if (trail <= halfRing) {
					(1.0 - trail / halfRing).pow(1.6).toFloat().coerceIn(0.05f, 1f)
				} else {
					0.04f
				}
				val inward = minR * 0.32f
				val lat = (0.22 * sin(timeSec * 1.5 + theta)).toFloat()
				SpotlightSample(inward, lat, c1R, c1G, c1B, intensity, 1.0f + 0.3f * intensity)
			}

			"dual_comet" -> {
				val head1 = ((timeSec * 3.0) % n.toDouble())
				val head2 = (head1 + n * 0.5) % n.toDouble()
				val t1 = ringBehind(sIdx.toDouble(), head1, n.toDouble())
				val t2 = ringBehind(sIdx.toDouble(), head2, n.toDouble())
				val span = max(2.0, n * 0.38)
				val i1 = if (t1 <= span) (1.0 - t1 / span).pow(1.5).toFloat() else 0f
				val i2 = if (t2 <= span) (1.0 - t2 / span).pow(1.5).toFloat() else 0f
				val useFirst = i1 >= i2
				val intensity = max(i1, i2).coerceIn(0.05f, 1f)
				val r = if (useFirst) c1R else c2R
				val g = if (useFirst) c1G else c2G
				val b = if (useFirst) c1B else c2B
				SpotlightSample(minR * 0.34f, 0.2f, r, g, b, intensity, 1.05f)
			}

			"wave" -> {
				val phase = timeSec * 2.8 - 2.0 * theta
				val w = (sin(phase) * 0.5 + 0.5).toFloat()
				val inward = minR * lerp(-0.15f, 0.65f, w)
				val intensity = 0.22f + 0.78f * w
				SpotlightSample(inward, 0f, baseR, baseG, baseB, intensity, 0.85f + 0.35f * w)
			}

			"mirror" -> {
				val right = cos(theta) >= 0.0
				val sign = if (right) 1f else -1f
				val osc = sin(timeSec * 1.6).toFloat()
				val inward = minR * (0.30f + 0.35f * cos(timeSec * 1.1).toFloat())
				val lat = sign * osc * 0.85f
				val r = if (right) c1R else c2R
				val g = if (right) c1G else c2G
				val b = if (right) c1B else c2B
				SpotlightSample(inward, lat, r, g, b, 0.92f, 1.0f)
			}

			"color_flip" -> {
				val flip = (beatIndex and 1) == 1
				val useC1 = if (flip) !isEven else isEven
				val r = if (useC1) c1R else c2R
				val g = if (useC1) c1G else c2G
				val b = if (useC1) c1B else c2B
				val sign = if (useC1) 1f else -1f
				val punch = exp(-beatPhase * 4.5).toFloat()
				val inward = minR * (0.28f + 0.36f * sign)
				SpotlightSample(inward, sign * 0.22f, r, g, b, 0.65f + 0.35f * punch, 1.0f)
			}

			"vortex" -> {
				val inward = minR * (0.38f + 0.14f * sin(timeSec * 1.3 + theta).toFloat())
				val lat = (0.78 + 0.18 * sin(timeSec * 1.8)).toFloat()
				val pulse = 0.75f + 0.25f * sin(timeSec * 3.0 - theta * 2.0).toFloat()
				SpotlightSample(inward, lat, baseR, baseG, baseB, pulse, 0.95f)
			}

			"heartbeat" -> {
				val cycle = (timeSec * 1.05) % 1.0
				val p1 = exp(-((cycle - 0.14) / 0.06).pow(2)).toFloat()
				val p2 = exp(-((cycle - 0.30) / 0.075).pow(2)).toFloat()
				val pulse = (p1 + 0.88f * p2).coerceIn(0f, 1f)
				val inward = minR * lerp(0.55f, -0.25f, pulse)
				val intensity = 0.12f + 0.88f * pulse
				SpotlightSample(inward, 0f, baseR, baseG, baseB, intensity, 0.85f + 0.45f * pulse)
			}

			"bump" -> {
				val decay = exp(-beatPhase * 5.2).toFloat()
				val whiteMix = if (beatPhase < 0.08) 0.78f else 0f
				val r = lerp(baseR, 1f, whiteMix)
				val g = lerp(baseG, 1f, whiteMix)
				val b = lerp(baseB, 1f, whiteMix)
				val inward = minR * (0.25f + 0.30f * (1f - decay))
				SpotlightSample(inward, 0f, r, g, b, (0.18f + 0.82f * decay), 0.9f + 0.45f * decay)
			}

			"blackout" -> {
				val cycle = (timeSec * 0.65) % 1.0
				if (cycle < 0.45) {
					SpotlightSample(minR * 0.3f, 0f, baseR, baseG, baseB, 0.02f, 1.0f)
				} else {
					val k = ((cycle - 0.45) / 0.55).toFloat()
					val blinder = exp(-k * 3.6f)
					val r = lerp(baseR, 1f, blinder * 0.65f)
					val g = lerp(baseG, 1f, blinder * 0.65f)
					val b = lerp(baseB, 1f, blinder * 0.65f)
					SpotlightSample(minR * 0.12f, 0f, r, g, b, (0.05f + 0.95f * blinder), 1.65f)
				}
			}

			"cathedral" -> {
				val stagger = (sin(timeSec * 1.9 - theta * 1.5) * 0.5 + 0.5).toFloat()
				val intensity = 0.25f + 0.75f * stagger
				SpotlightSample(0f, 0f, baseR, baseG, baseB, intensity, 0.58f)
			}

			"helix" -> {
				val sign = if (isEven) 1f else -1f
				val phase = timeSec * 2.1 + theta
				val inward = minR * (0.28f + 0.35f * sign * sin(phase).toFloat())
				val lat = sign * 0.55f * cos(phase).toFloat()
				SpotlightSample(inward, lat, baseR, baseG, baseB, 0.92f, 0.92f)
			}

			"shockwave" -> {
				val p = beatPhase.toFloat()
				val inward = lerp(minR * 0.78f, -minR * 0.55f, p)
				val whiteMix = (1f - p * 3.2f).coerceIn(0f, 1f)
				val r = lerp(baseR, 1f, whiteMix)
				val g = lerp(baseG, 1f, whiteMix)
				val b = lerp(baseB, 1f, whiteMix)
				val intensity = (1f - p).pow(1.25f).coerceIn(0.08f, 1f)
				SpotlightSample(inward, 0f, r, g, b, intensity, 1.15f)
			}

			"pendulum" -> {
				val sway = sin(timeSec * 0.95).toFloat()
				val mixT = sway * 0.5f + 0.5f
				val r = lerp(c1R, c2R, mixT)
				val g = lerp(c1G, c2G, mixT)
				val b = lerp(c1B, c2B, mixT)
				SpotlightSample(minR * 0.22f, sway * 0.92f, r, g, b, 0.92f, 1.35f)
			}

			"sparkle" -> {
				val seed = sIdx * 17.31
				val s1 = sin(timeSec * (2.8 + (sIdx % 5) * 0.45) + seed)
				val s2 = cos(timeSec * (1.9 + (sIdx % 3) * 0.6) - seed * 0.7)
				val intensity = ((s1 * s2) * 0.5 + 0.5).pow(1.4).toFloat().coerceIn(0.06f, 1f)
				val inward = minR * (0.25f + 0.25f * sin(timeSec * 0.9 + seed).toFloat())
				val lat = (0.28 * cos(timeSec * 1.1 + seed)).toFloat()
				SpotlightSample(inward, lat, baseR, baseG, baseB, intensity, 0.85f)
			}

			"prism_split" -> {
				val front = sin(theta) >= 0.0
				val r = if (front) c1R else c2R
				val g = if (front) c1G else c2G
				val b = if (front) c1B else c2B
				val osc = sin(timeSec * 1.35).toFloat()
				val inward = minR * (if (front) 0.48f * osc + 0.25f else -0.48f * osc + 0.25f)
				SpotlightSample(inward, if (front) 0.22f else -0.22f, r, g, b, 0.94f, 1.08f)
			}

			"pingpong" -> {
				val cycle = (timeSec * 0.95) % 2.0
				val bounceCount = floor(timeSec * 0.95).toInt()
				val posNorm = if (cycle < 1.0) cycle else (2.0 - cycle)
				val targetIdx = posNorm * (n - 1).coerceAtLeast(1)
				val d = abs(sIdx - targetIdx)
				val intensity = exp(-(d * d) / 1.8).toFloat().coerceIn(0.05f, 1f)
				val useC1 = (bounceCount and 1) == 0
				val r = if (useC1) c1R else c2R
				val g = if (useC1) c1G else c2G
				val b = if (useC1) c1B else c2B
				SpotlightSample(minR * 0.30f, 0f, r, g, b, intensity, 1.1f)
			}

			"crown" -> {
				val breath = (sin(timeSec * 1.8) * 0.5 + 0.5).toFloat()
				if (isEven) {
					val inward = minR * lerp(-0.15f, -0.45f, breath)
					SpotlightSample(inward, 0f, c1R, c1G, c1B, 0.85f + 0.15f * breath, 1.45f)
				} else {
					val inward = minR * lerp(0.35f, 0.65f, breath)
					SpotlightSample(inward, 0f, c2R, c2G, c2B, 0.90f, 0.65f)
				}
			}

			"cage" -> {
				val activeEven = (beatIndex and 1) == 0
				val active = if (activeEven) isEven else !isEven
				val lat = if (isEven) 0.65f else -0.65f
				val inward = minR * 0.62f
				val intensity = if (active) 0.96f else 0.18f
				SpotlightSample(inward, lat, baseR, baseG, baseB, intensity, 0.52f)
			}

			"meteor" -> {
				val period = 1.6
				val offset = ((sIdx * 0.6180339887) % 1.0) * period
				val p = ((timeSec + offset) % period) / period
				val burst = if (p < 0.25) exp(-((p - 0.08) / 0.07).pow(2)).toFloat() else 0.05f
				val inward = minR * lerp(0.35f, -0.35f, (p / 0.25).coerceIn(0.0, 1.0).toFloat())
				SpotlightSample(inward, 0.12f, baseR, baseG, baseB, burst, 0.85f)
			}

			// --- B. RGB (22) ---
			"rgb_comet" -> {
				val head = ((timeSec * 3.2) % n.toDouble())
				val trail = ringBehind(sIdx.toDouble(), head, n.toDouble())
				val span = max(2.0, n / 2.0)
				val intensity = if (trail <= span) (1.0 - trail / span).pow(1.6).toFloat().coerceIn(0.05f, 1f) else 0.04f
				val hue = ((timeSec * 0.28 + frac) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				SpotlightSample(minR * 0.32f, 0.20f, r, g, b, intensity, 1.05f + 0.25f * intensity)
			}

			"rgb_dual_comet" -> {
				val head1 = ((timeSec * 3.0) % n.toDouble())
				val head2 = (head1 + n * 0.5) % n.toDouble()
				val t1 = ringBehind(sIdx.toDouble(), head1, n.toDouble())
				val t2 = ringBehind(sIdx.toDouble(), head2, n.toDouble())
				val span = max(2.0, n * 0.38)
				val i1 = if (t1 <= span) (1.0 - t1 / span).pow(1.5).toFloat() else 0f
				val i2 = if (t2 <= span) (1.0 - t2 / span).pow(1.5).toFloat() else 0f
				val intensity = max(i1, i2).coerceIn(0.05f, 1f)
				val hueShift = if (i1 >= i2) 0f else 0.5f
				val hue = ((timeSec * 0.22 + hueShift + frac * 0.25) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				SpotlightSample(minR * 0.34f, 0.2f, r, g, b, intensity, 1.05f)
			}

			"rgb_wave" -> {
				val phase = timeSec * 2.8 - 2.0 * theta
				val w = (sin(phase) * 0.5 + 0.5).toFloat()
				val hue = ((timeSec * 0.25 + frac) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.92f, 1f)
				SpotlightSample(minR * lerp(-0.15f, 0.65f, w), 0f, r, g, b, 0.22f + 0.78f * w, 0.95f)
			}

			"rgb_fan" -> {
				val s = sin(timeSec * 1.4).toFloat()
				val hue = ((timeSec * 0.3 + frac * 0.5) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				SpotlightSample(minR * (0.28f - 0.58f * s), 0f, r, g, b, 0.94f, 1.05f)
			}

			"rgb_vortex" -> {
				val hue = ((timeSec * 0.32 + frac) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				val inward = minR * (0.38f + 0.14f * sin(timeSec * 1.3 + theta).toFloat())
				val lat = (0.78 + 0.18 * sin(timeSec * 1.8)).toFloat()
				SpotlightSample(inward, lat, r, g, b, 0.92f, 0.95f)
			}

			"rgb_ballyhoo" -> {
				val hue = ((timeSec * 0.25 + frac * 1.5) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				val inward = minR * (0.26f + 0.42f * sin(timeSec * 1.7 + theta * 2.0).toFloat())
				val lat = (0.72 * cos(timeSec * 3.4 + theta)).toFloat()
				SpotlightSample(inward, lat, r, g, b, 0.92f, 0.95f)
			}

			"rgb_xcross" -> {
				val hue = ((timeSec * 0.22 + (if (sin(theta) >= 0.0) 0.0 else 0.33)) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				val breathe = 0.82f + 0.14f * sin(timeSec * 1.1).toFloat()
				SpotlightSample(minR * breathe, 0.15f * sin(timeSec * 1.2).toFloat(), r, g, b, 0.92f, 1.05f)
			}

			"rgb_radar" -> {
				val beamAngle = (timeSec * 2.6) % TAU
				val diff = abs(((theta - beamAngle + PI) % TAU + TAU) % TAU - PI)
				val intensity = exp(-(diff * diff) / 0.32).toFloat().coerceIn(0.05f, 1f)
				val hue = ((timeSec * 0.22 + frac) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				SpotlightSample(minR * 0.20f, 0f, r, g, b, intensity, 1.65f)
			}

			"rgb_bump" -> {
				val decay = exp(-beatPhase * 5.2).toFloat()
				val hue = ((beatIndex * 0.27) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				SpotlightSample(minR * (0.25f + 0.30f * (1f - decay)), 0f, r, g, b, 0.18f + 0.82f * decay, 1.0f + 0.35f * decay)
			}

			"rgb_strobe" -> {
				val tick = floor(timeSec * 14.0).toInt()
				val on = (tick and 1) == 0
				val hue = ((tick * 0.19 + frac * 0.2) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.90f, 1f)
				SpotlightSample(minR * 0.25f, 0f, r, g, b, if (on) 1.0f else 0.02f, 1.15f)
			}

			"rgb_converge" -> {
				val targetX = cos(timeSec * 0.9) * 0.65
				val targetZ = sin(timeSec * 1.25) * 0.65
				val relX = targetX - cos(theta) * 0.95
				val relZ = targetZ - sin(theta) * 0.95
				val inward = (-(relX * cos(theta) + relZ * sin(theta)) * minR * 0.52).toFloat()
				val lat = ((-relX * sin(theta) + relZ * cos(theta)) * 0.55).toFloat()
				val hue = ((timeSec * 0.25) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				SpotlightSample(inward, lat, r, g, b, 0.96f, 0.92f)
			}

			"rgb_blackout" -> {
				val cycle = (timeSec * 0.65) % 1.0
				val hue = ((floor(timeSec * 0.65) * 0.31 + frac) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				if (cycle < 0.45) {
					SpotlightSample(minR * 0.3f, 0f, r, g, b, 0.02f, 1.0f)
				} else {
					val k = ((cycle - 0.45) / 0.55).toFloat()
					val blinder = exp(-k * 3.6f)
					SpotlightSample(minR * 0.12f, 0f, r, g, b, (0.05f + 0.95f * blinder), 1.65f)
				}
			}

			"rgb_aurora" -> {
				// Cyan -> Green -> Violet -> Magenta palette
				val t = (sin(timeSec * 0.55 + theta * 1.5) * 0.5 + 0.5).toFloat()
				val hue = lerp(0.38f, 0.84f, t)
				val (r, g, b) = hsvToRgb(hue, 0.85f, 1f)
				val inward = minR * (0.18f + 0.22f * sin(timeSec * 0.7 + theta).toFloat())
				val lat = (0.35 * sin(timeSec * 0.6 + theta * 2.0)).toFloat()
				SpotlightSample(inward, lat, r, g, b, 0.78f + 0.22f * t, 1.85f)
			}

			"rgb_helix" -> {
				val sign = if (isEven) 1f else -1f
				val phase = timeSec * 2.1 + theta
				val inward = minR * (0.28f + 0.35f * sign * sin(phase).toFloat())
				val lat = sign * 0.55f * cos(phase).toFloat()
				val hue = ((timeSec * 0.25 + frac + (if (isEven) 0.0 else 0.5)) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				SpotlightSample(inward, lat, r, g, b, 0.94f, 0.92f)
			}

			"rgb_starburst" -> {
				val burst = (sin(timeSec * 2.4) * 0.5 + 0.5).toFloat()
				val inward = minR * lerp(0.45f, -0.55f, burst)
				val hue = ((timeSec * 0.65 + frac) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				SpotlightSample(inward, 0f, r, g, b, 0.75f + 0.25f * burst, 0.55f)
			}

			"rgb_kaleido" -> {
				val quadPhase = ((sIdx % max(1, n / 4)).toDouble() / max(1, n / 4).toDouble()) * TAU
				val wave = sin(timeSec * 2.0 + quadPhase).toFloat()
				val inward = minR * (0.30f + 0.35f * wave)
				val lat = 0.45f * cos(timeSec * 2.0 + quadPhase).toFloat()
				val hue = ((timeSec * 0.22 + quadPhase / TAU) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				SpotlightSample(inward, lat, r, g, b, 0.92f, 1.0f)
			}

			"rgb_supernova" -> {
				val p = ((timeSec * 0.7) % 1.0).toFloat()
				val hue = ((floor(timeSec * 0.7) * 0.29) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				if (p < 0.35f) {
					val k = p / 0.35f
					SpotlightSample(minR * lerp(0.3f, 0.85f, k), 0f, r, g, b, 0.35f + 0.65f * k, 0.75f)
				} else {
					val k = (p - 0.35f) / 0.65f
					val ease = 1f - (1f - k).pow(2)
					SpotlightSample(minR * lerp(0.85f, -0.58f, ease), 0f, r, g, b, (1f - k * 0.75f), 1.45f)
				}
			}

			"rgb_neon" -> {
				val hue = ((timeSec * 0.35 + frac) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 1.0f, 1f)
				val inward = -minR * 0.25f
				val pulse = 0.80f + 0.20f * sin(timeSec * 2.5 + theta).toFloat()
				SpotlightSample(inward, 0.15f, r, g, b, pulse, 2.0f)
			}

			"rgb_cyber" -> {
				val layerA = isEven
				val dir = if (layerA) 1f else -1f
				val hue = if (layerA) ((timeSec * 0.2 + 0.52) % 1.0).toFloat() else ((timeSec * 0.2 + 0.85) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				val inward = minR * 0.52f
				val lat = dir * (0.65f + 0.25f * sin(timeSec * 1.5).toFloat())
				SpotlightSample(inward, lat, r, g, b, 0.92f, 0.55f)
			}

			"rgb_meteor" -> {
				val period = 1.6
				val offset = ((sIdx * 0.6180339887) % 1.0) * period
				val p = ((timeSec + offset) % period) / period
				val burst = if (p < 0.25) exp(-((p - 0.08) / 0.07).pow(2)).toFloat() else 0.05f
				val hue = ((timeSec * 0.3 + frac) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.95f, 1f)
				val inward = minR * lerp(0.35f, -0.35f, (p / 0.25).coerceIn(0.0, 1.0).toFloat())
				SpotlightSample(inward, 0.12f, r, g, b, burst, 0.85f)
			}

			"rgb_breathe" -> {
				val bWave = (sin(timeSec * 0.85) * 0.5 + 0.5).toFloat()
				val hue = ((timeSec * 0.12 + frac * 0.3) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.88f, 1f)
				val inward = minR * lerp(0.50f, -0.20f, bWave)
				SpotlightSample(inward, 0f, r, g, b, 0.38f + 0.62f * bWave, 1.15f)
			}

			"rgb_disco" -> {
				val step = floor(timeSec * BPM_HZ).toInt()
				val hash = ((sIdx * 73 + step * 139) % 97) / 97.0
				val hash2 = ((sIdx * 41 + step * 89) % 83) / 83.0
				val hue = ((hash + step * 0.17) % 1.0).toFloat()
				val (r, g, b) = hsvToRgb(hue, 0.98f, 1f)
				val inward = minR * lerp(-0.35f, 0.65f, hash.toFloat())
				val lat = lerp(-0.85f, 0.85f, hash2.toFloat())
				SpotlightSample(inward, lat, r, g, b, 0.95f, 0.95f)
			}

			else -> {
				val cycle = (sin(timeSec * 0.65) * 0.5 + 0.5).toFloat()
				SpotlightSample(lerp(minR * 0.25f, minR * 0.85f, cycle), 0f, baseR, baseG, baseB, 0.9f, 1.0f)
			}
		}
	}

	private fun ringDist(a: Double, b: Double, n: Double): Double {
		val d = abs(a - b) % n
		return min(d, n - d)
	}

	private fun ringBehind(idx: Double, head: Double, n: Double): Double {
		return ((head - idx) % n + n) % n
	}

	fun hsvToRgb(hIn: Float, s: Float, v: Float): Triple<Float, Float, Float> {
		val h = ((hIn % 1f) + 1f) % 1f
		val i = floor(h * 6f).toInt()
		val f = h * 6f - i
		val p = v * (1f - s)
		val q = v * (1f - f * s)
		val t = v * (1f - (1f - f) * s)
		return when (i % 6) {
			0 -> Triple(v, t, p)
			1 -> Triple(q, v, p)
			2 -> Triple(p, v, t)
			3 -> Triple(p, q, v)
			4 -> Triple(t, p, v)
			else -> Triple(v, p, q)
		}
	}
}
