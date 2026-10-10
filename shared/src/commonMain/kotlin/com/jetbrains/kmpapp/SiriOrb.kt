package com.jetbrains.kmpapp

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val TwoPi = 6.2831855f
private const val ReferenceLoopMillis = 17_117

// AE Easy Ease is a slow-in / slow-out curve (about 33% influence).
private val EasyEase = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)

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
    val separation: Float,
    val fill: Float,
)

internal val SiriBlue = Color(0xFF0030FF)
internal val SiriTeal = Color(0xFF00FFCC)
internal val SiriRed = Color(0xFFFF1212)
internal val SiriPink = Color(0xFFFF2A6D)
internal val SiriPurple = Color(0xFF7A2CFF)

/**
 * A Siri-inspired animated orb. Ribbons are feathered rings on twisted, bent planes.
 *
 * [audioLevel] is sampled while [state] is [SiriOrbState.Speaking] or [SiriOrbState.Error] to
 * drive scale (amplitude / 40, with 40 as unity). The caller controls size through [modifier].
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
    Box(
        modifier
            .aspectRatio(1f)
            .background(Color.Black),
    ) {
        SiriOrbVisual(
            phaseState = phaseState,
            style = style,
            audioLevel = audioLevel.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxSize(),
        )
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
    val animationSpec = tween<Float>(durationMillis = 850, easing = EasyEase)
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
        separation = transition.animateFloat({ animationSpec }, label = "Siri separation") {
            target(it).separation
        }.value,
        fill = transition.animateFloat({ animationSpec }, label = "Siri fill") {
            target(it).fill
        }.value,
    )
}

private fun SiriOrbState.targetStyle(): SiriOrbStyle = when (this) {
    SiriOrbState.Idle -> SiriOrbStyle(
        sizeScale = 0.72f,
        motionScale = 0.25f,
        opacityScale = 0.28f,
        bloomScale = 0.55f,
        bloomAlpha = 0.35f,
        redWeight = 0.55f,
        blueWeight = 0.60f,
        tealWeight = 0.55f,
        pulseAmount = 0.02f,
        errorAmount = 0f,
        separation = 0.08f,
        fill = 0.15f,
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
        separation = 0f,
        fill = 0f,
    )

    SiriOrbState.Thinking -> SiriOrbStyle(
        sizeScale = 0.94f,
        motionScale = 1.25f,
        opacityScale = 0.95f,
        bloomScale = 0.72f,
        bloomAlpha = 0.70f,
        redWeight = 0.16f,
        blueWeight = 1.05f,
        tealWeight = 1.08f,
        pulseAmount = 0.02f,
        errorAmount = 0f,
        separation = 1f,
        fill = 1f,
    )

    SiriOrbState.Speaking -> SiriOrbStyle(
        sizeScale = 1f,
        motionScale = 0.80f,
        opacityScale = 1f,
        bloomScale = 1.05f,
        bloomAlpha = 1f,
        redWeight = 1f,
        blueWeight = 1f,
        tealWeight = 1f,
        pulseAmount = 1f,
        errorAmount = 0f,
        separation = 0f,
        fill = 0f,
    )

    SiriOrbState.Error -> SiriOrbStyle(
        sizeScale = 0.86f,
        motionScale = 0.55f,
        opacityScale = 0.92f,
        bloomScale = 0.90f,
        bloomAlpha = 0.88f,
        redWeight = 1.15f,
        blueWeight = 0.04f,
        tealWeight = 0.04f,
        pulseAmount = 0.55f,
        errorAmount = 1f,
        separation = 0.12f,
        fill = 0.20f,
    )
}
