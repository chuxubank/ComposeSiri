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
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val TwoPi = 6.2831855f
private const val DegreesToRadians = 0.017453292f
private const val DiscRings = 7
private const val DiscSegments = 28

private val CoreBloomBrush = Brush.radialGradient(
    colorStops = arrayOf(
        0f to Color.White.copy(alpha = 0.90f),
        0.18f to Color(0xFFE9FFFF).copy(alpha = 0.72f),
        0.50f to Color(0xFFB9F7FF).copy(alpha = 0.32f),
        1f to Color.Transparent,
    ),
    center = Offset.Zero,
    radius = 1f,
)

private val ErrorBloomBrush = Brush.radialGradient(
    colorStops = arrayOf(
        0f to Color(0xFFFFF0E8).copy(alpha = 0.90f),
        0.18f to SiriRed.copy(alpha = 0.78f),
        0.50f to Color(0xFFFF001F).copy(alpha = 0.36f),
        1f to Color.Transparent,
    ),
    center = Offset.Zero,
    radius = 1f,
)

private data class DiscPoint(
    val x: Float,
    val y: Float,
    val distance: Float,
)

private data class SurfaceSpec(
    val color: Color,
    val scale: Float,
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float,
    val rotationXRange: Float,
    val rotationYRange: Float,
    val rotationZRange: Float,
    val twistX: Float,
    val twistY: Float,
    val bend: Float,
    val bendRange: Float,
    val bendDirection: Float,
    val phaseOffset: Float,
    val opacity: Float,
)

private data class SurfacePose(
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float,
    val twistX: Float,
    val twistY: Float,
    val bend: Float,
    val bendDirection: Float,
)

private val DiscPoints: List<DiscPoint> = buildList {
    add(DiscPoint(0f, 0f, 0f))
    for (ring in 1..DiscRings) {
        val distance = ring.toFloat() / DiscRings
        for (segment in 0 until DiscSegments) {
            val angle = TwoPi * segment / DiscSegments
            add(
                DiscPoint(
                    x = cos(angle) * distance,
                    y = sin(angle) * distance,
                    distance = distance,
                ),
            )
        }
    }
}

private val DiscIndices: List<Int> = buildList {
    for (segment in 0 until DiscSegments) {
        add(0)
        add(1 + segment)
        add(1 + (segment + 1) % DiscSegments)
    }
    for (ring in 2..DiscRings) {
        val innerStart = 1 + (ring - 2) * DiscSegments
        val outerStart = 1 + (ring - 1) * DiscSegments
        for (segment in 0 until DiscSegments) {
            val next = (segment + 1) % DiscSegments
            add(innerStart + segment)
            add(outerStart + segment)
            add(outerStart + next)
            add(innerStart + segment)
            add(outerStart + next)
            add(innerStart + next)
        }
    }
}

private val DiscTextureCoordinates: List<Offset> = DiscPoints.map { point ->
    Offset(
        x = (point.x + 1f) * 0.5f,
        y = (point.y + 1f) * 0.5f,
    )
}

// Baselines are the six Element 3D groups in the supplied AE project: two red, two blue and two
// teal copies of the same 922 x 922 ellipse. The ranges turn those authored poses into a seamless
// loop while preserving each group's characteristic bend and twist.
private val SurfaceSpecs = listOf(
    SurfaceSpec(
        color = SiriRed,
        scale = 1.05f,
        rotationX = -87f,
        rotationY = 259f,
        rotationZ = -189f,
        rotationXRange = 11f,
        rotationYRange = 34f,
        rotationZRange = 94f,
        twistX = -7f,
        twistY = -21f,
        bend = -51f,
        bendRange = 18f,
        bendDirection = -33f,
        phaseOffset = 0.20f,
        opacity = 0.94f,
    ),
    SurfaceSpec(
        color = SiriBlue,
        scale = 0.96f,
        rotationX = -29f,
        rotationY = 134f,
        rotationZ = 3.3f,
        rotationXRange = 22f,
        rotationYRange = 34f,
        rotationZRange = 82f,
        twistX = -19f,
        twistY = -64f,
        bend = 35f,
        bendRange = 30f,
        bendDirection = 65f,
        phaseOffset = 1.18f,
        opacity = 0.60f,
    ),
    SurfaceSpec(
        color = SiriRed,
        scale = 0.96f,
        rotationX = -16f,
        rotationY = 221f,
        rotationZ = 99f,
        rotationXRange = 24f,
        rotationYRange = 34f,
        rotationZRange = 78f,
        twistX = 12f,
        twistY = 38f,
        bend = 106f,
        bendRange = 31f,
        bendDirection = 228f,
        phaseOffset = 2.24f,
        opacity = 0.60f,
    ),
    SurfaceSpec(
        color = SiriTeal,
        scale = 1.04f,
        rotationX = 82f,
        rotationY = 14f,
        rotationZ = 164.4f,
        rotationXRange = 14f,
        rotationYRange = 34f,
        rotationZRange = 76f,
        twistX = 0f,
        twistY = -2f,
        bend = 55f,
        bendRange = 20f,
        bendDirection = 34f,
        phaseOffset = 3.25f,
        opacity = 0.92f,
    ),
    SurfaceSpec(
        color = SiriTeal,
        scale = 0.93f,
        rotationX = 162f,
        rotationY = 14f,
        rotationZ = 305f,
        rotationXRange = 22f,
        rotationYRange = 30f,
        rotationZRange = 84f,
        twistX = 0f,
        twistY = -15f,
        bend = -109f,
        bendRange = 30f,
        bendDirection = 30f,
        phaseOffset = 4.31f,
        opacity = 0.58f,
    ),
    SurfaceSpec(
        color = SiriBlue,
        scale = 0.93f,
        rotationX = -82f,
        rotationY = 217f,
        rotationZ = 91f,
        rotationXRange = 13f,
        rotationYRange = 34f,
        rotationZRange = 80f,
        twistX = 7f,
        twistY = -30f,
        bend = 33f,
        bendRange = 18f,
        bendDirection = 44f,
        phaseOffset = 5.36f,
        opacity = 0.94f,
    ),
)

@Composable
internal fun SiriSurfaceField(
    phaseState: State<Float>,
    style: SiriOrbStyle,
    audioLevel: Float,
    modifier: Modifier = Modifier,
) {
    val positions = remember {
        List(SurfaceSpecs.size) { MutableList(DiscPoints.size) { Offset.Zero } }
    }
    val colors = remember {
        List(SurfaceSpecs.size) { MutableList(DiscPoints.size) { Color.Transparent } }
    }
    val paint = remember {
        Paint().apply {
            color = Color.White
            blendMode = BlendMode.Screen
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
        val radius = size.minDimension * 0.47f * style.sizeScale * (1f + pulse * 0.075f)
        val core = Offset(
            x = center.x + radius * 0.018f * style.motionScale * sin(phase * 2f + 0.4f),
            y = center.y + radius * 0.022f * style.motionScale * cos(phase * 3f - 0.2f),
        )
        SurfaceSpecs.forEachIndexed { index, spec ->
            val colorWeight = when (spec.color) {
                SiriRed -> style.redWeight
                SiriBlue -> style.blueWeight
                else -> style.tealWeight
            }
            drawSurfaceMesh(
                spec = spec,
                pose = spec.poseAt(phase, style.motionScale),
                positions = positions[index],
                colors = colors[index],
                core = core,
                radius = radius * spec.scale,
                opacityScale = style.opacityScale * colorWeight,
                colorGain = 0.86f + 0.14f * colorWeight,
                paint = paint,
            )
        }
        val bloomRadius = radius * style.bloomScale * (
            0.36f + 0.018f * sin(phase * 3f + 0.4f) + pulse * 0.07f
        )
        withTransform({
            translate(core.x, core.y)
            scale(bloomRadius, bloomRadius, pivot = Offset.Zero)
        }) {
            drawCircle(
                brush = CoreBloomBrush,
                center = Offset.Zero,
                radius = 1f,
                alpha = style.bloomAlpha * (1f - style.errorAmount),
                blendMode = BlendMode.Screen,
            )
            drawCircle(
                brush = ErrorBloomBrush,
                center = Offset.Zero,
                radius = 1f,
                alpha = style.bloomAlpha * style.errorAmount,
                blendMode = BlendMode.Screen,
            )
        }
    }
}

private fun SurfaceSpec.poseAt(phase: Float, motionScale: Float): SurfacePose {
    val local = phase + phaseOffset
    return SurfacePose(
        rotationX = rotationX + rotationXRange * motionScale * sin(local),
        rotationY = rotationY + rotationYRange * motionScale * sin(local + 0.73f),
        rotationZ = rotationZ + rotationZRange * motionScale * sin(local * 2f + 0.21f),
        twistX = twistX + 12f * motionScale * sin(local * 1.5f + 1.1f),
        twistY = twistY + 20f * motionScale * sin(local + 1.83f),
        bend = bend + bendRange * motionScale * sin(local * 2f + 0.82f),
        bendDirection = bendDirection + 20f * motionScale * sin(local + 1.37f),
    )
}

private fun DrawScope.drawSurfaceMesh(
    spec: SurfaceSpec,
    pose: SurfacePose,
    positions: MutableList<Offset>,
    colors: MutableList<Color>,
    core: Offset,
    radius: Float,
    opacityScale: Float,
    colorGain: Float,
    paint: Paint,
) {
    val direction = pose.bendDirection * DegreesToRadians
    val directionCos = cos(direction)
    val directionSin = sin(direction)
    val bendAmount = pose.bend * DegreesToRadians * 0.5f
    val twistXAmount = sin(pose.twistX * DegreesToRadians) * 0.44f
    val twistYAmount = sin(pose.twistY * DegreesToRadians) * 0.44f

    val rotationX = pose.rotationX * DegreesToRadians
    val rotationY = pose.rotationY * DegreesToRadians
    val rotationZ = pose.rotationZ * DegreesToRadians
    val rotationXCos = cos(rotationX)
    val rotationXSin = sin(rotationX)
    val rotationYCos = cos(rotationY)
    val rotationYSin = sin(rotationY)
    val rotationZCos = cos(rotationZ)
    val rotationZSin = sin(rotationZ)

    DiscPoints.forEachIndexed { index, point ->
        val along = point.x * directionCos + point.y * directionSin
        val across = -point.x * directionSin + point.y * directionCos
        val angle = along * bendAmount
        val angleCos = cos(angle)
        val angleSin = sin(angle)
        val bentAlong: Float
        val bentDepth: Float
        val bentAlongDerivative: Float
        val bentDepthDerivative: Float
        if (abs(bendAmount) < 0.0001f) {
            bentAlong = along
            bentDepth = 0f
            bentAlongDerivative = 1f
            bentDepthDerivative = 0f
        } else {
            bentAlong = angleSin / bendAmount
            bentDepth = (1f - angleCos) / bendAmount
            bentAlongDerivative = angleCos
            bentDepthDerivative = angleSin
        }

        val surfaceX = bentAlong * directionCos - across * directionSin
        val surfaceY = bentAlong * directionSin + across * directionCos
        val surfaceZ = bentDepth +
            twistXAmount * surfaceX * across +
            twistYAmount * (surfaceX * surfaceX - surfaceY * surfaceY)

        val acrossDx = -directionSin
        val acrossDy = directionCos
        val surfaceXDx = directionCos * directionCos * bentAlongDerivative +
            directionSin * directionSin
        val surfaceXDy = directionCos * directionSin * (bentAlongDerivative - 1f)
        val surfaceYDx = surfaceXDy
        val surfaceYDy = directionSin * directionSin * bentAlongDerivative +
            directionCos * directionCos
        val surfaceZDx = bentDepthDerivative * directionCos +
            twistXAmount * (surfaceXDx * across + surfaceX * acrossDx) +
            twistYAmount * (2f * surfaceX * surfaceXDx - 2f * surfaceY * surfaceYDx)
        val surfaceZDy = bentDepthDerivative * directionSin +
            twistXAmount * (surfaceXDy * across + surfaceX * acrossDy) +
            twistYAmount * (2f * surfaceX * surfaceXDy - 2f * surfaceY * surfaceYDy)

        var normalX = surfaceYDx * surfaceZDy - surfaceZDx * surfaceYDy
        var normalY = surfaceZDx * surfaceXDy - surfaceXDx * surfaceZDy
        var normalZ = surfaceXDx * surfaceYDy - surfaceYDx * surfaceXDy
        val normalLength = sqrt(
            normalX * normalX + normalY * normalY + normalZ * normalZ,
        ).coerceAtLeast(0.0001f)
        normalX /= normalLength
        normalY /= normalLength
        normalZ /= normalLength

        val rotatedY = surfaceY * rotationXCos - surfaceZ * rotationXSin
        val rotatedZ = surfaceY * rotationXSin + surfaceZ * rotationXCos
        val rotatedX2 = surfaceX * rotationYCos + rotatedZ * rotationYSin
        val rotatedZ2 = -surfaceX * rotationYSin + rotatedZ * rotationYCos
        val rotatedX = rotatedX2 * rotationZCos - rotatedY * rotationZSin
        val rotatedY2 = rotatedX2 * rotationZSin + rotatedY * rotationZCos

        val normalRotatedY = normalY * rotationXCos - normalZ * rotationXSin
        val normalRotatedZ = normalY * rotationXSin + normalZ * rotationXCos
        val normalRotatedX2 = normalX * rotationYCos + normalRotatedZ * rotationYSin
        val normalRotatedZ2 = -normalX * rotationYSin + normalRotatedZ * rotationYCos
        val normalRotatedX = normalRotatedX2 * rotationZCos -
            normalRotatedY * rotationZSin
        val normalRotatedY2 = normalRotatedX2 * rotationZSin +
            normalRotatedY * rotationZCos

        val perspective = 1f / (1f + rotatedZ2 * 0.12f).coerceAtLeast(0.72f)
        positions[index] = Offset(
            x = core.x + radius * rotatedX * perspective,
            y = core.y + radius * rotatedY2 * perspective,
        )

        val lightDot = normalRotatedX * -0.34f +
            normalRotatedY2 * -0.42f +
            normalRotatedZ2 * 0.84f
        val diffuse = max(lightDot, -lightDot * 0.62f).coerceIn(0f, 1f)
        val facing = abs(normalRotatedZ2).coerceIn(0f, 1f)
        val rim = (1f - facing).pow(2)
        val lightShade = (0.38f + 0.62f * diffuse + rotatedZ2 * 0.04f)
            .coerceIn(0.25f, 1f)
        val radialShade = 0.64f + 0.36f * (1f - point.distance)
        val shade = (lightShade * radialShade).coerceIn(0.20f, 1f)
        val centerLight = smoothStep(
            ((0.70f - point.distance) / 0.70f).coerceIn(0f, 1f),
        )
        val whiteMix = (
            diffuse.pow(8) * 0.08f +
                rim * 0.02f +
                centerLight * (0.12f + rim * 0.28f)
            ).coerceIn(0f, 0.42f)
        val alpha = (spec.opacity * opacityScale * (0.80f + 0.20f * facing))
            .coerceIn(0f, 1f)
        colors[index] = Color(
            red = (spec.color.red * shade * colorGain + whiteMix).coerceIn(0f, 1f),
            green = (spec.color.green * shade * colorGain + whiteMix).coerceIn(0f, 1f),
            blue = (spec.color.blue * shade * colorGain + whiteMix).coerceIn(0f, 1f),
            alpha = alpha,
        )
    }
    val vertices = Vertices(
        vertexMode = VertexMode.Triangles,
        positions = positions,
        textureCoordinates = DiscTextureCoordinates,
        colors = colors,
        indices = DiscIndices,
    )
    drawContext.canvas.drawVertices(
        vertices = vertices,
        blendMode = BlendMode.Modulate,
        paint = paint,
    )
}

private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)
