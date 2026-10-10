package com.jetbrains.kmpapp.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.use
import com.jetbrains.kmpapp.SiriOrb
import com.jetbrains.kmpapp.SiriOrbFrame
import com.jetbrains.kmpapp.SiriOrbState
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.math.roundToInt
import org.jetbrains.skia.EncodedImageFormat

private const val FrameSizePx = 870
private const val DefaultFps = 1.0
private const val DefaultSeconds = 17.0
private const val DemoFps = 10.0
private const val DemoSecondsPerState = 3.0

private val DemoStates = listOf(
    SiriOrbState.Appear,
    SiriOrbState.Idle,
    SiriOrbState.Listening,
    SiriOrbState.Thinking,
    SiriOrbState.ShowingResult,
    SiriOrbState.Disappear,
)

private data class PreviewArgs(
    val outDir: Path,
    val fps: Double,
    val seconds: Double,
    val state: SiriOrbState,
    val demo: Boolean,
) {
    val frameCount: Int
        get() = if (demo) {
            (DemoStates.size * fps * seconds).roundToInt().coerceAtLeast(DemoStates.size)
        } else {
            (fps * seconds).roundToInt().coerceAtLeast(1)
        }

    fun timeMillisAt(index: Int): Long = (index * 1000.0 / fps).toLong()
}

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    if ("--sheet-demo" in args) {
        val outIdx = args.indexOf("--out")
        renderSheetDemo(java.nio.file.Paths.get(if (outIdx >= 0) args[outIdx + 1] else "/tmp/sheetdemo"), (System.getenv("FPS") ?: "10").toDouble())
        return
    }
    val sheetIdx = args.indexOf("--sheets")
    if (sheetIdx >= 0) {
        val outIdx = args.indexOf("--out")
        renderSheetStills(
            java.nio.file.Paths.get(if (outIdx >= 0) args[outIdx + 1] else "/tmp/sheet"),
            args[sheetIdx + 1].toInt(), 1.0, 12.0,
        )
        return
    }
    val options = parseArgs(args)
    options.outDir.createDirectories()
    if (options.demo) {
        renderDemo(options)
    } else {
        renderStill(options)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun renderStill(options: PreviewArgs) {
    var timeMillis by mutableStateOf(0L)
    ImageComposeScene(width = FrameSizePx, height = FrameSizePx) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            SiriOrbFrame(
                modifier = Modifier.fillMaxSize(),
                state = options.state,
                timeMillis = timeMillis,
            )
        }
    }.use { scene ->
        repeat(options.frameCount) { index ->
            timeMillis = options.timeMillisAt(index)
            Snapshot.sendApplyNotifications()
            val image = scene.render()
            writePng(image, options.outDir.resolve("o%02d.png".format(index)))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun renderDemo(options: PreviewArgs) {
    var state by mutableStateOf(DemoStates.first())
    var level by mutableStateOf(0.5f)
    ImageComposeScene(width = FrameSizePx, height = FrameSizePx) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            SiriOrb(
                modifier = Modifier.fillMaxSize(),
                state = state,
                audioLevel = level,
            )
        }
    }.use { scene ->
        repeat(options.frameCount) { index ->
            val seconds = index / options.fps
            val stateIndex = (seconds / options.seconds).toInt().coerceIn(0, DemoStates.lastIndex)
            state = DemoStates[stateIndex]
            // Simulated speech envelope: syllables at ~4 Hz with phrase gaps.
            val syl = kotlin.math.abs(kotlin.math.sin(seconds * 12.6)).toFloat()
            val phrase = (0.5 + 0.5 * kotlin.math.sin(seconds * 2.1)).toFloat()
            level = (0.15f + 0.85f * syl * phrase).coerceIn(0f, 1f)
            Snapshot.sendApplyNotifications()
            val nanos = (index * 1_000_000_000.0 / options.fps).toLong()
            val image = scene.render(nanos)
            writePng(image, options.outDir.resolve("d%03d.png".format(index)))
        }
    }
}

private fun writePng(image: org.jetbrains.skia.Image, file: Path) {
    val encoded = image.encodeToData(EncodedImageFormat.PNG)
        ?: error("Failed to encode PNG for $file")
    file.writeBytes(encoded.bytes)
    println("Wrote $file")
}

private fun parseArgs(args: Array<String>): PreviewArgs {
    var outDir: Path? = null
    var fps: Double? = null
    var seconds: Double? = null
    var state = SiriOrbState.Idle
    var demo = false
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        fun value(): String {
            val inline = arg.substringAfter("=", missingDelimiterValue = "")
            if (inline.isNotEmpty()) return inline
            require(index + 1 < args.size) { "Missing value for $arg" }
            return args[++index]
        }
        when {
            arg == "--out" || arg.startsWith("--out=") -> outDir = Path(value())
            arg == "--fps" || arg.startsWith("--fps=") -> fps = value().toDouble()
            arg == "--seconds" || arg.startsWith("--seconds=") -> seconds = value().toDouble()
            arg == "--demo" -> demo = true
            arg == "--state" || arg.startsWith("--state=") -> state = parseState(value())
            else -> error("Unknown argument: $arg")
        }
        index++
    }
    require(outDir != null) { "Missing required --out DIR" }
    val resolvedFps = fps ?: if (demo) DemoFps else DefaultFps
    val resolvedSeconds = seconds ?: if (demo) DemoSecondsPerState else DefaultSeconds
    require(resolvedFps > 0.0) { "--fps must be > 0" }
    require(resolvedSeconds > 0.0) { "--seconds must be > 0" }
    return PreviewArgs(
        outDir = outDir,
        fps = resolvedFps,
        seconds = resolvedSeconds,
        state = state,
        demo = demo,
    )
}

private fun parseState(raw: String): SiriOrbState {
    val key = raw.lowercase().filter { it.isLetterOrDigit() }
    val aliases = mapOf(
        "appear" to SiriOrbState.Appear,
        "idle" to SiriOrbState.Idle,
        "standard" to SiriOrbState.Idle,
        "standardloop" to SiriOrbState.Idle,
        "listening" to SiriOrbState.Listening,
        "speaking" to SiriOrbState.Speaking,
        "voice" to SiriOrbState.Listening,
        "voiceinteraction" to SiriOrbState.Listening,
        "thinking" to SiriOrbState.Thinking,
        "processing" to SiriOrbState.Thinking,
        "showingresult" to SiriOrbState.ShowingResult,
        "result" to SiriOrbState.ShowingResult,
        "disappear" to SiriOrbState.Disappear,
        "error" to SiriOrbState.Error,
    )
    return aliases[key]
        ?: SiriOrbState.entries.find { it.name.equals(raw, ignoreCase = true) }
        ?: error(
            "Unknown state '$raw'. Expected one of ${SiriOrbState.entries.joinToString()} " +
                "or Appear, StandardLoop, VoiceInteraction, Processing, ShowingResult, Disappear",
        )
}
