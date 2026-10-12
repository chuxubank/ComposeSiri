package com.jetbrains.kmpapp.siri.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.jetbrains.kmpapp.siri.SiriOrb
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the orb headlessly to numbered PNGs so it can be compared frame by frame with the
 * reference footage.
 *
 * Arguments: `outDir [size=720] [fps=30] [seconds=12] [startSeconds=0]`.
 */
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "build/frames" })
    val size = args.getOrNull(1)?.toInt() ?: 720
    val fps = args.getOrNull(2)?.toInt() ?: 30
    val seconds = args.getOrNull(3)?.toFloat() ?: 12f
    val start = args.getOrNull(4)?.toFloat() ?: 0f

    outDir.mkdirs()
    outDir.listFiles { f -> f.name.endsWith(".png") }?.forEach { it.delete() }

    val time = mutableFloatStateOf(start)
    val scene = ImageComposeScene(size, size, Density(1f)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            SiriOrb(time = { time.floatValue }, modifier = Modifier.fillMaxSize())
        }
    }
    val frames = (seconds * fps).toInt().coerceAtLeast(1)
    val started = System.nanoTime()
    for (i in 0 until frames) {
        time.floatValue = start + i.toFloat() / fps
        val image = scene.render(i * 1_000_000_000L / fps)
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
        File(outDir, "f_%04d.png".format(i)).writeBytes(png.bytes)
        image.close()
    }
    scene.close()
    val perFrame = (System.nanoTime() - started) / 1_000_000.0 / frames
    println("Rendered $frames frames to ${outDir.absolutePath} (%.1f ms/frame incl. encoding)".format(perFrame))
}
