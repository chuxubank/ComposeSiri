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
import kotlin.math.tanh

/**
 * One translucent surface of the orb: an ellipse centred on the orb, with a texture that is clear
 * inside and dense towards the rim. The ellipse is twisted around its long axis, mostly near the
 * centre, and bent along it, so its two halves face different ways: it reads as a figure eight, or as
 * a broad lobe whose other half is a straight blade.
 * The whole surface spins around one axis and slowly drifts around a second one.
 */
internal class PetalSpec(
    val red: Float,
    val green: Float,
    val blue: Float,
    /** Half of the short axis of the ellipse, in orb radii. */
    val width: Float,
    /** Half of the long axis of the ellipse, in orb radii. */
    val length: Float,
    /** Twist in radians from the centre to either end of the long axis. */
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
    /** Starting angle around [spinAxis], in degrees. */
    val spinPhase: Float = 0f,
    /**
     * Moves the ellipse along its long axis, in orb radii, so it reads as one lobe reaching out of
     * the core rather than a propeller centred on it.
     */
    val shift: Float = 0f,
)

internal class Axis(x: Float, y: Float, z: Float) {
    private val length = sqrt(x * x + y * y + z * z)
    val x = x / length
    val y = y / length
    val z = z / length
}

// Spin rates follow the time expressions in the reference After Effects project, scaled by TEMPO.
// Petals are wide, gently twisted translucent veils that reach toward the shell rather than
// bunching around the core; palette balances lush emerald/mint, soft rose-magenta, indigo-violet,
// and cyan so no single red mass dominates the lower half and the glass doesn't wash out to cyan.
internal val DefaultPetals = listOf(
    PetalSpec(
        0.18f, 0.86f, 0.44f, width = 0.90f, length = 0.80f,
        twist = 0.19f, twistOffset = 1.57f, bend = 0.048f,
        spinAxis = Axis(0.3f, 1f, 0.4f), spin = 30f, driftAxis = Axis(1f, 0f, 0.3f), drift = 6f,
        tiltX = 0f, tiltY = 0f, tiltZ = 30f, seed = 0.7f,
        spinPhase = 90f, shift = 0.30f,
    ),
    PetalSpec(
        0.88f, 0.23f, 0.52f, width = 0.84f, length = 0.76f,
        twist = 0.23f, twistOffset = 0.4f, bend = 0.048f,
        spinAxis = Axis(-0.5f, 0.8f, 0.3f), spin = 45f, driftAxis = Axis(0.2f, 0.3f, 1f), drift = -5f,
        tiltX = 60f, tiltY = 120f, tiltZ = 200f, seed = 1.9f,
        shift = 0.30f,
    ),
    PetalSpec(
        0.30f, 0.36f, 0.90f, width = 0.88f, length = 0.94f,
        twist = 0.25f, twistOffset = 2.5f, bend = 0.042f,
        spinAxis = Axis(0.7f, 0.4f, -0.6f), spin = -45f, driftAxis = Axis(0f, 1f, 0.3f), drift = 7f,
        tiltX = 140f, tiltY = 250f, tiltZ = 100f, seed = 3.1f,
        shift = 0.14f,
    ),
    PetalSpec(
        0.15f, 0.65f, 0.78f, width = 0.80f, length = 0.76f,
        twist = 0.20f, twistOffset = 1.0f, bend = 0.048f,
        spinAxis = Axis(0.2f, -0.4f, 1f), spin = 80f, driftAxis = Axis(0.8f, 0.5f, 0f), drift = 5f,
        tiltX = 220f, tiltY = 60f, tiltZ = 320f, seed = 4.4f,
        shift = 0.29f,
    ),
    PetalSpec(
        0.36f, 0.80f, 0.50f, width = 0.84f, length = 0.76f,
        twist = 0.22f, twistOffset = 2.0f, bend = 0.048f,
        spinAxis = Axis(-0.6f, -0.7f, 0.4f), spin = -30f, driftAxis = Axis(0.4f, 0f, 1f), drift = -6f,
        tiltX = 300f, tiltY = 200f, tiltZ = 60f, seed = 5.6f,
        shift = 0.28f,
    ),
)

internal class OrbScene(private val petals: List<PetalSpec> = DefaultPetals) {

    private val lengthSteps = 64
    private val widthSteps = 28
    private val verticesPerPetal = (lengthSteps + 1) * (widthSteps + 1)
    private val petalVertexCount = petals.size * verticesPerPetal

    private val rimSegments = 160
    // The glass shell: a gentle interior wash that dips slightly in the mid-outer zone, then rises
    // into a crisp refractive inner lip right at the silhouette before dropping cleanly to black.
    private val rimRadii = floatArrayOf(0f, 0.36f, 0.58f, 0.75f, 0.87f, 0.935f, 0.970f, 0.988f, 0.996f, 1.001f, 1.006f)
    private val rimProfile = floatArrayOf(0.27f, 0.33f, 0.43f, 0.56f, 0.70f, 0.89f, 1.13f, 1.42f, 1.16f, 0.06f, 0f)
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

    private val tintBins = 64
    private val tint = FloatArray(tintBins * 3)
    private val tintScratch = FloatArray(tintBins * 3)
    private val tintWide = FloatArray(tintBins * 3)
    private val silhouetteScale = FloatArray(rimSegments)

    private val local = FloatArray(9)
    private val m = FloatArray(9)
    private val ma = FloatArray(9)
    private val mb = FloatArray(9)
    private val mc = FloatArray(9)

    init {
        for (i in 0..lengthSteps) {
            val t = 2f * i / lengthSteps - 1f
            val a = abs(t)
            stepT[i] = t
            profile[i] = sqrt(max(0f, 1f - a * a))
        }
        for (j in 0..widthSteps) {
            stepS[j] = sin(PI.toFloat() / 2f * (2f * j / widthSteps - 1f))
        }
        for (i in 0..lengthSteps) {
            for (j in 0..widthSteps) {
                // Soft veil fill across the ellipse body with a feathered outline so tips stay wispy.
                val u = profile[i] * stepS[j]
                val v = stepT[i]
                val rho = min(1f, sqrt(u * u + v * v))
                texture[i * (widthSteps + 1) + j] = (0.64f + 0.36f * rho.pow(1.6f)) * smoothstep(1f, 0.88f, rho)
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

    fun update(clock: Float, centerX: Float, centerY: Float, radiusPx: Float) {
        val time = clock * TEMPO
        tint.fill(0f)
        for (p in petals.indices) {
            updatePetal(petals[p], p * verticesPerPetal, time, clock, centerX, centerY, radiusPx)
        }
        updateRim(centerX, centerY, radiusPx, clock)
    }

    private fun updatePetal(
        spec: PetalSpec,
        base: Int,
        time: Float,
        clock: Float,
        cx: Float,
        cy: Float,
        radiusPx: Float,
    ) {
        val twist = spec.twist + TWIST_WIGGLE * wiggle(time, WIGGLE_HZ, spec.seed)
        val bend = spec.bend + BEND_WIGGLE * wiggle(time, WIGGLE_HZ * 0.8f, spec.seed + 5.3f)
        orientation(spec, time)
        val length = spec.length

        for (i in 0..lengthSteps) {
            val halfWidth = profile[i] * spec.width
            val y0 = stepT[i] * length
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

                val ly = local[1] + spec.shift
                val px = m[0] * local[0] + m[1] * ly + m[2] * local[2]
                val py = m[3] * local[0] + m[4] * ly + m[5] * local[2]
                val pz = m[6] * local[0] + m[7] * ly + m[8] * local[2]
                val nx = m[0] * nlx + m[1] * nly + m[2] * nlz
                val ny = m[3] * nlx + m[4] * nly + m[5] * nlz
                val nz = m[6] * nlx + m[7] * nly + m[8] * nlz

                val vdx = -px; val vdy = -py; val vdz = CAMERA - pz
                val nLen = sqrt(nx * nx + ny * ny + nz * nz)
                val vLen = sqrt(vdx * vdx + vdy * vdy + vdz * vdz)
                val facing = if (nLen > 1e-12f) abs(nx * vdx + ny * vdy + nz * vdz) / (nLen * vLen) else 1f
                // Translucent veil: gentler grazing boost so broad faces stay luminous and edges don't turn into thin blades.
                val film = 1f / sqrt(facing * facing + GRAZE_FLOOR * GRAZE_FLOOR)
                val fog = FOG_FAR + (1f - FOG_FAR) * smoothstep(-0.85f, 0.65f, pz)
                val k = i * (widthSteps + 1) + j
                val alpha = texture[k]
                val scale = CAMERA / (CAMERA - pz) * radiusPx
                val sx = cx + px * scale
                val sy = cy - py * scale
                val dx = sx - cx; val dy = sy - cy
                val angle = atan2(dy, dx)
                val reach = (dx * dx + dy * dy) / (radiusPx * radiusPx)
                val localOutline = orbOutlineBase(angle, clock)
                val normReach = reach / (localOutline * localOutline)
                val contain = smoothstep(CONTAIN_END, CONTAIN_START, normReach)
                val intensity = GAIN * alpha * film * fog * contain
                val v = base + k
                positions[v * 2] = sx
                positions[v * 2 + 1] = sy
                val d = sqrt(reach)
                val white = DEPTH_WHITE * (1 - smoothstep(0f, DEPTH_WHITE_END, d))
                val r0 = spec.red * intensity
                val g0 = spec.green * intensity
                val b0 = spec.blue * intensity
                val peak = max(r0, max(g0, b0))
                colors[v] = toneMap(r0 + (peak - r0) * white, g0 + (peak - g0) * white, b0 + (peak - b0) * white)

                var bin = ((angle / (2f * PI.toFloat()) + 0.5f) * tintBins).toInt()
                if (bin >= tintBins) bin = tintBins - 1
                // Both mid-body and outer reach illuminate the glass wall along this meridian.
                val w = alpha * fog * (0.30f + 0.70f * min(1f, reach))
                tint[bin * 3] += spec.red * w
                tint[bin * 3 + 1] += spec.green * w
                tint[bin * 3 + 2] += spec.blue * w
            }
        }
    }

    private fun updateRim(cx: Float, cy: Float, radiusPx: Float, clock: Float) {
        // Local reflection pass: preserves the distinct hue of whichever petal approaches each sector.
        smoothTint(tint, passes = 3)
        tint.copyInto(tintWide)
        // Broad internal scatter pass: carries neighbouring petal colour across quieter sectors so
        // gaps never drop to neutral black or wash into a single uniform cyan.
        smoothTint(tintWide, passes = 14)

        var peak = 1e-6f
        for (i in 0 until tintBins) {
            for (c in 0..2) {
                val mixed = (1f - WIDE_SCATTER_MIX) * tint[i * 3 + c] + WIDE_SCATTER_MIX * tintWide[i * 3 + c]
                tint[i * 3 + c] = mixed
                peak = max(peak, mixed)
            }
        }

        // Precompute the organic silhouette per angle: base fluid harmonics + a gentle outward bulge
        // where internal petals illuminate the shell most strongly.
        var maxMag = 1e-6f
        for (i in 0 until tintBins) {
            val mag = max(tint[i * 3], max(tint[i * 3 + 1], tint[i * 3 + 2]))
            maxMag = max(maxMag, mag)
        }
        for (s in 0 until rimSegments) {
            val angle = 2f * PI.toFloat() * s / rimSegments - PI.toFloat()
            val binF = (angle / (2f * PI.toFloat()) + 0.5f) * tintBins - 0.5f
            val b0 = ((floor(binF).toInt() % tintBins) + tintBins) % tintBins
            val b1 = (b0 + 1) % tintBins
            val f = binF - floor(binF)
            val m0 = max(tint[b0 * 3], max(tint[b0 * 3 + 1], tint[b0 * 3 + 2])) / maxMag
            val m1 = max(tint[b1 * 3], max(tint[b1 * 3 + 1], tint[b1 * 3 + 2])) / maxMag
            val lobePush = LOBE_OUTLINE_PUSH * ((m0 * (1f - f) + m1 * f) - 0.55f)
            silhouetteScale[s] = (orbOutlineBase(angle, clock) + lobePush).coerceIn(0.950f, 1.045f)
        }

        for (ring in rimRadii.indices) {
            val ringFrac = rimRadii[ring]
            val k = rimProfile[ring] * RIM_GAIN
            // Outer glass wall reflects local petal colour strongly; deeper interior mixes more of the
            // wide scattered tint so the whole sphere feels filled with colour without over-brightening.
            val localWeight = 0.45f + 0.55f * smoothstep(0.35f, 0.95f, ringFrac)
            for (s in 0 until rimSegments) {
                val angle = 2f * PI.toFloat() * s / rimSegments - PI.toFloat()
                val binF = (angle / (2f * PI.toFloat()) + 0.5f) * tintBins - 0.5f
                val b0 = ((floor(binF).toInt() % tintBins) + tintBins) % tintBins
                val b1 = (b0 + 1) % tintBins
                val f = binF - floor(binF)

                val lr = (tint[b0 * 3] * (1f - f) + tint[b1 * 3] * f) / peak
                val lg = (tint[b0 * 3 + 1] * (1f - f) + tint[b1 * 3 + 1] * f) / peak
                val lb = (tint[b0 * 3 + 2] * (1f - f) + tint[b1 * 3 + 2] * f) / peak

                val wr = (tintWide[b0 * 3] * (1f - f) + tintWide[b1 * 3] * f) / peak
                val wg = (tintWide[b0 * 3 + 1] * (1f - f) + tintWide[b1 * 3 + 1] * f) / peak
                val wb = (tintWide[b0 * 3 + 2] * (1f - f) + tintWide[b1 * 3 + 2] * f) / peak

                var tr = localWeight * lr + (1f - localWeight) * wr
                var tg = localWeight * lg + (1f - localWeight) * wg
                var tb = localWeight * lb + (1f - localWeight) * wb

                // Normalize each sector's hue to its brightest channel so vivid saturation is preserved,
                // while keeping clear directional contrast between petal-lit sectors and quieter sectors.
                val sectorPeak = max(1e-4f, max(tr, max(tg, tb)))
                val energy = TINT_FLOOR + (1f - TINT_FLOOR) * sectorPeak.pow(0.80f)
                tr = tr / sectorPeak * energy
                tg = tg / sectorPeak * energy
                tb = tb / sectorPeak * energy

                // Faint specular sheen on the outermost glass lip where petals press against the shell.
                val sheen = SPECULAR_SHEEN * smoothstep(0.92f, 0.99f, ringFrac) * energy
                val u = 0.5f + 0.5f * (cos(angle) + sin(angle)) / sqrt(2f)
                val gr = RIM_UL_R + (RIM_LR_R - RIM_UL_R) * u
                val gg = RIM_UL_G + (RIM_LR_G - RIM_UL_G) * u
                val gb = RIM_UL_B + (RIM_LR_B - RIM_UL_B) * u
                val rr = RIM_TINT_MIX * gr + (1f - RIM_TINT_MIX) * tr + sheen
                val rg = RIM_TINT_MIX * gg + (1f - RIM_TINT_MIX) * tg + sheen
                val rb = RIM_TINT_MIX * gb + (1f - RIM_TINT_MIX) * tb + sheen

                val v = petalVertexCount + ring * rimSegments + s
                val radius = ringFrac * radiusPx * silhouetteScale[s]
                positions[v * 2] = cx + cos(angle) * radius
                positions[v * 2 + 1] = cy + sin(angle) * radius
                colors[v] = toneMap(
                    (RIM_BASE_R + rr) * k,
                    (RIM_BASE_G + rg) * k,
                    (RIM_BASE_B + rb) * k,
                )
            }
        }
    }

    private fun smoothTint(buf: FloatArray, passes: Int) {
        repeat(passes) {
            for (i in 0 until tintBins) {
                val l = (i + tintBins - 1) % tintBins
                val r = (i + 1) % tintBins
                for (c in 0..2) {
                    tintScratch[i * 3 + c] = 0.25f * buf[l * 3 + c] + 0.5f * buf[i * 3 + c] + 0.25f * buf[r * 3 + c]
                }
            }
            tintScratch.copyInto(buf)
        }
    }

    private fun deform(x0: Float, y0: Float, length: Float, twist: Float, twistOffset: Float, bend: Float, o: Int) {
        val theta = twistOffset + twist * tanh(TWIST_SHARPNESS * y0 / length) / tanh(TWIST_SHARPNESS)
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
        axisAngle(spec.spinAxis, (spec.spin * time + spec.spinPhase) * toRad, ma)
        multiply(ma, mc, m)
    }

    private companion object {
        const val EPS = 1e-3f
        const val CAMERA = 5f
        // Idle rotation rate matched to the iOS 27 (China) recording.
        const val TEMPO = 0.28f
        const val GAIN = 0.26f
        const val CONTAIN_START = 0.73f
        const val CONTAIN_END = 0.93f
        const val GRAZE_FLOOR = 0.44f
        const val FOG_FAR = 0.30f
        const val DEPTH_WHITE = 0.14f
        const val DEPTH_WHITE_END = 0.30f
        const val TWIST_SHARPNESS = 2.2f
        const val TWIST_WIGGLE = 0.14f
        const val BEND_WIGGLE = 0.05f
        const val WIGGLE_HZ = 0.2f
        const val RIM_GAIN = 0.39f
        const val RIM_BASE_R = 0.012f
        const val RIM_BASE_G = 0.018f
        const val RIM_BASE_B = 0.022f
        const val RIM_TINT_MIX = 0.07f
        const val WIDE_SCATTER_MIX = 0.28f
        const val TINT_FLOOR = 0.51f
        const val SPECULAR_SHEEN = 0.08f
        const val LOBE_OUTLINE_PUSH = 0.018f
        // Subtle ambient glass bias: warm rose upper-left to cool indigo-blue lower-right.
        const val RIM_UL_R = 0.92f
        const val RIM_UL_G = 0.28f
        const val RIM_UL_B = 0.48f
        const val RIM_LR_R = 0.24f
        const val RIM_LR_G = 0.46f
        const val RIM_LR_B = 0.96f
    }
}

/**
 * Base organic silhouette scale around the unit circle as a function of real [clock] seconds.
 * Combines counter-rotating 2-lobe (elliptical) and 3-lobe (triangular) waves with a faint 4-lobe
 * ripple so the outer boundary is clearly off-circular and continuously breathing (~1.5–2.5% RMS),
 * without wobbling into a cartoonish amoeba.
 */
internal fun orbOutlineBase(angle: Float, clock: Float): Float {
    val w =
        0.019f * sin(2f * angle + clock * 0.62f + 0.4f) +
            0.012f * sin(2f * angle - clock * 0.41f + 2.1f) +
            0.014f * sin(3f * angle - clock * 0.53f + 1.1f) +
            0.007f * sin(3f * angle + clock * 0.34f + 3.7f) +
            0.005f * sin(4f * angle + clock * 0.77f + 0.9f)
    return 1f + w
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
