package com.jetbrains.kmpapp

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.MeshGradientPainter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private const val TwoPi = 6.2831855f
private const val ReferenceLoopMillis = 17_117

internal val SiriBlue = Color(0xFF006DFF)
internal val SiriTeal = Color(0xFF00DDB5)
internal val SiriRed = Color(0xFFFF1744)
private val VignetteBrush = Brush.radialGradient(
    colorStops = arrayOf(
        0f to Color.Transparent,
        0.58f to Color.Transparent,
        0.82f to Color.Black.copy(alpha = 0.30f),
        1f to Color.Black.copy(alpha = 0.88f),
    ),
    center = Offset.Zero,
    radius = 1f,
)
private val ReflectionBrush = Brush.radialGradient(
    colors = listOf(Color.White.copy(alpha = 0.065f), Color.Transparent),
    center = Offset.Zero,
    radius = 1f,
)

/**
 * A Siri-inspired animated orb built from six bent and twisted 3D ellipse surfaces. The caller
 * controls its size through [modifier].
 */
@Composable
fun SiriOrb(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "Siri orb")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = TwoPi,
        animationSpec = infiniteRepeatable(
            animation = tween(ReferenceLoopMillis, easing = LinearEasing),
        ),
        label = "Siri orb phase",
    )
    val volumePainter = remember { volumePainter() }

    Box(modifier = modifier.aspectRatio(1f)) {
        OrbShell(Modifier.matchParentSize())

        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(4.dp)
                .graphicsLayer {
                    shape = CircleShape
                    clip = true
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .background(Color(0xFF000206)),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .paint(volumePainter),
            )
            SiriSurfaceField(
                phaseState = phase,
                modifier = Modifier.matchParentSize(),
            )
            OrbGlass(
                phaseState = phase,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun SiriOrbScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding(),
    ) {
        SiriOrb(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .fillMaxWidth(0.74f)
                .widthIn(max = 312.dp),
        )
    }
}

@Composable
private fun OrbShell(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF030A12),
                    0.72f to Color(0xFF01050A),
                    0.90f to Color(0xFF07182A),
                    0.98f to Color(0xFF02060B),
                    1f to Color.Black,
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
        )
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    SiriRed.copy(alpha = 0.20f),
                    Color.Transparent,
                    SiriBlue.copy(alpha = 0.26f),
                    SiriTeal.copy(alpha = 0.18f),
                    SiriRed.copy(alpha = 0.20f),
                ),
                center = center,
            ),
            radius = radius - 1.5.dp.toPx(),
            style = Stroke(width = 1.1.dp.toPx()),
        )
    }
}

@Composable
private fun OrbGlass(
    phaseState: State<Float>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val phase = phaseState.value
        val radius = size.minDimension / 2f
        withTransform({
            translate(center.x, center.y)
            scale(radius, radius, pivot = Offset.Zero)
        }) {
            drawCircle(brush = VignetteBrush, center = Offset.Zero, radius = 1f)
        }
        val reflectionCenter = Offset(
            x = size.width * (0.34f + 0.025f * sin(phase)),
            y = size.height * (0.25f + 0.018f * cos(phase * 2f)),
        )
        val reflectionRadius = radius * 0.30f
        withTransform({
            translate(reflectionCenter.x, reflectionCenter.y)
            scale(reflectionRadius, reflectionRadius, pivot = Offset.Zero)
        }) {
            drawCircle(
                brush = ReflectionBrush,
                center = Offset.Zero,
                radius = 1f,
                blendMode = BlendMode.Screen,
            )
        }
    }
}

private fun volumePainter(): MeshGradientPainter = MeshGradientPainter(
    rows = 2,
    columns = 2,
    hasBicubicColor = true,
) {
    setVertex(0, 0, Offset(0f, 0f), Color.Transparent)
    setVertex(
        0,
        1,
        Offset(0.52f, 0f),
        SiriRed.copy(alpha = 0.15f),
    )
    setVertex(0, 2, Offset(1f, 0f), Color.Transparent)
    setVertex(
        1,
        0,
        Offset(0f, 0.47f),
        SiriBlue.copy(alpha = 0.14f),
    )
    setVertex(
        1,
        1,
        Offset(
            0.52f,
            0.48f,
        ),
        Color(0xFFBFFFFF).copy(alpha = 0.12f),
    )
    setVertex(
        1,
        2,
        Offset(1f, 0.54f),
        SiriTeal.copy(alpha = 0.14f),
    )
    setVertex(2, 0, Offset(0f, 1f), Color.Transparent)
    setVertex(
        2,
        1,
        Offset(0.47f, 1f),
        SiriBlue.copy(alpha = 0.13f),
    )
    setVertex(2, 2, Offset(1f, 1f), Color.Transparent)
}
