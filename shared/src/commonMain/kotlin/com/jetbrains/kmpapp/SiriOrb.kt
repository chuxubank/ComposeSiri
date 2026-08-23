package com.jetbrains.kmpapp

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
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

/** The visual phases exposed by [SiriOrb]. */
enum class SiriOrbState {
    Idle,
    Listening,
    Thinking,
    Speaking,
    Error,
}

internal data class SiriOrbStyle(
    val sizeScale: Float,
    val motionScale: Float,
    val opacityScale: Float,
    val bloomScale: Float,
    val bloomAlpha: Float,
    val redWeight: Float,
    val blueWeight: Float,
    val tealWeight: Float,
    val pulseAmount: Float,
    val errorAmount: Float,
)

private data class TargetOrbStyle(
    val sizeScale: Float,
    val motionScale: Float,
    val opacityScale: Float,
    val bloomScale: Float,
    val bloomAlpha: Float,
    val redWeight: Float,
    val blueWeight: Float,
    val tealWeight: Float,
    val pulseAmount: Float,
    val errorAmount: Float,
)

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
 * A Siri-inspired animated orb built from six bent and twisted 3D ellipse surfaces.
 *
 * [audioLevel] is sampled while [state] is [SiriOrbState.Speaking] or [SiriOrbState.Error] to
 * drive the breathing response. The caller controls the component size through [modifier].
 */
@Composable
fun SiriOrb(
    modifier: Modifier = Modifier,
    state: SiriOrbState = SiriOrbState.Listening,
    audioLevel: Float = 0.65f,
) {
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
    val style = animatedStyle(state)

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
                style = style,
                audioLevel = audioLevel.coerceIn(0f, 1f),
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
fun SiriOrbScreen(
    modifier: Modifier = Modifier,
    state: SiriOrbState = SiriOrbState.Listening,
    audioLevel: Float = 0.65f,
) {
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
            state = state,
            audioLevel = audioLevel,
        )
    }
}

@Composable
private fun animatedStyle(state: SiriOrbState): SiriOrbStyle {
    val transition = updateTransition(targetState = state, label = "Siri state")
    val animationSpec = tween<Float>(durationMillis = 850, easing = FastOutSlowInEasing)
    fun target(value: SiriOrbState): TargetOrbStyle = value.targetStyle()
    return SiriOrbStyle(
        sizeScale = transition.animateFloat({ animationSpec }, label = "Siri size") {
            target(it).sizeScale
        }.value,
        motionScale = transition.animateFloat({ animationSpec }, label = "Siri motion") {
            target(it).motionScale
        }.value,
        opacityScale = transition.animateFloat({ animationSpec }, label = "Siri opacity") {
            target(it).opacityScale
        }.value,
        bloomScale = transition.animateFloat({ animationSpec }, label = "Siri bloom size") {
            target(it).bloomScale
        }.value,
        bloomAlpha = transition.animateFloat({ animationSpec }, label = "Siri bloom alpha") {
            target(it).bloomAlpha
        }.value,
        redWeight = transition.animateFloat({ animationSpec }, label = "Siri red") {
            target(it).redWeight
        }.value,
        blueWeight = transition.animateFloat({ animationSpec }, label = "Siri blue") {
            target(it).blueWeight
        }.value,
        tealWeight = transition.animateFloat({ animationSpec }, label = "Siri teal") {
            target(it).tealWeight
        }.value,
        pulseAmount = transition.animateFloat({ animationSpec }, label = "Siri pulse") {
            target(it).pulseAmount
        }.value,
        errorAmount = transition.animateFloat({ animationSpec }, label = "Siri error") {
            target(it).errorAmount
        }.value,
    )
}

private fun SiriOrbState.targetStyle(): TargetOrbStyle = when (this) {
    SiriOrbState.Idle -> TargetOrbStyle(
        sizeScale = 0.68f,
        motionScale = 0.16f,
        opacityScale = 0.22f,
        bloomScale = 0.30f,
        bloomAlpha = 0.12f,
        redWeight = 0.50f,
        blueWeight = 0.58f,
        tealWeight = 0.54f,
        pulseAmount = 0.02f,
        errorAmount = 0f,
    )

    SiriOrbState.Listening -> TargetOrbStyle(
        sizeScale = 1f,
        motionScale = 1f,
        opacityScale = 1f,
        bloomScale = 1f,
        bloomAlpha = 1f,
        redWeight = 1f,
        blueWeight = 1f,
        tealWeight = 1f,
        pulseAmount = 0f,
        errorAmount = 0f,
    )

    SiriOrbState.Thinking -> TargetOrbStyle(
        sizeScale = 0.92f,
        motionScale = 1.34f,
        opacityScale = 0.92f,
        bloomScale = 0.78f,
        bloomAlpha = 0.75f,
        redWeight = 0.10f,
        blueWeight = 1.05f,
        tealWeight = 1.10f,
        pulseAmount = 0.02f,
        errorAmount = 0f,
    )

    SiriOrbState.Speaking -> TargetOrbStyle(
        sizeScale = 1f,
        motionScale = 0.72f,
        opacityScale = 1f,
        bloomScale = 1.12f,
        bloomAlpha = 1f,
        redWeight = 1f,
        blueWeight = 1f,
        tealWeight = 1f,
        pulseAmount = 1f,
        errorAmount = 0f,
    )

    SiriOrbState.Error -> TargetOrbStyle(
        sizeScale = 0.84f,
        motionScale = 0.50f,
        opacityScale = 0.90f,
        bloomScale = 0.85f,
        bloomAlpha = 0.85f,
        redWeight = 1.20f,
        blueWeight = 0.02f,
        tealWeight = 0.02f,
        pulseAmount = 0.45f,
        errorAmount = 1f,
    )
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
