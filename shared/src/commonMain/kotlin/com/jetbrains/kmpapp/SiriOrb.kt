package com.jetbrains.kmpapp

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val TwoPi = 6.2831855f
private const val ReferenceLoopMillis = 17_117
private const val StateTransitionMillis = 900

/**
 * The visual phases exposed by [SiriOrb].
 *
 * Names already in the API stay. The six presentation states map as:
 * [Appear] scales and fades in from 0;
 * [Idle] is the standard loop;
 * [Listening] and [Speaking] are voice interaction (world scale pulses with audio);
 * [Thinking] is processing (petals separate, inner hole closes);
 * [ShowingResult] is calm, slightly brighter, and slower;
 * [Disappear] scales and fades out.
 * [Error] stays a red alert state.
 */
enum class SiriOrbState {
    Idle,
    Listening,
    Thinking,
    Speaking,
    Error,
    Appear,
    ShowingResult,
    Disappear,
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
    val rotationScale: Float,
)

// Reference-frame samples: red peaks near #FF3847 / #FF2D3C, blue near #0080FF–#008DFF
// (closer than royal #0030FF), teal near #00E0C0 (closer than pale #5AF0E0).
internal val SiriBlue = Color(0xFF0A84FF)
internal val SiriTeal = Color(0xFF00FFCC)
internal val SiriRed = Color(0xFFFF2D55)

/**
 * A Siri-inspired animated orb. Ribbons are thin blades that cross the center and taper to needles.
 *
 * [audioLevel] (0..1, mapped onto a Sound Keys amplitude of 0..100) drives a
 * world-scale pulse of amplitude/40 while [state] is [SiriOrbState.Listening],
 * [SiriOrbState.Speaking], or [SiriOrbState.Error]. The caller controls size
 * through [modifier].
 *
 * State changes ease with [FastOutSlowInEasing]. [SiriOrbState.Appear] animates
 * in from scale and opacity 0 the first time it is shown, and whenever the
 * previous state was [SiriOrbState.Disappear].
 */
@Composable
fun SiriOrb(
    modifier: Modifier = Modifier,
    state: SiriOrbState = SiriOrbState.Idle,
    audioLevel: Float = 0.65f,
) {
    val clock = rememberInfiniteTransition(label = "Siri orb")
    val time = clock.animateFloat(
        initialValue = 0f,
        targetValue = ReferenceLoopMillis.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(ReferenceLoopMillis, easing = LinearEasing),
        ),
        label = "Siri orb time",
    )
    val transitionState = remember {
        MutableTransitionState(
            if (state == SiriOrbState.Appear) SiriOrbState.Disappear else state,
        )
    }
    transitionState.targetState = state
    SiriOrbFrame(
        modifier = modifier,
        timeMillis = time.value.toLong(),
        audioLevel = audioLevel,
        style = animatedStyle(transitionState),
    )
}

/**
 * One still frame of [SiriOrb] at an explicit [timeMillis] in the reference loop.
 *
 * [timeMillis] is taken modulo the loop period so values beyond one cycle wrap.
 * Style is the resting look for [state], with no state-to-state animation.
 * [SiriOrbState.Appear] therefore shows the fully appeared standard loop, and
 * [SiriOrbState.Disappear] shows the collapsed pose.
 */
@Composable
fun SiriOrbFrame(
    modifier: Modifier = Modifier,
    state: SiriOrbState = SiriOrbState.Idle,
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
    state: SiriOrbState = SiriOrbState.Idle,
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
private fun animatedStyle(transitionState: MutableTransitionState<SiriOrbState>): SiriOrbStyle {
    val transition = updateTransition(transitionState, label = "Siri state")
    val animationSpec = tween<Float>(durationMillis = StateTransitionMillis, easing = FastOutSlowInEasing)
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
        rotationScale = transition.animateFloat({ animationSpec }, label = "Siri rotation") {
            target(it).rotationScale
        }.value,
    )
}

private val StandardLoopStyle = SiriOrbStyle(
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
    rotationScale = 1f,
)

private fun SiriOrbState.targetStyle(): SiriOrbStyle = when (this) {
    // Standard loop. Appear rests on the same look; the entrance is the transition.
    SiriOrbState.Idle,
    SiriOrbState.Appear,
    -> StandardLoopStyle

    // Voice interaction. World scale pulses with audioLevel / 40.
    SiriOrbState.Listening,
    SiriOrbState.Speaking,
    -> StandardLoopStyle.copy(pulseAmount = 1f)

    // Processing: petals move apart and the inner alpha hole closes.
    SiriOrbState.Thinking -> StandardLoopStyle.copy(
        sizeScale = 0.96f,
        motionScale = 1.25f,
        opacityScale = 1.1f,
        separation = 1f,
        fill = 1f,
        rotationScale = 1.7f,
    )

    // Calm, slightly brighter, slower spin.
    SiriOrbState.ShowingResult -> StandardLoopStyle.copy(
        sizeScale = 1.02f,
        motionScale = 0.28f,
        opacityScale = 1.25f,
        bloomScale = 1.06f,
        bloomAlpha = 1.05f,
        fill = 0.06f,
        rotationScale = 0.40f,
    )

    SiriOrbState.Disappear -> StandardLoopStyle.copy(
        sizeScale = 0f,
        opacityScale = 0f,
        bloomScale = 0.2f,
        bloomAlpha = 0f,
        rotationScale = 1f,
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
        rotationScale = 0.75f,
    )
}
