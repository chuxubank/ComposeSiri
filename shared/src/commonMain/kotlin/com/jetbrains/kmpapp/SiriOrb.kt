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
import androidx.compose.foundation.layout.BoxWithConstraints
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

// Glass disc is 95% of the frame. The surface canvas is inset to that disc,
// so mesh radii are fractions of the shell, not of the square frame.
private const val ShellInsetFraction = 0.025f

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
        0.90f to Color.Transparent,
        0.97f to Color.Black.copy(alpha = 0.10f),
        1f to Color.Black.copy(alpha = 0.28f),
    ),
    center = Offset.Zero,
    radius = 1f,
)
private val RimGlowBrush = Brush.radialGradient(
    colorStops = arrayOf(
        0.00f to Color.Transparent,
        0.84f to Color.Transparent,
        0.92f to Color(0xFF14686A).copy(alpha = 0.05f),
        0.97f to Color(0xFF6A2430).copy(alpha = 0.07f),
        1.00f to Color(0xFF10343C).copy(alpha = 0.09f),
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
 * A Siri-inspired animated orb built from wide, curved translucent sheets.
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

    BoxWithConstraints(modifier = modifier.aspectRatio(1f)) {
        val shellInset = maxWidth * ShellInsetFraction
        Box(Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .padding(shellInset)
                    .fillMaxSize()
                    .graphicsLayer {
                        shape = CircleShape
                        clip = true
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .background(Color.Black),
            ) {
                // Broad fans, softened just enough that the interior reads as a
                // gradient. A second pass keeps one flank and the needle tips.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .blur(26.dp, BlurredEdgeTreatment.Unbounded),
                ) {
                    SiriSurfaceField(
                        phaseState = phaseState,
                        style = style,
                        audioLevel = audioLevel.coerceIn(0f, 1f),
                        widthScale = 1f,
                        alphaScale = 0.9f,
                        emphasizeEdge = false,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .blur(5.dp, BlurredEdgeTreatment.Unbounded),
                ) {
                    SiriSurfaceField(
                        phaseState = phaseState,
                        style = style,
                        audioLevel = audioLevel.coerceIn(0f, 1f),
                        widthScale = 1f,
                        alphaScale = 1.15f,
                        emphasizeEdge = true,
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
        val radius = size.minDimension * (0.5f - ShellInsetFraction)
        val glowWidth = size.minDimension * 0.012f
        val stroke = size.minDimension * 0.0026f
        val sweep = Brush.sweepGradient(
            colorStops = arrayOf(
                0.00f to Color(0xFFFF5A62),
                0.18f to Color(0xFF4A2428),
                0.40f to Color(0xFF1CE6D4),
                0.58f to Color(0xFF163034),
                0.76f to Color(0xFFE23A48),
                0.90f to Color(0xFF184448),
                1.00f to Color(0xFFFF5A62),
            ),
            center = center,
        )
        drawCircle(
            brush = sweep,
            radius = radius * 0.978f,
            style = Stroke(width = glowWidth),
            alpha = 0.16f,
            blendMode = BlendMode.Plus,
        )
        drawCircle(
            brush = sweep,
            radius = radius,
            style = Stroke(width = stroke),
            alpha = 0.38f,
            blendMode = BlendMode.Plus,
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
            drawCircle(
                brush = RimGlowBrush,
                center = Offset.Zero,
                radius = 1f,
                blendMode = BlendMode.Plus,
            )
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


