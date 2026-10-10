package com.jetbrains.kmpapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val TwoPi = 6.2831855f
private const val DegreesToRadians = 0.017453292f

// Cropped preview frame. The AE shell is the 922 ellipse on the 1440 precomp
// (64% of that comp). In this frame the stroke sits near the outer edge of the
// reference crop, about 86% of the canvas across, not a band that reaches the corners.
private const val ShellRadiusFraction = 0.43f
private const val ShellRadiusAe = 461f
private const val CameraDistance = 2700f
// Fog only the far side. The camera-facing face stays flat (lighting variation 0).
private const val FogStart = 2760f
private const val FogRange = 420f
// Across-axis half extent of each ring plane, in AE units.
// Cross-section half-width. Narrower than the shell so face-on ribbons leave
// near-black gaps; edge-on twist still pinches them to a needle.
private const val PlaneHalf = 210f

// 0.2 Hz over a 17.117 s loop is 3.42 cycles. 3 cycles stays periodic and seamless.
private const val WiggleCycles = 3f

// time*80 deg/s is 3.80 revolutions per loop. 4 revolutions stays periodic.
private const val TurbulenceCycles = 4f
private const val TurbulenceFeatures = 19f
private const val TurbulenceAmountAe = 7f

// AE wiggle(0.2, 15) on bend and wiggle(0.2, 10) on twist, in degrees.
private const val BendWiggleDeg = 15f
private const val TwistWiggleDeg = 10f

// Polar annulus. The outer ring is the mesh boundary, so the crisp edge is a
// smooth polyline instead of a cartesian grid cut by an alpha mask.
// ~3px on the 870 preview so the sharp outer polyline does not read as teeth.
private const val RingSegments = 840
private const val RingBands = 14
private const val RingNv = RingBands + 1
private const val PetalVertexCount = RingSegments * RingNv
private const val OuterRho = 0.985f

private const val RimSegments = 160
private const val RimColumns = RimSegments + 1
private const val RimRowVerts = 3
private const val RimVertexCount = RimColumns * RimRowVerts

// Element material: glossiness 0, lighting variation 0, environment exposure 2.
// No specular and no lambert gradient. Exposure is a flat gain on the ribbon.
private const val ExposureGain = 1.12f

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
)

// AE stroke #4D2D44. One soft line, not a stack of wide grey bands.
private val RimColor = Color(0xFF4D2D44)

// Six planes. Spin counts are integer turns per 17.117 s loop so the standard
// loop closes: +2 turns ≈ time*45 deg/s (42.1), -1 turn ≈ -time*30 deg/s (21.0).
private val Petals = listOf(
    PetalSpec(
        color = SiriRed,
        group = PetalColorGroup.Red,
        intensity = 1f,
        bendDeg = 228f,
        twistDeg = 86f,
        rotX = 52f,
        rotY = -18f,
        rotZ = -28f,
        spinX = 0,
        spinY = 2,
        spinZ = 0,
        seed = 0.4f,
    ),
    PetalSpec(
        color = SiriBlue,
        group = PetalColorGroup.Blue,
        intensity = 1f,
        bendDeg = 206f,
        twistDeg = 188f,
        rotX = 68f,
        rotY = 16f,
        rotZ = 36f,
        spinX = -1,
        spinY = 0,
        spinZ = 0,
        seed = 1.35f,
    ),
    PetalSpec(
        color = SiriTeal,
        group = PetalColorGroup.Teal,
        intensity = 0.9f,
        bendDeg = -220f,
        twistDeg = -110f,
        rotX = 44f,
        rotY = 54f,
        rotZ = 8f,
        spinX = 0,
        spinY = 0,
        spinZ = -1,
        seed = 2.25f,
    ),
    PetalSpec(
        color = SiriTeal,
        group = PetalColorGroup.Teal,
        intensity = 0.50f,
        bendDeg = 188f,
        twistDeg = 200f,
        rotX = 80f,
        rotY = 10f,
        rotZ = 122f,
        spinX = 0,
        spinY = 0,
        spinZ = 2,
        seed = 3.15f,
    ),
    PetalSpec(
        color = SiriPink,
        group = PetalColorGroup.Red,
        intensity = 0.28f,
        bendDeg = 200f,
        twistDeg = 140f,
        rotX = -36f,
        rotY = 26f,
        rotZ = 208f,
        spinX = 1,
        spinY = 0,
        spinZ = 0,
        seed = 4.45f,
    ),
    PetalSpec(
        color = SiriPurple,
        group = PetalColorGroup.Blue,
        intensity = 0.22f,
        bendDeg = -198f,
        twistDeg = -186f,
        rotX = 62f,
        rotY = -26f,
        rotZ = 252f,
        spinX = 0,
        spinY = -2,
        spinZ = 0,
        seed = 5.6f,
    ),
)

private val PetalIndices: List<Int> = buildList {
    fun id(i: Int, j: Int) = i * RingNv + j
    for (i in 0 until RingSegments) {
        val next = (i + 1) % RingSegments
        for (j in 0 until RingBands) {
            val a = id(i, j)
            val b = id(next, j)
            val c = id(next, j + 1)
            val d = id(i, j + 1)
            add(a); add(b); add(c)
            add(a); add(c); add(d)
        }
    }
}

private val PetalTextureCoordinates: List<Offset> = List(PetalVertexCount) { index ->
    val i = index / RingNv
    val j = index - i * RingNv
    Offset(i.toFloat() / RingSegments, j.toFloat() / RingBands)
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

/**
 * Integrates spin so a change in [SiriOrbStyle.rotationScale] slows the orb
 * without rewinding it. Integer turns still close the standard loop.
 */
private class SpinClock {
    private var turns = Float.NaN
    private var lastPhase = 0f

    fun advance(phase: Float, rotationScale: Float): Float {
        val scale = rotationScale.coerceIn(0f, 2.5f)
        if (turns.isNaN()) {
            lastPhase = phase
            turns = phase / TwoPi * scale
            return turns
        }
        if (phase == lastPhase) return turns
        var delta = (phase - lastPhase) / TwoPi
        if (delta > 0.5f) delta -= 1f
        if (delta < -0.5f) delta += 1f
        lastPhase = phase
        turns += delta * scale
        return turns
    }
}

@Composable
internal fun SiriOrbVisual(
    phaseState: State<Float>,
    style: SiriOrbStyle,
    audioLevel: Float,
    modifier: Modifier = Modifier,
) {
    val spinClock = remember { SpinClock() }
    val petalPositions = remember {
        List(Petals.size) { MutableList(PetalVertexCount) { Offset.Zero } }
    }
    val petalColors = remember {
        List(Petals.size) { MutableList(PetalVertexCount) { Color.Transparent } }
    }
    val petalDepth = remember { FloatArray(Petals.size) }
    val rimPositions = remember { MutableList(RimVertexCount) { Offset.Zero } }
    val rimColors = remember { MutableList(RimVertexCount) { Color.Transparent } }
    val screenPaint = remember {
        Paint().apply {
            color = Color.White
            isAntiAlias = true
            blendMode = BlendMode.Screen
        }
    }
    val rimPaint = remember {
        Paint().apply {
            color = Color.White
            isAntiAlias = true
            blendMode = BlendMode.SrcOver
        }
    }
    val turns = spinClock.advance(phaseState.value, style.rotationScale)

    // Blur only the glow copy. The base petals stay sharp.
    BoxWithConstraints(modifier) {
        val glowBlur = maxWidth * 0.010f
        Canvas(
            Modifier
                .fillMaxSize()
                .blur(glowBlur, BlurredEdgeTreatment.Unbounded),
        ) {
            val phase = phaseState.value
            val shell = shellRadius(phase, audioLevel, style)
            if (shell < 1f) return@Canvas
            val focal = shell * CameraDistance / ShellRadiusAe
            drawPetals(
                phase = phase,
                style = style,
                turns = turns,
                colorScale = 0.08f,
                positions = petalPositions,
                colors = petalColors,
                depth = petalDepth,
                focal = focal,
                shell = shell,
                paint = screenPaint,
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val phase = phaseState.value
            val shell = shellRadius(phase, audioLevel, style)
            if (shell < 1f) return@Canvas
            val focal = shell * CameraDistance / ShellRadiusAe
            drawPetals(
                phase = phase,
                style = style,
                turns = turns,
                colorScale = 1f,
                positions = petalPositions,
                colors = petalColors,
                depth = petalDepth,
                focal = focal,
                shell = shell,
                paint = screenPaint,
            )
            drawCore(shell, style)
            drawRim(phase, shell, style, rimPositions, rimColors, rimPaint)
        }
    }
}

private fun DrawScope.shellRadius(phase: Float, audioLevel: Float, style: SiriOrbStyle): Float {
    return size.minDimension * ShellRadiusFraction * orbScale(phase, audioLevel, style)
}

private fun orbScale(phase: Float, audioLevel: Float, style: SiriOrbStyle): Float {
    // Sound Keys style: world scale += amplitude/40, with 40 as unity.
    // audioLevel 0..1 maps onto an amplitude of 0..100. A wobble keeps a
    // constant level visibly pulsing instead of a static zoom.
    val amplitude = audioLevel.coerceIn(0f, 1f) * 100f
    val wobble = 0.55f + 0.45f * sin(phase * 5f)
    val delta = ((amplitude / 40f) - 1f) * wobble
    val pulse = style.pulseAmount * delta.coerceIn(-0.08f, 0.07f)
    return (style.sizeScale * (1f + pulse)).coerceAtLeast(0f)
}

private fun DrawScope.drawCore(shell: Float, style: SiriOrbStyle) {
    val whiteMix = 1f - style.errorAmount
    val coreColor = Color(
        red = 1f * whiteMix + SiriRed.red * style.errorAmount,
        green = 1f * whiteMix + SiriRed.green * style.errorAmount,
        blue = 1f * whiteMix + SiriRed.blue * style.errorAmount,
    )
    // Overlap glow. It fades when petals separate, and the alpha falls the
    // whole way out so the center is a soft white, not a flat disk.
    val overlap = (1f - style.separation * 0.72f).coerceIn(0.2f, 1f)
    val alpha = (style.bloomAlpha * style.opacityScale * overlap).coerceIn(0f, 1.2f)
    if (alpha <= 0.001f) return
    val featherRadius = shell * 0.74f * style.bloomScale.coerceIn(0.25f, 1.4f)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to coreColor.copy(alpha = 0.98f * alpha),
                0.16f to coreColor.copy(alpha = 0.90f * alpha),
                0.32f to coreColor.copy(alpha = 0.66f * alpha),
                0.50f to coreColor.copy(alpha = 0.32f * alpha),
                0.70f to coreColor.copy(alpha = 0.09f * alpha),
                0.88f to coreColor.copy(alpha = 0.02f * alpha),
                1.00f to Color.Transparent,
            ),
            center = center,
            radius = featherRadius,
        ),
        radius = featherRadius,
        center = center,
        blendMode = BlendMode.Screen,
    )
}

private fun DrawScope.drawRim(
    phase: Float,
    shell: Float,
    style: SiriOrbStyle,
    position: MutableList<Offset>,
    color: MutableList<Color>,
    rimPaint: Paint,
) {
    val alphaScale = style.opacityScale.coerceIn(0f, 1.2f)
    if (alphaScale <= 0.001f) return
    val evolution = phase * TurbulenceCycles
    val amountPx = TurbulenceAmountAe * shell / ShellRadiusAe
    // Thin soft plum stroke. halfWidth is a few percent of the shell, not a band.
    val half = shell * 0.010f
    val peak = 0.22f
    var vertex = 0
    for (column in 0..RimSegments) {
        val angle = column.toFloat() / RimSegments * TwoPi
        val wobble = turbulence(angle, evolution)
        val radius = shell + wobble * amountPx * 0.65f
        val radii = floatArrayOf(radius - half, radius, radius + half)
        val weights = floatArrayOf(0f, 1f, 0f)
        for (row in 0 until RimRowVerts) {
            val alpha = (peak * weights[row] * alphaScale).coerceIn(0f, 1f)
            position[vertex] = Offset(
                center.x + cos(angle) * radii[row],
                center.y + sin(angle) * radii[row],
            )
            color[vertex] = RimColor.withAlpha(alpha)
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
        paint = rimPaint,
    )
}

private fun DrawScope.drawPetals(
    phase: Float,
    style: SiriOrbStyle,
    turns: Float,
    colorScale: Float,
    positions: List<MutableList<Offset>>,
    colors: List<MutableList<Color>>,
    depth: FloatArray,
    focal: Float,
    shell: Float,
    paint: Paint,
) {
    Petals.forEachIndexed { index, spec ->
        depth[index] = fillPetal(
            spec = spec,
            phase = phase,
            style = style,
            turns = turns,
            colorScale = colorScale,
            positions = positions[index],
            colors = colors[index],
            focal = focal,
            shell = shell,
        )
    }
    val order = depth.indices.sortedByDescending { depth[it] }
    for (index in order) {
        drawContext.canvas.drawVertices(
            vertices = Vertices(
                vertexMode = VertexMode.Triangles,
                positions = positions[index],
                textureCoordinates = PetalTextureCoordinates,
                colors = colors[index],
                indices = PetalIndices,
            ),
            blendMode = BlendMode.Modulate,
            paint = paint,
        )
    }
}

private fun DrawScope.fillPetal(
    spec: PetalSpec,
    phase: Float,
    style: SiriOrbStyle,
    turns: Float,
    colorScale: Float,
    positions: MutableList<Offset>,
    colors: MutableList<Color>,
    focal: Float,
    shell: Float,
): Float {
    val motion = style.motionScale.coerceIn(0f, 1.6f)
    val bendWiggle = loopWiggle(phase, spec.seed) * BendWiggleDeg * motion
    val twistWiggle = loopWiggle(phase, spec.seed + 1.7f) * TwistWiggleDeg * motion
    val open = 1f - style.separation * 0.46f
    val bendDeg = spec.bendDeg * open + bendWiggle
    val twistDeg = spec.twistDeg + twistWiggle
    val rotX = (spec.rotX + spec.spinX * 360f * turns + bendWiggle * 0.22f) * DegreesToRadians
    val rotY = (spec.rotY + spec.spinY * 360f * turns) * DegreesToRadians
    val rotZ = (spec.rotZ + spec.spinZ * 360f * turns) * DegreesToRadians
    val weight = when (spec.group) {
        PetalColorGroup.Red -> style.redWeight
        PetalColorGroup.Blue -> style.blueWeight
        PetalColorGroup.Teal -> style.tealWeight
    }
    val spread = 1f + style.separation * 0.16f
    val push = style.separation * 150f
    val intensity = spec.intensity * style.opacityScale * weight * ExposureGain
    val rotXCos = cos(rotX)
    val rotXSin = sin(rotX)
    val rotYCos = cos(rotY)
    val rotYSin = sin(rotY)
    val rotZCos = cos(rotZ)
    val rotZSin = sin(rotZ)

    var depthSum = 0f
    var vertex = 0
    for (i in 0 until RingSegments) {
        val theta = i.toFloat() / RingSegments * TwoPi
        val around = cos(theta)
        val across = sin(theta)
        for (j in 0..RingBands) {
            val rho = j.toFloat() / RingBands * OuterRho
            val u = rho * around
            val v = rho * across
            val local = bendTwist(u, v, bendDeg, twistDeg)
            val x0 = local[0] * spread + push
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
            // Clip only past the shell. The ribbon's own edge stays sharp inside it.
            val shellFade = 1f - smoothStep(((projected - 1.01f) / 0.035f).coerceIn(0f, 1f))
            val mask = ringMask(u, v, style.fill)
            // One crisp lip on the outer ring. The body stays the petal color and
            // feathers inward; it is not a second white band.
            val lip = when {
                j == RingBands -> 1f
                j == RingBands - 1 -> 0.22f
                else -> 0f
            }
            val coverage = mask.coverage
            val alpha = (coverage * intensity * fog * shellFade * colorScale).coerceIn(0f, 1f)
            val whiten = lip * 0.42f
            val src = spec.color
            colors[vertex] = Color(
                red = src.red + (1f - src.red) * whiten,
                green = src.green + (1f - src.green) * whiten,
                blue = src.blue + (1f - src.blue) * whiten,
            ).withAlpha(alpha)
            depthSum += depth
            vertex++
        }
    }
    return depthSum / PetalVertexCount
}

/**
 * Ring plane wrapped onto an arc. u = 0 faces the camera; the ends swing behind
 * it so depth fog can hide them. Twist spins the cross-section around the arc
 * so a large twist pinches the ribbon to an edge-on needle.
 */
private fun bendTwist(u: Float, v: Float, bendDeg: Float, twistDeg: Float): FloatArray {
    val bend = bendDeg * DegreesToRadians
    if (abs(bend) < 0.001f) {
        return floatArrayOf(v * PlaneHalf, u * PlaneHalf, 0f)
    }
    val angle = u * bend * 0.5f
    val radius = ShellRadiusAe * 0.92f
    val alongY = sin(angle) * radius
    val alongZ = cos(angle) * radius
    val twist = u * twistDeg * 0.5f * DegreesToRadians
    val across = v * PlaneHalf
    val twistCos = cos(twist)
    val twistSin = sin(twist)
    val x = across * twistCos
    val y = alongY + across * twistSin * sin(angle)
    val z = alongZ + across * twistSin * cos(angle)
    return floatArrayOf(x, y, z)
}

private data class RingMask(val coverage: Float, val edge: Float)

/**
 * Circle mask whose outer edge is sharp. Feather travels inward only.
 * [fill] shrinks the center hole until the petal reads solid (processing).
 */
private fun ringMask(u: Float, v: Float, fill: Float): RingMask {
    val radius = hypot(u, v)
    val t = fill.coerceIn(0f, 1f)
    val outer = 0.992f
    val outside = 1f - smoothStep(((radius - outer) / 0.010f).coerceIn(0f, 1f))
    val hole = 0.40f * (1f - t)
    val featherWidth = (0.56f * (1f - t) + 0.04f * t).coerceAtLeast(0.035f)
    val body = smoothStep(((radius - hole) / featherWidth).coerceIn(0f, 1f))
    val coverage = outside * body
    val lip = smoothStep(((radius - (outer - 0.08f)) / 0.08f).coerceIn(0f, 1f))
    return RingMask(coverage = coverage, edge = outside * lip)
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

/** Straight-alpha color with RGB scaled by [alpha] so screen vertices stay correct. */
private fun Color.withAlpha(alpha: Float): Color {
    val a = alpha.coerceIn(0f, 1f)
    return Color(red * a, green * a, blue * a, a)
}

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
