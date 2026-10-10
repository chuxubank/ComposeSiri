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

private data class PreviewArgs(
    val outDir: Path,
    val fps: Double,
    val seconds: Double,
    val state: SiriOrbState,
) {
    val frameCount: Int get() = (fps * seconds).roundToInt().coerceAtLeast(1)

    fun timeMillisAt(index: Int): Long = (index * 1000.0 / fps).toLong()
}

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val options = parseArgs(args)
    options.outDir.createDirectories()

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
            val encoded = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("Failed to encode PNG for frame $index")
            val file = options.outDir.resolve("o%02d.png".format(index))
            file.writeBytes(encoded.bytes)
            println("Wrote $file")
        }
    }
}

private fun parseArgs(args: Array<String>): PreviewArgs {
    var outDir: Path? = null
    var fps = DefaultFps
    var seconds = DefaultSeconds
    var state = SiriOrbState.Listening
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
            arg == "--state" || arg.startsWith("--state=") -> {
                val raw = value()
                state = SiriOrbState.entries.find { it.name.equals(raw, ignoreCase = true) }
                    ?: error("Unknown state '$raw'. Expected one of ${SiriOrbState.entries.joinToString()}")
            }
            else -> error("Unknown argument: $arg")
        }
        index++
    }
    require(outDir != null) { "Missing required --out DIR" }
    require(fps > 0.0) { "--fps must be > 0" }
    require(seconds > 0.0) { "--seconds must be > 0" }
    return PreviewArgs(outDir = outDir, fps = fps, seconds = seconds, state = state)
}
