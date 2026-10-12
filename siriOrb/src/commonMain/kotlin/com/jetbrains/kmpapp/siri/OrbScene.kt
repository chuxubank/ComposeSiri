package com.jetbrains.kmpapp.siri

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One translucent surface of the orb: two teardrop lobes meeting tip to tip at the orb centre,
 * with a texture that is clear inside and dense towards the rim. The surface is twisted around and
 * bent along its long axis, spins around one axis and slowly drifts around a second one.
 */
internal class PetalSpec(
    val red: Float,
    val green: Float,
    val blue: Float,
    val width: Float,
    val length: Float,
    /** Length of the lobe on the other side of the centre, as a fraction of [length]. */
    val backLength: Float,
    /** Twist in radians from the centre to the end of the main lobe. */
    val twist: Float,
    val twistOffset: Float,
    /** Curvature of the bend along the long axis, in 1 / orb radius. */
    val bend: Float,
    val spinAxis: Axis,
    /** Degrees per second around [spinAxis]. */
    val spin: Float,
    val driftAxis: Axis,
    /** Degrees per second around [driftAxis]. */
    val drift: Float,
    /** Starting orientation as rotations around x, y and z, in degrees. */
    val tiltX: Float,
    val tiltY: Float,
    val tiltZ: Float,
    val seed: Float,
)

internal class Axis(x: Float, y: Float, z: Float) {
    private val length = sqrt(x * x + y * y + z * z)
    val x = x / length
    val y = y / length
    val z = z / length
}

internal val DefaultPetals = listOf(
    PetalSpec(
        0.98f, 0.07f, 0.11f, width = 0.74f, length = 0.84f, backLength = 0.45f,
        twist = 0.30f, twistOffset = 0.3f, bend = 0.8f,
        spinAxis = Axis(0.3f, 1f, 0.2f), spin = 34f, driftAxis = Axis(1f, 0f, 0.4f), drift = 9f,
        tiltX = 10f, tiltY = 40f, tiltZ = 0f, seed = 0.1f,
    ),
    PetalSpec(
        0.03f, 0.24f, 1.00f, width = 0.76f, length = 0.86f, backLength = 0.5f,
        twist = 0.25f, twistOffset = 1.9f, bend = 0.7f,
        spinAxis = Axis(-0.2f, 0.9f, -0.5f), spin = -28f, driftAxis = Axis(0.3f, 0.2f, 1f), drift = 11f,
        tiltX = 70f, tiltY = 160f, tiltZ = 50f, seed = 0.7f,
    ),
    PetalSpec(
        0.05f, 0.78f, 1.00f, width = 0.46f, length = 0.84f, backLength = 0.8f,
        twist = 0.40f, twistOffset = 0.9f, bend = 0.9f,
        spinAxis = Axis(0.8f, 0.3f, 0.5f), spin = 38f, driftAxis = Axis(0f, 1f, 0.3f), drift = -8f,
        tiltX = 130f, tiltY = 260f, tiltZ = 120f, seed = 1.3f,
    ),
    PetalSpec(
        0.02f, 0.70f, 0.55f, width = 0.64f, length = 0.82f, backLength = 0.4f,
        twist = 0.30f, twistOffset = 2.6f, bend = 0.8f,
        spinAxis = Axis(0.4f, -0.6f, 0.7f), spin = -32f, driftAxis = Axis(0.7f, 0.7f, 0f), drift = 10f,
        tiltX = 200f, tiltY = 80f, tiltZ = 200f, seed = 2.1f,
    ),
    PetalSpec(
        1.00f, 0.06f, 0.24f, width = 0.48f, length = 0.84f, backLength = 0.6f,
        twist = 0.35f, twistOffset = 0.1f, bend = 0.9f,
        spinAxis = Axis(-0.6f, 0.5f, 0.6f), spin = 30f, driftAxis = Axis(0.2f, 1f, -0.3f), drift = -12f,
        tiltX = 280f, tiltY = 320f, tiltZ = 280f, seed = 2.9f,
    ),
    PetalSpec(
        0.10f, 0.30f, 1.00f, width = 0.70f, length = 0.86f, backLength = 0.35f,
        twist = 0.30f, twistOffset = 1.2f, bend = 0.8f,
        spinAxis = Axis(0.5f, 0.8f, -0.3f), spin = -36f, driftAxis = Axis(1f, -0.3f, 0.3f), drift = 9f,
        tiltX = 330f, tiltY = 220f, tiltZ = 15f, seed = 3.6f,
    ),
)

internal class OrbScene(private val petals: List<PetalSpec> = DefaultPetals) {

    private val lengthSteps = 64
    private val widthSteps = 28
    private val verticesPerPetal = (lengthSteps + 1) * (widthSteps + 1)
    private val petalVertexCount = petals.size * verticesPerPetal

    private val rimSegments = 120
    private val rimRadii = floatArrayOf(0.66f, 0.84f, 0.93f, 0.97f, 0.993f, 1.012f, 1.045f)
    private val rimProfile = floatArrayOf(0f, 0.12f, 0.36f, 0.72f, 1f, 0.30f, 0f)
    private val rimVertexCount = rimSegments * rimRadii.size

    val vertexCount = petalVertexCount + rimVertexCount
    val positions = FloatArray(vertexCount * 2)
    val colors = IntArray(vertexCount)
    val indices: IntArray
    val petalIndexCount: Int

    private val stepT = FloatArray(lengthSteps + 1)
    private val stepS = FloatArray(widthSteps + 1)
    private val profile = FloatArray(lengthSteps + 1)
    private val texture = FloatArray(verticesPerPetal)

    private val tintBins = 48
    private val tint = FloatArray(tintBins * 3)
    private val tintScratch = FloatArray(tintBins * 3)

    private val local = FloatArray(9)
    private val m = FloatArray(9)
    private val ma = FloatArray(9)
    private val mb = FloatArray(9)
    private val mc = FloatArray(9)

    init {
        var peak = 0f
        for (i in 0..lengthSteps) {
            val t = 2f * i / lengthSteps - 1f
            val a = abs(t)
            stepT[i] = t
            profile[i] = a.pow(0.85f) * sqrt(max(0f, 1f - a.pow(2.4f)))
            peak = max(peak, profile[i])
        }
        for (i in 0..lengthSteps) profile[i] /= peak
        for (j in 0..widthSteps) {
            stepS[j] = sin(PI.toFloat() / 2f * (2f * j / widthSteps - 1f))
        }
        for (i in 0..lengthSteps) {
            for (j in 0..widthSteps) {
                val s = stepS[j]
                val t = abs(stepT[i])
                val rho = min(1f, sqrt(0.85f * s * s + 0.6f * t * t))
                texture[i * (widthSteps + 1) + j] = (0.38f + 0.62f * rho.pow(1.6f)) *
                    smoothstep(1f, 0.94f, abs(s)) * smoothstep(1f, 0.94f, t)
            }
        }

        val idx = ArrayList<Int>()
        for (p in petals.indices) {
            val base = p * verticesPerPetal
            for (i in 0 until lengthSteps) {
                for (j in 0 until widthSteps) {
                    val a = base + i * (widthSteps + 1) + j
                    val b = a + 1
                    val c = a + widthSteps + 1
                    val d = c + 1
                    idx += a; idx += c; idx += d
                    idx += a; idx += d; idx += b
                }
            }
        }
        petalIndexCount = idx.size
        for (r in 0 until rimRadii.size - 1) {
            for (s in 0 until rimSegments) {
                val a = petalVertexCount + r * rimSegments + s
                val b = petalVertexCount + r * rimSegments + (s + 1) % rimSegments
                val c = a + rimSegments
                val d = b + rimSegments
                idx += a; idx += c; idx += d
                idx += a; idx += d; idx += b
            }
        }
        indices = idx.toIntArray()
    }

    fun update(time: Float, centerX: Float, centerY: Float, radiusPx: Float) {
        tint.fill(0f)
        for (p in petals.indices) {
            updatePetal(petals[p], p * verticesPerPetal, time, centerX, centerY, radiusPx)
        }
        updateRim(centerX, centerY, radiusPx)
    }

    private fun updatePetal(
        spec: PetalSpec,
        base: Int,
        time: Float,
        cx: Float,
        cy: Float,
        radiusPx: Float,
    ) {
        val twist = spec.twist + TWIST_WIGGLE * wiggle(time, WIGGLE_HZ, spec.seed)
        val bend = spec.bend + BEND_WIGGLE * wiggle(time, WIGGLE_HZ * 0.8f, spec.seed + 5.3f)
        orientation(spec, time)
        val length = spec.length
        val backWidth = spec.width * (0.5f + 0.5f * spec.backLength)

        for (i in 0..lengthSteps) {
            val t = stepT[i]
            val halfWidth = profile[i] * if (t < 0f) backWidth else spec.width
            val y0 = t * if (t < 0f) length * spec.backLength else length
            for (j in 0..widthSteps) {
                val x0 = halfWidth * stepS[j]
                deform(x0, y0, length, twist, spec.twistOffset, bend, 0)
                deform(x0 + EPS, y0, length, twist, spec.twistOffset, bend, 3)
                deform(x0, y0 + EPS, length, twist, spec.twistOffset, bend, 6)
                val ux = local[3] - local[0]; val uy = local[4] - local[1]; val uz = local[5] - local[2]
                val vx = local[6] - local[0]; val vy = local[7] - local[1]; val vz = local[8] - local[2]
                val nlx = uy * vz - uz * vy
                val nly = uz * vx - ux * vz
                val nlz = ux * vy - uy * vx

                val px = m[0] * local[0] + m[1] * local[1] + m[2] * local[2]
                val py = m[3] * local[0] + m[4] * local[1] + m[5] * local[2]
                val pz = m[6] * local[0] + m[7] * local[1] + m[8] * local[2]
                val nx = m[0] * nlx + m[1] * nly + m[2] * nlz
                val ny = m[3] * nlx + m[4] * nly + m[5] * nlz
                val nz = m[6] * nlx + m[7] * nly + m[8] * nlz

                val vdx = -px; val vdy = -py; val vdz = CAMERA - pz
                val nLen = sqrt(nx * nx + ny * ny + nz * nz)
                val vLen = sqrt(vdx * vdx + vdy * vdy + vdz * vdz)
                val facing = if (nLen > 1e-12f) abs(nx * vdx + ny * vdy + nz * vdz) / (nLen * vLen) else 1f
                // A thin glowing film looks brighter the more obliquely it is seen.
                val film = 1f / sqrt(facing * facing + GRAZE_FLOOR * GRAZE_FLOOR)
                val fog = FOG_FAR + (1f - FOG_FAR) * smoothstep(-0.8f, 0.6f, pz)
                val k = i * (widthSteps + 1) + j
                val alpha = texture[k]
                val scale = CAMERA / (CAMERA - pz) * radiusPx
                val sx = cx + px * scale
                val sy = cy - py * scale
                val dx = sx - cx; val dy = sy - cy
                val reach = (dx * dx + dy * dy) / (radiusPx * radiusPx)
                // Safety net only: the petals are sized to stay inside, this catches extreme poses.
                val contain = smoothstep(CONTAIN_END, CONTAIN_START, reach)
                val intensity = GAIN * alpha * film * fog * contain
                val v = base + k
                positions[v * 2] = sx
                positions[v * 2 + 1] = sy
                colors[v] = toneMap(spec.red * intensity, spec.green * intensity, spec.blue * intensity)

                var bin = ((atan2(dy, dx) / (2f * PI.toFloat()) + 0.5f) * tintBins).toInt()
                if (bin >= tintBins) bin = tintBins - 1
                val w = alpha * fog * reach * reach
                tint[bin * 3] += spec.red * w
                tint[bin * 3 + 1] += spec.green * w
                tint[bin * 3 + 2] += spec.blue * w
            }
        }
    }

    private fun updateRim(cx: Float, cy: Float, radiusPx: Float) {
        repeat(5) {
            for (i in 0 until tintBins) {
                val l = (i + tintBins - 1) % tintBins
                val r = (i + 1) % tintBins
                for (c in 0..2) {
                    tintScratch[i * 3 + c] = 0.25f * tint[l * 3 + c] + 0.5f * tint[i * 3 + c] + 0.25f * tint[r * 3 + c]
                }
            }
            tintScratch.copyInto(tint)
        }
        var peak = 1e-6f
        for (v in tint) peak = max(peak, v)

        for (ring in rimRadii.indices) {
            val radius = rimRadii[ring] * radiusPx
            val k = rimProfile[ring] * RIM_GAIN
            for (s in 0 until rimSegments) {
                val angle = 2f * PI.toFloat() * s / rimSegments - PI.toFloat()
                val binF = (angle / (2f * PI.toFloat()) + 0.5f) * tintBins - 0.5f
                val b0 = ((floor(binF).toInt() % tintBins) + tintBins) % tintBins
                val b1 = (b0 + 1) % tintBins
                val f = binF - floor(binF)
                val tr = (tint[b0 * 3] * (1 - f) + tint[b1 * 3] * f) / peak
                val tg = (tint[b0 * 3 + 1] * (1 - f) + tint[b1 * 3 + 1] * f) / peak
                val tb = (tint[b0 * 3 + 2] * (1 - f) + tint[b1 * 3 + 2] * f) / peak
                val v = petalVertexCount + ring * rimSegments + s
                positions[v * 2] = cx + cos(angle) * radius
                positions[v * 2 + 1] = cy + sin(angle) * radius
                colors[v] = toneMap(
                    (RIM_BASE_R + tr) * k,
                    (RIM_BASE_G + tg) * k,
                    (RIM_BASE_B + tb) * k,
                )
            }
        }
    }

    private fun deform(x0: Float, y0: Float, length: Float, twist: Float, twistOffset: Float, bend: Float, o: Int) {
        val theta = twistOffset + twist * (y0 / length)
        val x1 = x0 * cos(theta)
        val z1 = x0 * sin(theta)
        if (abs(bend) < 1e-4f) {
            local[o] = x1; local[o + 1] = y0; local[o + 2] = z1
        } else {
            val r = 1f / bend
            val phi = y0 * bend
            local[o] = x1
            local[o + 1] = (r - z1) * sin(phi)
            local[o + 2] = r - (r - z1) * cos(phi)
        }
    }

    /** m = spin(t) * drift(t) * tilt */
    private fun orientation(spec: PetalSpec, time: Float) {
        val toRad = PI.toFloat() / 180f
        axisAngle(AxisX, spec.tiltX * toRad, ma)
        axisAngle(AxisY, spec.tiltY * toRad, mb)
        multiply(mb, ma, mc)
        axisAngle(AxisZ, spec.tiltZ * toRad, ma)
        multiply(ma, mc, mb)
        axisAngle(spec.driftAxis, spec.drift * time * toRad, ma)
        multiply(ma, mb, mc)
        axisAngle(spec.spinAxis, spec.spin * time * toRad, ma)
        multiply(ma, mc, m)
    }

    private companion object {
        const val EPS = 1e-3f
        const val CAMERA = 5f
        const val GAIN = 0.28f
        const val CONTAIN_START = 0.72f
        const val CONTAIN_END = 0.92f
        const val GRAZE_FLOOR = 0.10f
        const val FOG_FAR = 0.18f
        const val TWIST_WIGGLE = 0.12f
        const val BEND_WIGGLE = 0.20f
        const val WIGGLE_HZ = 0.06f
        const val RIM_GAIN = 0.42f
        const val RIM_BASE_R = 0.03f
        const val RIM_BASE_G = 0.035f
        const val RIM_BASE_B = 0.06f
    }
}

private val AxisX = Axis(1f, 0f, 0f)
private val AxisY = Axis(0f, 1f, 0f)
private val AxisZ = Axis(0f, 0f, 1f)

private fun axisAngle(axis: Axis, angle: Float, out: FloatArray) {
    val c = cos(angle)
    val s = sin(angle)
    val t = 1f - c
    val x = axis.x; val y = axis.y; val z = axis.z
    out[0] = x * x * t + c; out[1] = x * y * t - z * s; out[2] = x * z * t + y * s
    out[3] = y * x * t + z * s; out[4] = y * y * t + c; out[5] = y * z * t - x * s
    out[6] = z * x * t - y * s; out[7] = z * y * t + x * s; out[8] = z * z * t + c
}

private fun multiply(a: FloatArray, b: FloatArray, out: FloatArray) {
    for (r in 0..2) {
        for (c in 0..2) {
            out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
    }
}

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Smooth pseudo-random motion in [-1, 1], standing in for After Effects' wiggle(). */
private fun wiggle(time: Float, frequency: Float, seed: Float): Float {
    val w = 2f * PI.toFloat() * frequency
    return 0.55f * sin(w * time + seed * 7.13f) +
        0.30f * sin(w * 1.73f * time + seed * 3.31f) +
        0.15f * sin(w * 2.91f * time + seed * 1.77f)
}

/** Film-like response: bright overlaps roll off towards white instead of clipping per channel. */
private fun toneMap(r: Float, g: Float, b: Float): Int {
    val spill = max(0f, max(r, max(g, b)) - 1.6f) * 0.25f
    val tr = 1f - exp(-(r + spill))
    val tg = 1f - exp(-(g + spill))
    val tb = 1f - exp(-(b + spill))
    return (0xFF shl 24) or
        (min(255f, tr * 255f + 0.5f).toInt() shl 16) or
        (min(255f, tg * 255f + 0.5f).toInt() shl 8) or
        min(255f, tb * 255f + 0.5f).toInt()
}
