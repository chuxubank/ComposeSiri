package com.jetbrains.kmpapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val TwoPi = 6.2831855f
private const val DegreesToRadians = 0.017453292f

// Preview frame is the cropped orb (reference content diameter ~88% of the square).
// AE shell is the 922 ellipse inside the 1440 precomp; 461 is that radius in AE units.
private const val ShellRadiusFraction = 0.44f
private const val ShellRadiusAe = 461f
private const val CameraDistance = 2700f
private const val FogStart = 2560f
private const val FogRange = 720f
private const val PlaneHalf = 600f

// 0.2 Hz over a 17.117 s loop is 3.42 cycles. 3 cycles stays periodic and seamless.
private const val WiggleCycles = 3f

// time*80 deg/s is 3.80 revolutions per loop. 4 revolutions stays periodic.
private const val TurbulenceCycles = 4f
private const val TurbulenceFeatures = 19f
private const val TurbulenceAmountAe = 7f

private const val Grid = 48
private const val GridN = Grid + 1
private const val PetalVertexCount = GridN * GridN

private const val RimSegments = 144
private const val RimColumns = RimSegments + 1
private const val RimRowVerts = 3
private const val RimVertexCount = RimColumns * RimRowVerts

private enum class PetalColorGroup {
    Red,
    Blue,
    Teal,
}

private data class PetalSpec(
    val color: Color,
    val group: PetalColorGroup,
    val intensity: Float,
    val bendDeg: Float,
    val twistDeg: Float,
    val rotX: Float,
    val rotY: Float,
    val rotZ: Float,
    val spinX: Int,
    val spinY: Int,
    val spinZ: Int,
    val seed: Float,
    val wiggleDeg: Float,
)

private data class RimBand(
    val color: Color,
    val radius: Float,
    val halfWidth: Float,
    val alpha: Float,
    val phase: Float,
)

// Six planes. Spin counts are integer turns per 17.117 s loop:
// ±2 turns ≈ time*45, ±1 turn ≈ time*30, signs split the counter-rotating groups.
private val Petals = listOf(
    PetalSpec(
        color = SiriRed,
        group = PetalColorGroup.Red,
        intensity = 0.72f,
        bendDeg = 230f,
        twistDeg = 36f,
        rotX = 48f,
        rotY = -16f,
        rotZ = -28f,
        spinX = 0,
        spinY = 2,
        spinZ = 0,
        seed = 0.4f,
        wiggleDeg = 12f,
    ),
    PetalSpec(
        color = SiriBlue,
        group = PetalColorGroup.Blue,
        intensity = 0.82f,
        bendDeg = 210f,
        twistDeg = 110f,
        rotX = 66f,
        rotY = 14f,
        rotZ = 36f,
        spinX = -1,
        spinY = 0,
        spinZ = 0,
        seed = 1.35f,
        wiggleDeg = 15f,
    ),
    PetalSpec(
        color = SiriTeal,
        group = PetalColorGroup.Teal,
        intensity = 0.66f,
        bendDeg = -220f,
        twistDeg = -42f,
        rotX = 42f,
        rotY = 52f,
        rotZ = 8f,
        spinX = 0,
        spinY = 0,
        spinZ = -1,
        seed = 2.25f,
        wiggleDeg = 11f,
    ),
    PetalSpec(
        color = SiriTeal,
        group = PetalColorGroup.Teal,
        intensity = 0.76f,
        bendDeg = 168f,
        twistDeg = 150f,
        rotX = 78f,
        rotY = 8f,
        rotZ = 122f,
        spinX = 0,
        spinY = 0,
        spinZ = 2,
        seed = 3.15f,
        wiggleDeg = 14f,
    ),
    PetalSpec(
        color = SiriPink,
        group = PetalColorGroup.Red,
        intensity = 0.40f,
        bendDeg = 205f,
        twistDeg = 64f,
        rotX = -34f,
        rotY = 24f,
        rotZ = 208f,
        spinX = 1,
        spinY = 0,
        spinZ = 0,
        seed = 4.45f,
        wiggleDeg = 13f,
    ),
    PetalSpec(
        color = SiriPurple,
        group = PetalColorGroup.Blue,
        intensity = 0.32f,
        bendDeg = -200f,
        twistDeg = -88f,
        rotX = 60f,
        rotY = -24f,
        rotZ = 252f,
        spinX = 0,
        spinY = -2,
        spinZ = 0,
        seed = 5.6f,
        wiggleDeg = 10f,
    ),
)

// Stacked rim copies. halfWidth is the AE blur (250 / 80 / 57) as a fraction of the 461 shell.
private val RimBands = listOf(
    RimBand(Color(0xFFB0B0BA), radius = 1.00f, halfWidth = 0.18f, alpha = 0.28f, phase = 0.0f),
    RimBand(Color(0xFFFF9A32), radius = 0.988f, halfWidth = 0.070f, alpha = 0.46f, phase = 1.1f),
    RimBand(Color(0xFF3EE0E6), radius = 1.018f, halfWidth = 0.058f, alpha = 0.44f, phase = 2.4f),
    RimBand(Color(0xFFE07038), radius = 0.962f, halfWidth = 0.062f, alpha = 0.30f, phase = 3.6f),
    RimBand(Color(0xFFC6EEF0), radius = 1.006f, halfWidth = 0.050f, alpha = 0.34f, phase = 4.8f),
)

private val PetalIndices: List<Int> = buildList {
    fun id(i: Int, j: Int) = i * GridN + j
    for (i in 0 until Grid) {
        for (j in 0 until Grid) {
            val a = id(i, j)
            val b = id(i + 1, j)
            val c = id(i + 1, j + 1)
            val d = id(i, j + 1)
            add(a); add(b); add(c)
            add(a); add(c); add(d)
        }
    }
}

private val PetalTextureCoordinates: List<Offset> = List(PetalVertexCount) { index ->
    val i = index / GridN
    val j = index - i * GridN
    Offset(i.toFloat() / Grid, j.toFloat() / Grid)
}

private val RimIndices: List<Int> = buildList {
    for (segment in 0 until RimSegments) {
        val row = segment * RimRowVerts
        val next = row + RimRowVerts
        add(row); add(next); add(row + 1)
        add(row + 1); add(next); add(next + 1)
        add(row + 1); add(next + 1); add(row + 2)
        add(row + 2); add(next + 1); add(next + 2)
    }
}

private val RimTextureCoordinates: List<Offset> = List(RimVertexCount) { index ->
    val column = index / RimRowVerts
    val row = index - column * RimRowVerts
    Offset(column.toFloat() / RimSegments, row.toFloat() / 2f)
}

@Composable
internal fun SiriOrbVisual(
    phaseState: State<Float>,
    style: SiriOrbStyle,
    audioLevel: Float,
    modifier: Modifier = Modifier,
) {
    val petalPositions = remember {
        List(Petals.size) { MutableList(PetalVertexCount) { Offset.Zero } }
    }
    val petalColors = remember {
        List(Petals.size) { MutableList(PetalVertexCount) { Color.Transparent } }
    }
    val petalDepth = remember { FloatArray(Petals.size) }
    val rimPositions = remember {
        List(RimBands.size) { MutableList(RimVertexCount) { Offset.Zero } }
    }
    val rimColors = remember {
        List(RimBands.size) { MutableList(RimVertexCount) { Color.Transparent } }
    }
    val screenPaint = remember {
        Paint().apply {
            color = Color.White
            isAntiAlias = true
            blendMode = BlendMode.Plus
        }
    }

    Box(
        modifier.blur(8.dp, BlurredEdgeTreatment.Unbounded),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val phase = phaseState.value
            val orbScale = orbScale(phase, audioLevel, style)
            val shell = size.minDimension * ShellRadiusFraction * orbScale
            val focal = shell * CameraDistance / ShellRadiusAe
            drawRim(phase, shell, style, rimPositions, rimColors, screenPaint)
            drawCore(shell, style)
            Petals.forEachIndexed { index, spec ->
                petalDepth[index] = fillPetal(
                    spec = spec,
                    phase = phase,
                    style = style,
                    positions = petalPositions[index],
                    colors = petalColors[index],
                    focal = focal,
                    shell = shell,
                )
            }
            val order = petalDepth.indices.sortedByDescending { petalDepth[it] }
            for (index in order) {
                drawContext.canvas.drawVertices(
                    vertices = Vertices(
                        vertexMode = VertexMode.Triangles,
                        positions = petalPositions[index],
                        textureCoordinates = PetalTextureCoordinates,
                        colors = petalColors[index],
                        indices = PetalIndices,
                    ),
                    blendMode = BlendMode.Modulate,
                    paint = screenPaint,
                )
            }
        }
    }
}

private fun orbScale(phase: Float, audioLevel: Float, style: SiriOrbStyle): Float {
    // Sound Keys style: scale follows amplitude/40, with 40 as unity. Idle listening
    // leaves the orb at sizeScale. Harmonics are integer so the loop stays closed.
    val shaped = (
        0.62f +
            0.24f * sin(phase * 5f) +
            0.14f * sin(phase * 8f + 1.1f)
        ).coerceIn(0f, 1f)
    val amplitude = audioLevel.coerceIn(0f, 1f) * shaped * 100f
    val audioScale = (amplitude / 40f).coerceIn(0.4f, 1.7f)
    return style.sizeScale * (1f + style.pulseAmount * (audioScale - 1f))
}

private fun DrawScope.drawCore(shell: Float, style: SiriOrbStyle) {
    val whiteMix = 1f - style.errorAmount
    val coreColor = Color(
        red = 1f * whiteMix + SiriRed.red * style.errorAmount,
        green = 1f * whiteMix + SiriRed.green * style.errorAmount,
        blue = 1f * whiteMix + SiriRed.blue * style.errorAmount,
    )
    val alpha = style.bloomAlpha
    // Bright core is about 15% of the orb radius. Stops hit transparent inside the
    // drawn circle so the falloff is the feather, not the circle edge.
    val featherRadius = shell * 0.80f * style.bloomScale
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to coreColor.copy(alpha = 1f * alpha),
                0.10f to coreColor.copy(alpha = 0.82f * alpha),
                0.22f to coreColor.copy(alpha = 0.36f * alpha),
                0.42f to coreColor.copy(alpha = 0.10f * alpha),
                0.68f to coreColor.copy(alpha = 0.025f * alpha),
                1.00f to Color.Transparent,
            ),
            center = center,
            radius = featherRadius,
        ),
        radius = featherRadius,
        center = center,
        blendMode = BlendMode.Plus,
    )
    val glowRadius = shell * 1.05f
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to coreColor.copy(alpha = 0.06f * alpha),
                0.35f to coreColor.copy(alpha = 0.02f * alpha),
                0.70f to coreColor.copy(alpha = 0.006f * alpha),
                1.00f to Color.Transparent,
            ),
            center = center,
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = center,
        blendMode = BlendMode.Plus,
    )
}

private fun DrawScope.drawRim(
    phase: Float,
    shell: Float,
    style: SiriOrbStyle,
    positions: List<MutableList<Offset>>,
    colors: List<MutableList<Color>>,
    screenPaint: Paint,
) {
    val evolution = phase * TurbulenceCycles
    val amountPx = TurbulenceAmountAe * shell / ShellRadiusAe
    val alphaScale = style.opacityScale.coerceIn(0f, 1.15f) * style.bloomAlpha.coerceIn(0.35f, 1f)
    RimBands.forEachIndexed { bandIndex, band ->
        val position = positions[bandIndex]
        val color = colors[bandIndex]
        var vertex = 0
        for (column in 0..RimSegments) {
            val angle = column.toFloat() / RimSegments * TwoPi
            val wobble = turbulence(angle, evolution + band.phase)
            val sided = 0.72f + 0.28f * sin(angle + band.phase)
            val radius = shell * band.radius + wobble * amountPx
            val half = shell * band.halfWidth
            val radii = floatArrayOf(radius - half, radius, radius + half)
            val weights = floatArrayOf(0f, 1f, 0f)
            for (row in 0 until RimRowVerts) {
                val r = radii[row]
                val punch = smoothStep(((r / shell - 0.74f) / 0.14f).coerceIn(0f, 1f))
                val edgeFade = 1f - smoothStep(((r / shell - 1.08f) / 0.10f).coerceIn(0f, 1f))
                val alpha = (band.alpha * weights[row] * sided * punch * edgeFade * alphaScale)
                    .coerceIn(0f, 1f)
                position[vertex] = Offset(
                    center.x + cos(angle) * r,
                    center.y + sin(angle) * r,
                )
                color[vertex] = band.color.withAlpha(alpha)
                vertex++
            }
        }
        drawContext.canvas.drawVertices(
            vertices = Vertices(
                vertexMode = VertexMode.Triangles,
                positions = position,
                textureCoordinates = RimTextureCoordinates,
                colors = color,
                indices = RimIndices,
            ),
            blendMode = BlendMode.Modulate,
            paint = screenPaint,
        )
    }
}

private fun DrawScope.fillPetal(
    spec: PetalSpec,
    phase: Float,
    style: SiriOrbStyle,
    positions: MutableList<Offset>,
    colors: MutableList<Color>,
    focal: Float,
    shell: Float,
): Float {
    val wiggle = loopWiggle(phase, spec.seed) * spec.wiggleDeg * style.motionScale.coerceIn(0.15f, 1.5f)
    val open = 1f - style.separation * 0.38f
    val bendDeg = spec.bendDeg * open + wiggle
    val twistDeg = spec.twistDeg + wiggle * 0.85f
    val spin = phase / TwoPi
    val rotX = (spec.rotX + spec.spinX * 360f * spin + wiggle * 0.45f) * DegreesToRadians
    val rotY = (spec.rotY + spec.spinY * 360f * spin) * DegreesToRadians
    val rotZ = (spec.rotZ + spec.spinZ * 360f * spin) * DegreesToRadians
    val weight = when (spec.group) {
        PetalColorGroup.Red -> style.redWeight
        PetalColorGroup.Blue -> style.blueWeight
        PetalColorGroup.Teal -> style.tealWeight
    }
    val spread = 1f + style.separation * 0.22f
    val intensity = spec.intensity * style.opacityScale * weight
    val rotXCos = cos(rotX)
    val rotXSin = sin(rotX)
    val rotYCos = cos(rotY)
    val rotYSin = sin(rotY)
    val rotZCos = cos(rotZ)
    val rotZSin = sin(rotZ)

    var depthSum = 0f
    var vertex = 0
    for (i in 0..Grid) {
        val u = i.toFloat() / Grid * 2f - 1f
        for (j in 0..Grid) {
            val v = j.toFloat() / Grid * 2f - 1f
            val local = bendTwist(u, v, bendDeg, twistDeg)
            val x0 = local[0] * spread
            val y0 = local[1] * spread
            val z0 = local[2] * spread

            val y1 = y0 * rotXCos - z0 * rotXSin
            val z1 = y0 * rotXSin + z0 * rotXCos
            val x2 = x0 * rotYCos + z1 * rotYSin
            val z2 = -x0 * rotYSin + z1 * rotYCos
            val x3 = x2 * rotZCos - y1 * rotZSin
            val y3 = x2 * rotZSin + y1 * rotZCos

            // +z points at the camera. Depth increases away from it, matching AE fog.
            val depth = CameraDistance - z2
            val safeDepth = depth.coerceAtLeast(420f)
            val projection = focal / safeDepth
            val screenX = center.x + x3 * projection
            val screenY = center.y - y3 * projection
            positions[vertex] = Offset(screenX, screenY)

            val fogT = ((depth - FogStart) / FogRange).coerceIn(0f, 1f)
            val fog = 1f - smoothStep(fogT)
            val projected = hypot(screenX - center.x, screenY - center.y) / shell
            val shellFade = 1f - smoothStep(((projected - 0.90f) / 0.10f).coerceIn(0f, 1f))
            val alpha = (ringAlpha(u, v, style.fill) * intensity * fog * shellFade).coerceIn(0f, 1f)
            colors[vertex] = spec.color.withAlpha(alpha)
            depthSum += depth
            vertex++
        }
    }
    return depthSum / PetalVertexCount
}

/**
 * Ring plane wrapped onto an arc. u = 0 faces the camera; the ends swing behind
 * it so depth fog can hide them. Twist spins the cross-section around the arc.
 */
private fun bendTwist(u: Float, v: Float, bendDeg: Float, twistDeg: Float): FloatArray {
    val bend = bendDeg * DegreesToRadians
    if (abs(bend) < 0.001f) {
        return floatArrayOf(v * PlaneHalf, u * PlaneHalf, 0f)
    }
    val angle = u * bend * 0.5f
    // Sit the arc on the shell so a crescent fills the sphere instead of a small loop.
    val radius = ShellRadiusAe * 0.90f
    val alongY = sin(angle) * radius
    val alongZ = cos(angle) * radius
    val twist = u * twistDeg * 0.5f * DegreesToRadians
    val across = v * PlaneHalf
    val twistCos = cos(twist)
    val twistSin = sin(twist)
    // Mix the across-axis with the arc's outward direction.
    val x = across * twistCos
    val y = alongY + across * twistSin * sin(angle)
    val z = alongZ + across * twistSin * cos(angle)
    return floatArrayOf(x, y, z)
}

/**
 * Inverted circle mask with a feather large enough to cover the 1440 plane.
 * Alpha is 0 at the center, peaks near the edge, and falls off before the square border.
 * [fill] closes the hole so Thinking reads as solid petals.
 */
private fun ringAlpha(u: Float, v: Float, fill: Float): Float {
    val radius = hypot(u, v)
    // Peak near 0.72 (just inside the shell) and reach 0 before the square edge at r=1,
    // so the plane border never draws as a hard line.
    val clampedFill = fill.coerceIn(0f, 1f)
    val hole = 0.12f * (1f - clampedFill)
    val riseEnd = 0.78f - 0.50f * clampedFill
    val rise = smoothStep(((radius - hole) / (riseEnd - hole)).coerceIn(0f, 1f))
    val fall = 1f - smoothStep(((radius - 0.58f) / 0.40f).coerceIn(0f, 1f))
    val ring = rise * fall
    val solid = 1f - smoothStep(((radius - 0.78f) / 0.18f).coerceIn(0f, 1f))
    val filled = if (solid > ring) solid else ring
    return ring + (filled - ring) * fill.coerceIn(0f, 1f)
}

/** Periodic stand-in for wiggle(0.2, amplitude). Value is about -1..1. */
private fun loopWiggle(phase: Float, seed: Float): Float {
    val a = sin(phase * WiggleCycles + seed)
    val b = 0.42f * sin(phase * (WiggleCycles + 2f) + seed * 1.7f)
    val c = 0.20f * sin(phase * (WiggleCycles + 5f) + seed * 2.4f)
    return ((a + b + c) / 1.62f).coerceIn(-1f, 1f)
}

private fun turbulence(angle: Float, evolution: Float): Float {
    val n1 = sin(angle * TurbulenceFeatures + evolution)
    val n2 = sin(angle * (TurbulenceFeatures * 0.55f) - evolution * 1.6f + 0.7f)
    val n3 = sin(angle * 7f + evolution * 0.5f + 1.4f)
    return n1 * 0.58f + n2 * 0.28f + n3 * 0.14f
}

/** Straight-alpha color with RGB scaled by [alpha] so additive vertices stay correct. */
private fun Color.withAlpha(alpha: Float): Color {
    val a = alpha.coerceIn(0f, 1f)
    return Color(red * a, green * a, blue * a, a)
}

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
