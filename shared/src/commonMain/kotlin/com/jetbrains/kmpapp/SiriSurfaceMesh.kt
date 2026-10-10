package com.jetbrains.kmpapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

private const val TwoPi = 6.2831855f
private const val Pi = 3.1415927f
private const val DegreesToRadians = 0.017453292f

// Cropped preview frame. The AE shell is the 922 ellipse on the 1440 precomp.
// In this frame the stroke sits near the outer edge of the reference crop.
private const val ShellRadiusFraction = 0.43f
private const val ShellRadiusAe = 461f
private const val CameraDistance = 2700f
// Dim only the far side. The camera-facing face stays bright.
private const val FogStart = 2800f
private const val FogRange = 520f

// 0.2 Hz over a 17.117 s loop is 3.42 cycles. 3 cycles stays periodic and seamless.
private const val WiggleCycles = 3f

// Bank around the long axis turns a blade edge-on without shortening it.
// Pitch toward the camera stays smaller so the tips still reach the glass.
private const val BankFlipDeg = 140f
private const val PitchFlipDeg = 34f

// Bloom is a tight halo on the bright lip only, about 5% of the shell radius.
private const val BloomRadiusFractionOfShell = 0.05f
private const val LipPx = 1.7f
// Blades reach ~90% of the glass radius.
private const val LengthScale = 1.32f
// Soft translucent body around each blade (AE feathered ring texture, clear centre).
private const val BodyWidthScale = 4.2f
private const val BodyAlpha = 0.5f
private const val BodyBlurFractionOfShell = 0.07f
private const val ModeSheet = 0
private const val ModeEdge = 1
private const val ModeBody = 2

private const val LengthSegments = 96
private const val WidthSegments = 8
private const val LengthVerts = LengthSegments + 1
private const val WidthVerts = WidthSegments + 1
private const val PetalVertexCount = LengthVerts * WidthVerts
private const val MidColumn = LengthSegments / 2
private const val MidRow = WidthSegments / 2

// Inset from the silhouette, in screen pixels. The outer band is the 1–2px lip.
// The middle row is the centerline (clamped).
private const val BloomBandPx = 6.5f

private val RowInsetPx = floatArrayOf(0f, LipPx, BloomBandPx, 26f, 10_000f, 26f, BloomBandPx, LipPx, 0f)

private enum class PetalColorGroup {
    Red,
    Blue,
    Teal,
}

private data class PetalSpec(
    val color: Color,
    val group: PetalColorGroup,
    val intensity: Float,
    val halfLength: Float,
    val halfWidth: Float,
    val bendDeg: Float,
    val twistDeg: Float,
    val bias: Float,
    val curl: Float,
    val rotX: Float,
    val rotY: Float,
    val rotZ: Float,
    val spinX: Int,
    val spinY: Int,
    val spinZ: Int,
    val shift: Float,
    val seed: Float,
)

// AE stroke #4D2D44, one pixel at low opacity. Not a grey band.
private val RimColor = Color(0xFFC26A9C)
private val RimHighlight = Color(0xFFFFD4E6)

// Six blades. Spins are integer turns per 17.117 s loop.
// spinX banks the sheet edge-on (length stays). spinZ yaws it in the glass.
// Widths are a thin sheet. Rest banks are spread so the blades do not face
// the camera together. Teal is one narrow blade.
private val Petals = listOf(
    PetalSpec(
        color = SiriRed,
        group = PetalColorGroup.Red,
        intensity = 1f,
        halfLength = 430f,
        halfWidth = 46f,
        bendDeg = 70f,
        twistDeg = 62f,
        bias = 0.18f,
        curl = 0.06f,
        rotX = 12f,
        rotY = 6f,
        rotZ = -24f,
        spinX = 2,
        spinY = 0,
        spinZ = 1,
        shift = 1f,
        seed = 0.4f,
    ),
    PetalSpec(
        color = SiriRed,
        group = PetalColorGroup.Red,
        intensity = 0.94f,
        halfLength = 404f,
        halfWidth = 30f,
        bendDeg = -76f,
        twistDeg = 124f,
        bias = -0.22f,
        curl = 0.08f,
        rotX = 76f,
        rotY = -10f,
        rotZ = 62f,
        spinX = -2,
        spinY = 1,
        spinZ = -1,
        shift = -1f,
        seed = 1.35f,
    ),
    PetalSpec(
        color = SiriBlue,
        group = PetalColorGroup.Blue,
        intensity = 1f,
        halfLength = 440f,
        halfWidth = 42f,
        bendDeg = 88f,
        twistDeg = -54f,
        bias = 0.08f,
        curl = -0.05f,
        rotX = 28f,
        rotY = 8f,
        rotZ = 142f,
        spinX = 1,
        spinY = 0,
        spinZ = 2,
        shift = 1f,
        seed = 2.25f,
    ),
    PetalSpec(
        color = SiriBlue,
        group = PetalColorGroup.Blue,
        intensity = 0.92f,
        halfLength = 392f,
        halfWidth = 26f,
        bendDeg = -54f,
        twistDeg = 138f,
        bias = -0.16f,
        curl = 0.07f,
        rotX = 84f,
        rotY = 12f,
        rotZ = 206f,
        spinX = -1,
        spinY = -1,
        spinZ = 1,
        shift = -1f,
        seed = 3.15f,
    ),
    PetalSpec(
        color = SiriBlue,
        group = PetalColorGroup.Blue,
        intensity = 0.84f,
        halfLength = 416f,
        halfWidth = 18f,
        bendDeg = 96f,
        twistDeg = 22f,
        bias = 0.10f,
        curl = -0.04f,
        rotX = 96f,
        rotY = -8f,
        rotZ = 18f,
        spinX = 2,
        spinY = 0,
        spinZ = -2,
        shift = 1f,
        seed = 4.45f,
    ),
    PetalSpec(
        color = SiriTeal,
        group = PetalColorGroup.Teal,
        intensity = 0.80f,
        halfLength = 400f,
        halfWidth = 20f,
        bendDeg = -64f,
        twistDeg = -96f,
        bias = -0.12f,
        curl = 0.06f,
        rotX = 50f,
        rotY = 10f,
        rotZ = 258f,
        spinX = -2,
        spinY = 1,
        spinZ = 1,
        shift = -1f,
        seed = 5.6f,
    ),
)

private val PetalIndices: List<Int> = buildList {
    for (i in 0 until LengthSegments) {
        val row = i * WidthVerts
        val next = row + WidthVerts
        for (j in 0 until WidthSegments) {
            val a = row + j
            val b = a + 1
            val c = next + j
            val d = c + 1
            add(a); add(c); add(b)
            add(b); add(c); add(d)
        }
    }
}

private val PetalTextureCoordinates: List<Offset> = List(PetalVertexCount) { index ->
    val i = index / WidthVerts
    val j = index - i * WidthVerts
    Offset(i.toFloat() / LengthSegments, j.toFloat() / WidthSegments)
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
    val rayAngle = remember { FloatArray(Petals.size) }
    val screenPaint = remember {
        Paint().apply {
            color = Color.White
            isAntiAlias = true
            blendMode = BlendMode.Screen
        }
    }
    val petalPaint = remember {
        Paint().apply {
            color = Color.White
            isAntiAlias = true
            blendMode = BlendMode.SrcOver
        }
    }
    val turns = spinClock.advance(phaseState.value, style.rotationScale)

    BoxWithConstraints(modifier) {
        val glowBlur = maxWidth * ShellRadiusFraction * BloomRadiusFractionOfShell *
            style.bloomScale.coerceIn(0.35f, 1.4f)
        val bodyBlur = maxWidth * ShellRadiusFraction * BodyBlurFractionOfShell
        Canvas(
            Modifier
                .fillMaxSize()
                .blur(bodyBlur, BlurredEdgeTreatment.Unbounded),
        ) {
            val phase = phaseState.value
            val shell = shellRadius(phase, audioLevel, style)
            if (shell < 1f) return@Canvas
            val focal = shell * CameraDistance / ShellRadiusAe
            drawPetals(
                phase = phase,
                style = style,
                turns = turns,
                mode = ModeBody,
                positions = petalPositions,
                colors = petalColors,
                depth = petalDepth,
                rayAngle = rayAngle,
                focal = focal,
                shell = shell,
                paint = screenPaint,
                layerPaint = null,
            )
            drawBodyLobes(shell, phase, rayAngle, petalDepth, style)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .blur(glowBlur, BlurredEdgeTreatment.Unbounded),
        ) {
            val phase = phaseState.value
            val shell = shellRadius(phase, audioLevel, style)
            if (shell < 1f || style.bloomAlpha <= 0.001f) return@Canvas
            val focal = shell * CameraDistance / ShellRadiusAe
            drawPetals(
                phase = phase,
                style = style,
                turns = turns,
                mode = ModeEdge,
                positions = petalPositions,
                colors = petalColors,
                depth = petalDepth,
                rayAngle = rayAngle,
                focal = focal,
                shell = shell,
                paint = screenPaint,
                layerPaint = null,
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
                mode = ModeSheet,
                positions = petalPositions,
                colors = petalColors,
                depth = petalDepth,
                rayAngle = rayAngle,
                focal = focal,
                shell = shell,
                paint = petalPaint,
                layerPaint = screenPaint,
            )
            drawCore(shell, phase, style)
            drawStarRays(shell, phase, rayAngle, style)
            drawRim(shell, style)
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
    val pulse = style.pulseAmount * delta.coerceIn(-0.14f, 0.14f)
    return (style.sizeScale * (1f + pulse)).coerceAtLeast(0f)
}

private fun DrawScope.drawCore(shell: Float, phase: Float, style: SiriOrbStyle) {
    val whiteMix = 1f - style.errorAmount
    val coreColor = Color(
        red = 1f * whiteMix + SiriRed.red * style.errorAmount,
        green = 1f * whiteMix + SiriRed.green * style.errorAmount,
        blue = 1f * whiteMix + SiriRed.blue * style.errorAmount,
    )
    // About 40% of the previous 0.74-shell feather. Soft inside that footprint
    // only, so the rest of the glass stays black.
    val overlap = (1f - style.separation * 0.8f).coerceIn(0.15f, 1f)
    val breathe = 0.94f + 0.06f * sin(phase * 2f)
    val alpha = (style.bloomAlpha * style.opacityScale * overlap * breathe).coerceIn(0f, 1.15f)
    if (alpha <= 0.001f) return
    val featherRadius = shell * 0.30f * style.bloomScale.coerceIn(0.25f, 1.4f)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to coreColor.copy(alpha = 0.82f * alpha),
                0.22f to coreColor.copy(alpha = 0.46f * alpha),
                0.48f to coreColor.copy(alpha = 0.14f * alpha),
                0.74f to coreColor.copy(alpha = 0.03f * alpha),
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

private fun DrawScope.drawStarRays(
    shell: Float,
    phase: Float,
    angles: FloatArray,
    style: SiriOrbStyle,
) {
    val cover = (style.opacityScale * (1f - style.separation * 0.65f)).coerceIn(0f, 1.15f)
    if (cover <= 0.001f) return
    val whiteMix = 1f - style.errorAmount
    val tint = Color(
        red = 1f * whiteMix + SiriRed.red * style.errorAmount,
        green = 1f * whiteMix + SiriRed.green * style.errorAmount,
        blue = 1f * whiteMix + SiriRed.blue * style.errorAmount,
    )
    for (index in angles.indices) {
        val flick = 0.78f + 0.22f * sin(phase * 4f + index * 1.7f + Petals[index].seed)
        val length = shell * 0.60f * (0.90f + 0.10f * flick)
        val rayAlpha = (0.75f * flick * cover).coerceIn(0f, 0.9f)
        if (rayAlpha <= 0.01f) continue
        val angle = angles[index]
        val end = Offset(
            center.x + cos(angle) * length,
            center.y + sin(angle) * length,
        )
        drawLine(
            brush = Brush.linearGradient(
                0f to tint.copy(alpha = rayAlpha),
                0.35f to tint.copy(alpha = rayAlpha * 0.55f),
                1f to Color.Transparent,
                start = center,
                end = end,
            ),
            start = center,
            end = end,
            strokeWidth = 1.8f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Screen,
        )
    }
}

private fun DrawScope.drawRim(shell: Float, style: SiriOrbStyle) {
    val alphaScale = style.opacityScale.coerceIn(0f, 1.2f)
    if (alphaScale <= 0.001f) return
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.86f to Color.Transparent,
                0.97f to RimColor.copy(alpha = 0.07f * alphaScale.coerceAtMost(1f)),
                1.00f to Color.Transparent,
            ),
            center = center,
            radius = shell,
        ),
        radius = shell,
        center = center,
        blendMode = BlendMode.Screen,
    )
    drawCircle(
        color = RimColor.copy(alpha = 0.22f * alphaScale.coerceAtMost(1f)),
        radius = shell,
        center = center,
        style = Stroke(width = 1.15f),
    )
    // Fixed in camera space: a short specular kiss on the upper-left glass.
    val arc = shell * 2f
    drawArc(
        color = RimHighlight.copy(alpha = 0.34f * alphaScale.coerceAtMost(1f)),
        startAngle = 214f,
        sweepAngle = 40f,
        useCenter = false,
        topLeft = Offset(center.x - shell, center.y - shell),
        size = Size(arc, arc),
        style = Stroke(width = 1.7f, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawPetals(
    phase: Float,
    style: SiriOrbStyle,
    turns: Float,
    mode: Int,
    positions: List<MutableList<Offset>>,
    colors: List<MutableList<Color>>,
    depth: FloatArray,
    rayAngle: FloatArray,
    focal: Float,
    shell: Float,
    paint: Paint,
    layerPaint: Paint?,
) {
    Petals.forEachIndexed { index, spec ->
        depth[index] = fillPetal(
            spec = spec,
            phase = phase,
            style = style,
            turns = turns,
            mode = mode,
            positions = positions[index],
            colors = colors[index],
            focal = focal,
            shell = shell,
            angleOut = rayAngle,
            angleIndex = index,
        )
    }
    val order = depth.indices.sortedByDescending { depth[it] }
    val layerBounds = Rect(0f, 0f, size.width, size.height)
    for (index in order) {
        // One layer per blade so shared triangle edges do not screen with themselves.
        // The layer then screens onto the orb, and overlaps go white and pink.
        if (layerPaint != null) {
            drawContext.canvas.saveLayer(layerBounds, layerPaint)
        }
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
        if (layerPaint != null) {
            drawContext.canvas.restore()
        }
    }
}

private fun DrawScope.fillPetal(
    spec: PetalSpec,
    phase: Float,
    style: SiriOrbStyle,
    turns: Float,
    mode: Int,
    positions: MutableList<Offset>,
    colors: MutableList<Color>,
    focal: Float,
    shell: Float,
    angleOut: FloatArray,
    angleIndex: Int,
): Float {
    val motion = style.motionScale.coerceIn(0f, 1.6f)
    val bendWiggle = loopWiggle(phase, spec.seed) * 20f * motion
    val twistWiggle = loopWiggle(phase, spec.seed + 1.7f) * 26f * motion
    val flipX = loopWiggle(phase, spec.seed + 2.2f) * BankFlipDeg * motion
    val flipY = loopWiggle(phase, spec.seed + 4.1f) * PitchFlipDeg * motion
    val bendDeg = spec.bendDeg + bendWiggle
    val twistDeg = spec.twistDeg + twistWiggle
    val rotX = (spec.rotX + spec.spinX * 360f * turns + flipX) * DegreesToRadians
    val rotY = (spec.rotY + spec.spinY * 360f * turns + flipY) * DegreesToRadians
    val rotZ = (spec.rotZ + spec.spinZ * 360f * turns) * DegreesToRadians
    val weight = when (spec.group) {
        PetalColorGroup.Red -> style.redWeight
        PetalColorGroup.Blue -> style.blueWeight
        PetalColorGroup.Teal -> style.tealWeight
    }
    val spread = 1f + style.separation * 0.22f
    val push = style.separation * spec.shift * 230f
    val halfWidth = spec.halfWidth * (1f + style.fill * 0.18f) * (if (mode == ModeBody) BodyWidthScale * (1f + style.fill * 0.25f) else 1f)
    val driftX = loopWiggle(phase, spec.seed + 6f) * 16f * motion
    val driftY = loopWiggle(phase, spec.seed + 8f) * 14f * motion
    val energy = spec.intensity * weight * style.opacityScale
    val rotXCos = cos(rotX)
    val rotXSin = sin(rotX)
    val rotYCos = cos(rotY)
    val rotYSin = sin(rotY)
    val rotZCos = cos(rotZ)
    val rotZSin = sin(rotZ)
    val local = FloatArray(3)
    val edgeNeg = FloatArray(3)
    val edgePos = FloatArray(3)
    val midPoint = FloatArray(3)

    var depthSum = 0f
    var midX = center.x
    var midY = center.y
    var posX = center.x
    var posY = center.y
    var posW = 0f
    var negX = center.x
    var negY = center.y
    var negW = 0f

    for (i in 0 until LengthVerts) {
        val s = -1f + 2f * i / LengthSegments
        val taper = taperOf(s, spec.bias)
        projectRibbon(s, -1f, spec, halfWidth, bendDeg, twistDeg, spread, push, driftX, driftY, rotXCos, rotXSin, rotYCos, rotYSin, rotZCos, rotZSin, focal, local, edgeNeg)
        projectRibbon(s, 1f, spec, halfWidth, bendDeg, twistDeg, spread, push, driftX, driftY, rotXCos, rotXSin, rotYCos, rotYSin, rotZCos, rotZSin, focal, local, edgePos)
        projectRibbon(s, 0f, spec, halfWidth, bendDeg, twistDeg, spread, push, driftX, driftY, rotXCos, rotXSin, rotYCos, rotYSin, rotZCos, rotZSin, focal, local, midPoint)
        val halfWidthPx = 0.5f * (hypot(edgePos[0] - midPoint[0], edgePos[1] - midPoint[1]) +
            hypot(edgeNeg[0] - midPoint[0], edgeNeg[1] - midPoint[1]))
        val geoHalf = halfWidth * taper
        val edgeOn = geoHalf > 8f && halfWidthPx < 1.7f
        if (i == MidColumn) {
            midX = midPoint[0]
            midY = midPoint[1]
        } else if (i == (LengthSegments * 0.16f).toInt()) {
            negX = midPoint[0]
            negY = midPoint[1]
            negW = halfWidthPx
        } else if (i == (LengthSegments * 0.84f).toInt()) {
            posX = midPoint[0]
            posY = midPoint[1]
            posW = halfWidthPx
        }

        val tangentX: Float
        val tangentY: Float
        if (edgeOn) {
            val neighbor = if (i < LengthSegments) i + 1 else i - 1
            val ns = -1f + 2f * neighbor / LengthSegments
            projectRibbon(ns, 0f, spec, halfWidth, bendDeg, twistDeg, spread, push, driftX, driftY, rotXCos, rotXSin, rotYCos, rotYSin, rotZCos, rotZSin, focal, local, edgeNeg)
            tangentX = edgeNeg[0] - midPoint[0]
            tangentY = edgeNeg[1] - midPoint[1]
        } else {
            tangentX = 0f
            tangentY = 0f
        }
        val tangentLen = hypot(tangentX, tangentY).coerceAtLeast(0.001f)
        val normalX = -tangentY / tangentLen
        val normalY = tangentX / tangentLen
        val hairline = if (edgeOn) (0.85f - halfWidthPx * 0.5f).coerceAtLeast(0f) else 0f

        for (j in 0 until WidthVerts) {
            val side = when {
                j < MidRow -> -1f
                j > MidRow -> 1f
                else -> 0f
            }
            val insetRequest = RowInsetPx[j]
            val insetPx = when {
                side == 0f -> halfWidthPx
                halfWidthPx < 0.6f -> 0f
                else -> insetRequest.coerceAtMost(halfWidthPx * 0.97f)
            }
            val t = when {
                mode == ModeBody -> (j - MidRow).toFloat() / MidRow
                side == 0f -> 0f
                halfWidthPx < 0.6f -> side
                else -> side * (1f - insetPx / halfWidthPx)
            }
            projectRibbon(s, t, spec, halfWidth, bendDeg, twistDeg, spread, push, driftX, driftY, rotXCos, rotXSin, rotYCos, rotYSin, rotZCos, rotZSin, focal, local, edgePos)
            var sx = edgePos[0]
            var sy = edgePos[1]
            val depth = edgePos[2]
            if (hairline > 0f && (j == 0 || j == WidthSegments)) {
                val sign = if (j == 0) -1f else 1f
                sx += normalX * sign * hairline
                sy += normalY * sign * hairline
            }
            val vertex = i * WidthVerts + j
            positions[vertex] = Offset(sx, sy)

            val centerline = if (halfWidthPx < 1f) 0f else (insetPx / halfWidthPx).coerceIn(0f, 1f)
            val lip = if (halfWidthPx < 2.3f) {
                1f
            } else {
                (1f - insetPx / LipPx).coerceIn(0f, 1f)
            }
            val along = abs(s)
            val heat = (1f - along).pow(1.35f)
            val projected = hypot(sx - center.x, sy - center.y) / shell
            val shellFade = 1f - smoothStep(((projected - 0.985f) / 0.02f).coerceIn(0f, 1f))
            val fogT = ((depth - FogStart) / FogRange).coerceIn(0f, 1f)
            val fog = 1f - 0.45f * smoothStep(fogT)
            val src = spec.color
            if (mode == ModeBody) {
                // Feathered ring: clear centre, colour toward the sheet edge.
                val a = abs(t)
                val hole = (1f - style.fill).coerceIn(0f, 1f)
                val ring = (1f - hole) + hole * (0.12f + 0.88f * a.pow(1.4f))
                val outer = 1f - smoothStep(((a - 0.82f) / 0.18f).coerceIn(0f, 1f)) * 0.6f
                val alpha = (BodyAlpha * (1f + style.fill * 0.8f) * ring * outer * energy * fog * shellFade).coerceIn(0f, 1f)
                colors[vertex] = src.withAlpha(alpha)
            } else if (mode == ModeEdge) {
                // Colored halo on the bright lip. Kept tinted so it does not draw a white stroke.
                val band = if (halfWidthPx < 2.6f) 1f else (1f - insetPx / BloomBandPx).coerceIn(0f, 1f)
                val alpha = (band * energy * fog * shellFade * style.bloomAlpha * 0.72f).coerceIn(0f, 1f)
                val glow = 0.38f
                colors[vertex] = Color(
                    red = src.red + (1f - src.red) * glow,
                    green = src.green + (1f - src.green) * glow,
                    blue = src.blue + (1f - src.blue) * glow,
                ).withAlpha(alpha)
            } else {
                // Saturated sheet. The hot streak is only the spine, stronger where
                // the blade crosses the orb. The lip is the 1–2px hard edge.
                val spine = centerline.pow(1.35f)
                val streak = spine * (0.15f + 0.85f * heat)
                val whiten = maxOf(streak * 0.36f, lip * 0.48f)
                val shape = maxOf(0.97f, lip).coerceIn(0f, 1f)
                val alpha = (shape * energy * fog * shellFade).coerceIn(0f, 1f)
                colors[vertex] = Color(
                    red = src.red + (1f - src.red) * whiten,
                    green = src.green + (1f - src.green) * whiten,
                    blue = src.blue + (1f - src.blue) * whiten,
                ).withAlpha(alpha)
            }
            depthSum += depth
        }
    }

    val aimPos = posW >= negW
    val aimX = if (aimPos) posX else negX
    val aimY = if (aimPos) posY else negY
    angleOut[angleIndex] = atan2(aimY - midY, aimX - midX)
    return depthSum / PetalVertexCount
}

private fun DrawScope.projectRibbon(
    s: Float,
    t: Float,
    spec: PetalSpec,
    halfWidth: Float,
    bendDeg: Float,
    twistDeg: Float,
    spread: Float,
    push: Float,
    driftX: Float,
    driftY: Float,
    rotXCos: Float,
    rotXSin: Float,
    rotYCos: Float,
    rotYSin: Float,
    rotZCos: Float,
    rotZSin: Float,
    focal: Float,
    local: FloatArray,
    out: FloatArray,
) {
    writeRibbonPoint(local, s, t, spec.halfLength * LengthScale, halfWidth, bendDeg, twistDeg, spec.bias, spec.curl)
    val x0 = local[0] * spread + push + driftX
    val y0 = local[1] * spread + driftY
    val z0 = local[2] * spread
    val y1 = y0 * rotXCos - z0 * rotXSin
    val z1 = y0 * rotXSin + z0 * rotXCos
    val x2 = x0 * rotYCos + z1 * rotYSin
    val z2 = -x0 * rotYSin + z1 * rotYCos
    val x3 = x2 * rotZCos - y1 * rotZSin
    val y3 = x2 * rotZSin + y1 * rotZCos
    // +z points at the camera. Depth increases away from it.
    val depth = CameraDistance - z2
    val projection = focal / depth.coerceAtLeast(420f)
    out[0] = center.x + x3 * projection
    out[1] = center.y - y3 * projection
    out[2] = depth
}

/**
 * Blade through the origin. [s] runs tip to tip, [t] runs across the sheet.
 * Bend curves the centerline in the sheet, so the tips stay out at the glass.
 * Twist banks the cross-section; a bank of 90° is the edge-on hairline.
 */
private fun writeRibbonPoint(
    out: FloatArray,
    s: Float,
    t: Float,
    halfLength: Float,
    halfWidth: Float,
    bendDeg: Float,
    twistDeg: Float,
    bias: Float,
    curl: Float,
) {
    val taper = taperOf(s, bias)
    val across = t * halfWidth * taper
    val halfAngle = bendDeg * DegreesToRadians * 0.5f
    val xArc: Float
    val yArc: Float
    if (abs(halfAngle) < 0.02f) {
        xArc = s * halfLength
        yArc = 0f
    } else {
        val angle = s * halfAngle
        xArc = sin(angle) / sin(halfAngle) * halfLength
        val bowSign = if (bendDeg >= 0f) 1f else -1f
        yArc = (1f - cos(angle)) * halfLength * 0.46f * bowSign
    }
    val twist = s * twistDeg * DegreesToRadians
    out[0] = xArc
    out[1] = yArc + across * cos(twist) + sin(s * Pi) * curl * halfLength
    out[2] = across * sin(twist)
}

/**
 * Ribbon profile: nearly even width through the body, then a smooth needle.
 * [bias] slides the wide point so one tip is longer than the other.
 */
private fun taperOf(s: Float, bias: Float): Float {
    val pivot = bias.coerceIn(-0.45f, 0.45f)
    val u = if (s >= pivot) {
        (s - pivot) / (1f - pivot)
    } else {
        (pivot - s) / (pivot + 1f)
    }
    val c = abs(u).coerceIn(0f, 1f)
    if (c >= 0.998f) return 0f
    val needle = 1f - smoothStep(((c - 0.58f) / 0.42f).coerceIn(0f, 1f))
    return needle * (0.90f + 0.10f * (1f - c * c))
}

/** Periodic stand-in for wiggle(0.2, amplitude). Value is about -1..1. */
private fun loopWiggle(phase: Float, seed: Float): Float {
    val a = sin(phase * WiggleCycles + seed)
    val b = 0.42f * sin(phase * (WiggleCycles + 2f) + seed * 1.7f)
    val c = 0.20f * sin(phase * (WiggleCycles + 5f) + seed * 2.4f)
    return ((a + b + c) / 1.62f).coerceIn(-1f, 1f)
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

/**
 * Large soft lobes behind the blades: the feathered ring texture seen face-on.
 * Each sits off-centre along its blade, so colour fills the glass unevenly.
 */
private fun DrawScope.drawBodyLobes(
    shell: Float,
    phase: Float,
    angles: FloatArray,
    depth: FloatArray,
    style: SiriOrbStyle,
) {
    Petals.forEachIndexed { index, spec ->
        val weight = when (spec.group) {
            PetalColorGroup.Red -> style.redWeight
            PetalColorGroup.Blue -> style.blueWeight
            PetalColorGroup.Teal -> style.tealWeight * 0.6f
        }
        val fogT = ((depth[index] - FogStart) / FogRange).coerceIn(0f, 1f)
        val near = 1f - 0.7f * smoothStep(fogT)
        val w = loopWiggle(phase, spec.seed + 9.3f)
        val alpha = (0.75f * (0.7f + 0.3f * w) * near * weight * spec.intensity * style.opacityScale *
            (1f + style.fill * 0.5f)).coerceIn(0f, 0.8f)
        if (alpha <= 0.01f) return@forEachIndexed
        val sideSign = if (index % 2 == 0) 1f else -1f
        val angle = angles[index] + sideSign * 0.5f
        val dist = shell * (0.30f + 0.12f * loopWiggle(phase, spec.seed + 3.3f) + style.separation * 0.15f)
        val c = Offset(center.x + cos(angle) * dist, center.y + sin(angle) * dist)
        val r = shell * (0.50f + 0.08f * w)
        val hole = (1f - style.fill).coerceIn(0f, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to spec.color.copy(alpha = alpha * (1f - 0.6f * hole)),
                    0.55f to spec.color.copy(alpha = alpha * 0.85f),
                    0.88f to spec.color.copy(alpha = alpha),
                    0.93f to spec.color.copy(alpha = alpha * 0.5f),
                    1.00f to Color.Transparent,
                ),
                center = c,
                radius = r,
            ),
            radius = r,
            center = c,
            blendMode = BlendMode.Screen,
        )
    }
}
