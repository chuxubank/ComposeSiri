package com.jetbrains.kmpapp.siri

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.min
import kotlin.math.sin

/** The Siri orb in its idle state, animating on its own clock. */
@Composable
fun SiriOrb(modifier: Modifier = Modifier) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { time = (it - start) / 1_000_000_000f }
        }
    }
    SiriOrb(time = { time }, modifier = modifier)
}

/** The Siri orb at an externally driven [time], in seconds. */
@Composable
fun SiriOrb(time: () -> Float, modifier: Modifier = Modifier) {
    val frame = remember { OrbFrame(OrbScene()) }
    Box(modifier) {
        // Bloom: the same petals, blurred, under the sharp pass. Platforms without blur support
        // (Android before 12) simply draw the orb without it.
        Canvas(
            Modifier.matchParentSize().graphicsLayer {
                val blur = min(size.width, size.height) * BLOOM_RADIUS
                renderEffect = BlurEffect(blur, blur, TileMode.Decal)
                alpha = BLOOM_STRENGTH
            },
        ) {
            frame.prepare(this, time())
            frame.drawPetals(this)
            frame.drawRim(this)
        }
        Canvas(
            Modifier.matchParentSize().graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                blendMode = BlendMode.Plus
            },
        ) {
            val t = time()
            frame.prepare(this, t)
            frame.drawPetals(this)
            frame.drawRim(this)
            drawHalo(frame.radius)
            drawCoreGlow(frame.radius, t)
        }
    }
}

private const val ORB_RADIUS = 0.46f
private const val BLOOM_RADIUS = 0.035f
private const val BLOOM_STRENGTH = 0.45f

/** Shares one scene update between the bloom and sharp passes of a frame. */
private class OrbFrame(private val scene: OrbScene) {
    private val paint = Paint().apply {
        color = Color.White
        blendMode = BlendMode.Plus
    }
    private var preparedTime = Float.NaN
    private var preparedCenter = Offset.Unspecified
    private var petals: Vertices? = null
    private var rim: Vertices? = null
    var radius = 0f
        private set

    fun prepare(scope: DrawScope, time: Float) {
        val r = min(scope.size.width, scope.size.height) * ORB_RADIUS
        val c = scope.center
        if (time == preparedTime && c == preparedCenter && r == radius) return
        preparedTime = time
        preparedCenter = c
        radius = r
        scene.update(time, c.x, c.y, r)
        val points = PackedOffsets(scene.positions, scene.vertexCount)
        val colors = PackedColors(scene.colors, scene.vertexCount)
        petals = vertices(points, colors, IndexList(scene.indices, 0, scene.petalIndexCount))
        rim = vertices(points, colors, IndexList(scene.indices, scene.petalIndexCount, scene.indices.size))
    }

    fun drawPetals(scope: DrawScope) = draw(scope, petals)

    fun drawRim(scope: DrawScope) = draw(scope, rim)

    private fun draw(scope: DrawScope, vertices: Vertices?) {
        vertices ?: return
        scope.drawIntoCanvas { it.drawVertices(vertices, BlendMode.Modulate, paint) }
    }
}

/** Light scattered just outside the glass edge. */
private fun DrawScope.drawHalo(radius: Float) {
    val halo = radius * 1.12f
    val halation = Color(0xFF7FA6BF)
    drawCircle(
        brush = Brush.radialGradient(
            0.86f to Color.Transparent,
            0.9f to halation.copy(alpha = 0.11f),
            0.94f to halation.copy(alpha = 0.05f),
            0.97f to halation.copy(alpha = 0.02f),
            1f to Color.Transparent,
            center = center,
            radius = halo,
        ),
        radius = halo,
        blendMode = BlendMode.Plus,
    )
}

private fun DrawScope.drawCoreGlow(radius: Float, time: Float) {
    val glow = radius * (0.42f + 0.012f * sin(time * 1.3f))
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.White,
            0.2f to Color.White.copy(alpha = 0.95f),
            0.32f to Color(0xFFE6F8FF).copy(alpha = 0.7f),
            0.5f to Color(0xFFC4E6FF).copy(alpha = 0.3f),
            0.72f to Color(0xFFA8D4FF).copy(alpha = 0.08f),
            1f to Color.Transparent,
            center = center,
            radius = glow,
        ),
        radius = glow,
        blendMode = BlendMode.Plus,
    )
}

private fun vertices(points: List<Offset>, colors: List<Color>, indices: List<Int>) = Vertices(
    vertexMode = VertexMode.Triangles,
    positions = points,
    // Android rejects an empty texture array, so reuse positions even though no shader reads them.
    textureCoordinates = points,
    colors = colors,
    indices = indices,
)

private class PackedOffsets(private val xy: FloatArray, override val size: Int) : AbstractList<Offset>() {
    override fun get(index: Int) = Offset(xy[index * 2], xy[index * 2 + 1])
}

private class PackedColors(private val argb: IntArray, override val size: Int) : AbstractList<Color>() {
    override fun get(index: Int) = Color(argb[index])
}

private class IndexList(private val values: IntArray, private val from: Int, private val to: Int) : AbstractList<Int>() {
    override val size: Int get() = to - from
    override fun get(index: Int) = values[from + index]
}
