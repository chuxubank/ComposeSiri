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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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

internal val SiriBlue = Color(0xFF0021FF)
internal val SiriTeal = Color(0xFF23FFE6)
internal val SiriGreen = Color(0xFF00F09A)
internal val SiriRed = Color(0xFFFF1A22)
private val VignetteBrush = Brush.radialGradient(
    colorStops = arrayOf(
        0f to Color.Transparent,
        0.78f to Color.Transparent,
        0.92f to Color.Black.copy(alpha = 0.18f),
        1f to Color.Black.copy(alpha = 0.45f),
    ),
    center = Offset.Zero,
    radius = 1f,
)
private val ReflectionBrush = Brush.radialGradient(
    colors = listOf(Color.White.copy(alpha = 0.035f), Color.Transparent),
    center = Offset.Zero,
    radius = 1f,
)

/**
 * A Siri-inspired animated orb built from six curved, tapered 3D sheets.
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
    val time = transition.animateFloat(
        initialValue = 0f,
        targetValue = ReferenceLoopMillis.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(ReferenceLoopMillis, easing = LinearEasing),
        ),
        label = "Siri orb time",
    )
    SiriOrbFrame(
        modifier = modifier,
        timeMillis = time.value.toLong(),
        audioLevel = audioLevel,
        style = animatedStyle(state),
    )
}

/**
 * One still frame of [SiriOrb] at an explicit [timeMillis] in the reference loop.
 *
 * [timeMillis] is taken modulo the loop period so values beyond one cycle wrap.
 * Style is the target look for [state], with no state-to-state animation.
 */
@Composable
fun SiriOrbFrame(
    modifier: Modifier = Modifier,
    state: SiriOrbState = SiriOrbState.Listening,
    timeMillis: Long,
    audioLevel: Float = 0.65f,
) {
    SiriOrbFrame(
        modifier = modifier,
        timeMillis = timeMillis,
        audioLevel = audioLevel,
        style = state.targetStyle(),
    )
}

@Composable
private fun SiriOrbFrame(
    modifier: Modifier,
    timeMillis: Long,
    audioLevel: Float,
    style: SiriOrbStyle,
) {
    val phaseState = rememberUpdatedState(siriOrbPhase(timeMillis))

    Box(modifier = modifier.aspectRatio(1f)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    shape = CircleShape
                    clip = true
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .background(Color.Black),
        ) {
            // Wide gaussian for the soft flank, then a tighter pass so the
            // needle tips and one edge stay defined.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(22.dp, BlurredEdgeTreatment.Unbounded),
            ) {
                SiriSurfaceField(
                    phaseState = phaseState,
                    style = style,
                    audioLevel = audioLevel.coerceIn(0f, 1f),
                    widthScale = 1.55f,
                    alphaScale = 0.9f,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(7.dp, BlurredEdgeTreatment.Unbounded),
            ) {
                SiriSurfaceField(
                    phaseState = phaseState,
                    style = style,
                    audioLevel = audioLevel.coerceIn(0f, 1f),
                    widthScale = 0.16f,
                    alphaScale = 1f,
                    modifier = Modifier.matchParentSize(),
                )
            }
            SiriCoreBloom(
                phaseState = phaseState,
                style = style,
                audioLevel = audioLevel.coerceIn(0f, 1f),
                modifier = Modifier.matchParentSize(),
            )
            OrbGlass(
                phaseState = phaseState,
                modifier = Modifier.matchParentSize(),
            )
        }
        OrbShell(Modifier.matchParentSize())
    }
}

private fun siriOrbPhase(timeMillis: Long): Float {
    val loopMillis = timeMillis.mod(ReferenceLoopMillis.toLong())
    return TwoPi * loopMillis / ReferenceLoopMillis
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
    fun target(value: SiriOrbState): SiriOrbStyle = value.targetStyle()
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

private fun SiriOrbState.targetStyle(): SiriOrbStyle = when (this) {
    SiriOrbState.Idle -> SiriOrbStyle(
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

    SiriOrbState.Listening -> SiriOrbStyle(
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

    SiriOrbState.Thinking -> SiriOrbStyle(
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

    SiriOrbState.Speaking -> SiriOrbStyle(
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

    SiriOrbState.Error -> SiriOrbStyle(
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
        val radius = size.minDimension * 0.47f
        val stroke = size.minDimension * 0.0035f
        drawCircle(
            brush = Brush.sweepGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFF7A2434).copy(alpha = 0.42f),
                    0.22f to Color(0xFF102028).copy(alpha = 0.10f),
                    0.48f to Color(0xFF1A4A52).copy(alpha = 0.32f),
                    0.74f to Color(0xFF2A1218).copy(alpha = 0.08f),
                    1.00f to Color(0xFF7A2434).copy(alpha = 0.42f),
                ),
                center = center,
            ),
            radius = radius,
            style = Stroke(width = stroke),
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
        val reflectionRadius = radius * 0.22f
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


