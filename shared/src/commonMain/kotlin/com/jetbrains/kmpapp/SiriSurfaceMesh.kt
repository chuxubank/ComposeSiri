package com.jetbrains.kmpapp

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val TwoPi = 6.2831855f
private const val DegreesToRadians = 0.017453292f

// Length samples are packed toward the tips so the needle ends stay pointed.
private const val PetalU = 56
private const val PetalV = 22
private const val PetalVertexCount = (PetalU + 1) * (PetalV + 1)

// The surface canvas is the glass disc (see SiriOrb). Mesh radius 1 maps to
// half that disc, so 0.46 puts an unforeshortened tip at about 92% of the
// shell radius. Integer spin counts keep the 17.117 s loop seamless.
private const val TipReachFraction = 0.46f

private val CoreBloomBrush = Brush.radialGradient(
    colorStops = arrayOf(
        0.00f to Color.White.copy(alpha = 1f),
        0.22f to Color.White.copy(alpha = 0.94f),
        0.40f to Color.White.copy(alpha = 0.55f),
        0.56f to Color(0xFFE7F6FF).copy(alpha = 0.12f),
        0.72f to Color.Transparent,
        1.00f to Color.Transparent,
    ),
    center = Offset.Zero,
    radius = 1f,
)

private val ErrorBloomBrush = Brush.radialGradient(
    colorStops = arrayOf(
        0.00f to Color(0xFFFFF4F0).copy(alpha = 0.92f),
        0.28f to SiriRed.copy(alpha = 0.70f),
        0.62f to Color(0xFFFF1A2A).copy(alpha = 0.18f),
        1.00f to Color.Transparent,
    ),
    center = Offset.Zero,
    radius = 1f,
)

private data class SurfaceSpec(
    val color: Color,
    val length: Float,
    val halfWidth: Float,
    val bend: Float,
    val twist: Float,
    val bulge: Float,
    val softness: Float,
    val rotX: Float,
    val rotY: Float,
    val rotZ: Float,
    val spinX: Int,
    val spinZ: Int,
    val opacity: Float,
    val crispSign: Float,
    val seed: Float,
    val warp: Float,
)

private val PetalIndices: List<Int> = buildList {
    fun vertexId(i: Int, j: Int) = i * (PetalV + 1) + j
    for (i in 0 until PetalU) {
        for (j in 0 until PetalV) {
            val a = vertexId(i, j)
            val b = vertexId(i + 1, j)
            val c = vertexId(i + 1, j + 1)
            val d = vertexId(i, j + 1)
            add(a)
            add(b)
            add(c)
            add(a)
            add(c)
            add(d)
        }
    }
}

private val PetalTextureCoordinates: List<Offset> = List(PetalVertexCount) { index ->
    val i = index / (PetalV + 1)
    val j = index - i * (PetalV + 1)
    Offset(i.toFloat() / PetalU, j.toFloat() / PetalV)
}

private val PetalUValues: FloatArray = FloatArray(PetalU + 1) { index ->
    val t = index.toFloat() / PetalU
    // Mild tip bias. A strong bias facets the wide middle of the fan.
    val biased = if (t < 0.5f) {
        0.5f * (t * 2f).pow(0.9f)
    } else {
        1f - 0.5f * ((1f - t) * 2f).pow(0.9f)
    }
    biased * 2f - 1f
}

// Five Element 3D planes. Each is a wide fan: a bright flank, a long translucent
// body, and needle tips. Two turns on X/Z is the 42 deg/s family and closes
// on the 17.117 s loop. Y only rocks, so the long axis stays inside the shell.
private val SurfaceSpecs = listOf(
    // halfWidth is the centered half-span in tip-length units. 0.55 is about
    // half the shell diameter once the tip sits near 90% of the glass radius.
    SurfaceSpec(
        color = SiriRed,
        length = 1.02f,
        halfWidth = 0.62f,
        bend = 1.05f,
        twist = 0.14f,
        bulge = 0.08f,
        softness = 0.92f,
        rotX = 12f,
        rotY = 6f,
        rotZ = -24f,
        spinX = 2,
        spinZ = -2,
        opacity = 0.34f,
        crispSign = 1f,
        seed = 0.4f,
        warp = 0.06f,
    ),
    SurfaceSpec(
        color = SiriBlue,
        length = 1.00f,
        halfWidth = 0.56f,
        bend = 1.28f,
        twist = -0.16f,
        bulge = -0.06f,
        softness = 0.80f,
        rotX = 24f,
        rotY = -8f,
        rotZ = 62f,
        spinX = -2,
        spinZ = 2,
        opacity = 0.46f,
        crispSign = -1f,
        seed = 1.8f,
        warp = 0.07f,
    ),
    SurfaceSpec(
        color = SiriTeal,
        length = 1.00f,
        halfWidth = 0.52f,
        bend = -1.08f,
        twist = 0.14f,
        bulge = 0.08f,
        softness = 0.84f,
        rotX = 18f,
        rotY = 10f,
        rotZ = 138f,
        spinX = 2,
        spinZ = 2,
        opacity = 0.40f,
        crispSign = 1f,
        seed = 3.2f,
        warp = 0.06f,
    ),
    SurfaceSpec(
        color = SiriRed,
        length = 1.04f,
        halfWidth = 0.38f,
        bend = 1.42f,
        twist = -0.20f,
        bulge = 0.10f,
        softness = 0.58f,
        rotX = 36f,
        rotY = -7f,
        rotZ = 206f,
        spinX = -2,
        spinZ = -2,
        opacity = 0.52f,
        crispSign = -1f,
        seed = 4.6f,
        warp = 0.08f,
    ),
    SurfaceSpec(
        color = SiriGreen,
        length = 0.98f,
        halfWidth = 0.50f,
        bend = -1.22f,
        twist = 0.12f,
        bulge = -0.08f,
        softness = 0.76f,
        rotX = 28f,
        rotY = 9f,
        rotZ = 284f,
        spinX = 2,
        spinZ = -2,
        opacity = 0.32f,
        crispSign = 1f,
        seed = 6.1f,
        warp = 0.07f,
    ),
)

@Composable
internal fun SiriSurfaceField(
    phaseState: State<Float>,
    style: SiriOrbStyle,
    audioLevel: Float,
    widthScale: Float = 1f,
    alphaScale: Float = 1f,
    emphasizeEdge: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val positions = remember {
        List(SurfaceSpecs.size) { MutableList(PetalVertexCount) { Offset.Zero } }
    }
    val colors = remember {
        List(SurfaceSpecs.size) { MutableList(PetalVertexCount) { Color.Transparent } }
    }
    val paint = remember {
        Paint().apply {
            // Modulate against this white keeps the vertex color. Plus is the
            // composite onto the canvas, so overlapping sheets brighten.
            color = Color.White
            isAntiAlias = true
            blendMode = BlendMode.Plus
        }
    }

    Canvas(modifier) {
        val phase = phaseState.value
        val voiceWave = (
            0.55f +
                0.28f * sin(phase * 7f + 0.2f) +
                0.17f * sin(phase * 11f + 1.4f)
            ).coerceIn(0f, 1f)
        val pulse = style.pulseAmount * audioLevel * voiceWave
        val tipReach = size.minDimension * TipReachFraction * style.sizeScale *
            (1f + pulse * 0.06f)
        val core = center
        SurfaceSpecs.forEachIndexed { index, spec ->
            val colorWeight = when (spec.color) {
                SiriRed -> style.redWeight
                SiriBlue -> style.blueWeight
                else -> style.tealWeight
            }
            drawPetal(
                spec = spec,
                pose = spec.anglesAt(phase),
                phase = phase,
                positions = positions[index],
                colors = colors[index],
                core = core,
                tipReach = tipReach,
                opacityScale = style.opacityScale * colorWeight * alphaScale,
                widthScale = widthScale,
                emphasizeEdge = emphasizeEdge,
                paint = paint,
            )
        }
    }
}

@Composable
internal fun SiriCoreBloom(
    phaseState: State<Float>,
    style: SiriOrbStyle,
    audioLevel: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val phase = phaseState.value
        val voiceWave = (
            0.55f +
                0.28f * sin(phase * 7f + 0.2f) +
                0.17f * sin(phase * 11f + 1.4f)
            ).coerceIn(0f, 1f)
        val pulse = style.pulseAmount * audioLevel * voiceWave
        val tipReach = size.minDimension * TipReachFraction * style.sizeScale *
            (1f + pulse * 0.06f)
        drawCoreBloom(center, tipReach, style, pulse)
    }
}

private fun DrawScope.drawCoreBloom(
    core: Offset,
    tipReach: Float,
    style: SiriOrbStyle,
    pulse: Float,
) {
    // Soft white core, cut off before it fogs the dark gaps between lobes.
    val bloomRadius = tipReach * style.bloomScale * (0.62f + pulse * 0.03f)
    withTransform({
        translate(core.x, core.y)
        scale(bloomRadius, bloomRadius, pivot = Offset.Zero)
    }) {
        drawCircle(
            brush = CoreBloomBrush,
            center = Offset.Zero,
            radius = 1f,
            alpha = style.bloomAlpha * (1f - style.errorAmount),
            blendMode = BlendMode.Plus,
        )
        drawCircle(
            brush = ErrorBloomBrush,
            center = Offset.Zero,
            radius = 1f,
            alpha = style.bloomAlpha * style.errorAmount,
            blendMode = BlendMode.Plus,
        )
    }
}

private fun SurfaceSpec.anglesAt(phase: Float): FloatArray {
    val spin = phase / TwoPi
    // Y only rocks. A full turn on Y would swing the long axis at the camera
    // and pull the tips off the shell. X and Z spin at ±2 turns (42.1 deg/s).
    val tiltY = rotY + sin(phase + seed) * 12f
    return floatArrayOf(
        (rotX + spinX * 360f * spin) * DegreesToRadians,
        tiltY * DegreesToRadians,
        (rotZ + spinZ * 360f * spin) * DegreesToRadians,
    )
}

private fun DrawScope.drawPetal(
    spec: SurfaceSpec,
    pose: FloatArray,
    phase: Float,
    positions: MutableList<Offset>,
    colors: MutableList<Color>,
    core: Offset,
    tipReach: Float,
    opacityScale: Float,
    widthScale: Float,
    emphasizeEdge: Boolean,
    paint: Paint,
) {
    val rotXCos = cos(pose[0])
    val rotXSin = sin(pose[0])
    val rotYCos = cos(pose[1])
    val rotYSin = sin(pose[1])
    val rotZCos = cos(pose[2])
    val rotZSin = sin(pose[2])
    val bend = spec.bend + 0.08f * sin(phase * 2f + spec.seed)
    val evoX = cos(phase + spec.seed)
    val evoY = sin(phase + spec.seed)
    val halfWidth = spec.halfWidth * widthScale
    // Edge-on sheets would collapse to a hair. Hold a slim ribbon so the
    // bright flank still reads as a needle instead of a one-pixel line.
    val facing = abs(rotXCos).coerceAtLeast(0.02f)
    val widthKeep = (0.30f / facing).coerceIn(1f, 2.6f)

    var vertex = 0
    for (i in 0..PetalU) {
        val u = PetalUValues[i]
        val along = abs(u)
        val taper = taperAt(u, spec.bulge)
        val n1 = turbulence(u, spec.seed, evoX, evoY)
        val arc = centerline(u, bend)
        val acrossAmp = halfWidth * taper * (1f + spec.warp * n1)
        val wobble = spec.warp * 0.06f * n1 * taper
        val twistAngle = u * spec.twist
        val twistCos = cos(twistAngle)
        val twistSin = sin(twistAngle)
        for (j in 0..PetalV) {
            val t0 = j.toFloat() / PetalV
            val t = if (spec.crispSign < 0f) 1f - t0 else t0
            // Centered on the arc so the sides stay inside the glass and only the
            // pinched tips reach the rim. t = 0 is the bright flank.
            val across = ((t - 0.5f) * 2f * acrossAmp + wobble) * widthKeep
            val localX = (arc.x + arc.px * across * twistCos) * arc.norm * spec.length
            val localY = (arc.y + arc.py * across * twistCos) * arc.norm * spec.length
            val localZ = across * twistSin * 0.28f

            val rotatedY = localY * rotXCos - localZ * rotXSin
            val rotatedZ = localY * rotXSin + localZ * rotXCos
            val rotatedX2 = localX * rotYCos + rotatedZ * rotYSin
            val rotatedZ2 = -localX * rotYSin + rotatedZ * rotYCos
            val rotatedX = rotatedX2 * rotZCos - rotatedY * rotZSin
            val rotatedY2 = rotatedX2 * rotZSin + rotatedY * rotZCos

            val perspective = 1f / (1f + rotatedZ2 * 0.12f).coerceAtLeast(0.72f)
            val screenX = core.x + tipReach * rotatedX * perspective
            val screenY = core.y + tipReach * rotatedY2 * perspective
            positions[vertex] = Offset(screenX, screenY)

            val dx = (screenX - core.x) / tipReach
            val dy = (screenY - core.y) / tipReach
            val dist = sqrt(dx * dx + dy * dy)
            val profile = alphaProfile(t, along, spec.softness, emphasizeEdge)
            // Let the core bloom own the middle so the sheets read as color, not a white disc.
            // Keep the wide middle inside the glass. Needle tips are allowed to reach it.
            val tipKeep = smoothStep(((along - 0.60f) / 0.32f).coerceIn(0f, 1f))
            val outer = smoothStep(((dist - 0.84f) / 0.20f).coerceIn(0f, 1f))
            val shellFade = 1f - outer * (1f - tipKeep)
            val alpha = (spec.opacity * opacityScale * profile * shellFade).coerceIn(0f, 1f)
            val edgeLight = exp(-(t * t) / 0.018f)
            val brightness = 0.96f + 0.20f * edgeLight
            val white = edgeLight * if (emphasizeEdge) 0.22f else 0.02f
            colors[vertex] = Color(
                red = (spec.color.red * brightness + white).coerceIn(0f, 1f),
                green = (spec.color.green * brightness + white).coerceIn(0f, 1f),
                blue = (spec.color.blue * brightness + white).coerceIn(0f, 1f),
                alpha = alpha,
            )
            vertex++
        }
    }
    drawContext.canvas.drawVertices(
        vertices = Vertices(
            vertexMode = VertexMode.Triangles,
            positions = positions,
            textureCoordinates = PetalTextureCoordinates,
            colors = colors,
            indices = PetalIndices,
        ),
        // Combines vertex color with the white paint. Destination blend is paint.blendMode.
        blendMode = BlendMode.Modulate,
        paint = paint,
    )
}

private data class Centerline(
    val x: Float,
    val y: Float,
    val px: Float,
    val py: Float,
    val norm: Float,
)

private fun centerline(u: Float, bend: Float): Centerline {
    if (abs(bend) < 0.001f) {
        return Centerline(x = u, y = 0f, px = 0f, py = 1f, norm = 1f)
    }
    val angle = u * bend
    val x = sin(angle) / bend
    val y = (1f - cos(angle)) / bend
    val tangentX = cos(angle)
    val tangentY = sin(angle)
    val endX = sin(bend) / bend
    val endY = (1f - cos(bend)) / bend
    val endRadius = sqrt(endX * endX + endY * endY).coerceAtLeast(0.0001f)
    return Centerline(
        x = x,
        y = y,
        px = -tangentY,
        py = tangentX,
        norm = 1f / endRadius,
    )
}

private fun taperAt(u: Float, bulge: Float): Float {
    // Wide fan through the body. The last quarter collapses to a needle tip.
    val along = abs(u)
    val base = if (along <= 0.62f) {
        1f - 0.04f * (along / 0.62f).pow(2f)
    } else {
        val t = (along - 0.62f) / 0.38f
        0.96f * (1f - t).pow(2.5f)
    }
    return (base * (1f + bulge * u * (1f - along))).coerceAtLeast(0f)
}

// t = 0 is the crisp flank, t = 1 is the soft flank.
private fun alphaProfile(
    t: Float,
    along: Float,
    softness: Float,
    emphasizeEdge: Boolean,
): Float {
    val tip = smoothStep(((along - 0.70f) / 0.30f).coerceIn(0f, 1f))
    if (emphasizeEdge) {
        val band = exp(-(t * t) / 0.02f)
        return (band * (0.85f + 0.15f * tip)).coerceIn(0f, 1f)
    }
    // Soft interior. The sharp pass supplies the bright flank and the needles.
    val flank = exp(-(t * t) / (0.03f + 0.02f * softness))
    val veilT = smoothStep(((t - 0.04f) / 0.78f).coerceIn(0f, 1f))
    val veil = (1f - veilT).pow(0.50f)
    val veilWeight = 0.28f + 0.30f * softness
    return (flank * 0.28f + veil * veilWeight).coerceIn(0f, 1f)
}

private fun turbulence(u: Float, seed: Float, evoX: Float, evoY: Float): Float {
    val n1 = sin(u * 2.1f + seed * 1.3f + evoX * 1.2f)
    val n2 = sin(u * 3.7f + seed * 2.4f + evoY * 0.9f)
    return (n1 * 0.75f + n2 * 0.25f).coerceIn(-1f, 1f)
}

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
