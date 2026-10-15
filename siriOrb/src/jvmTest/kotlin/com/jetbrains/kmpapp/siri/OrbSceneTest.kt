package com.jetbrains.kmpapp.siri

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class OrbSceneTest {

    @Test
    fun petalsStayInsideTheShell() {
        val scene = OrbScene()
        val radius = 360f
        var t = 0f
        while (t < 30f) {
            scene.update(t, 0f, 0f, radius)
            for (v in 0 until scene.vertexCount) {
                if (scene.colors[v] and 0xFFFFFF == 0) continue
                val x = scene.positions[v * 2]
                val y = scene.positions[v * 2 + 1]
                assertTrue(x * x + y * y <= (radius * 1.06f) * (radius * 1.06f), "vertex $v at t=$t is outside the orb")
            }
            t += 0.25f
        }
    }

    @Test
    fun outlineIsIrregularAndKeepsChanging() {
        val atZero = FloatArray(8) { orbOutlineScale(it * PI.toFloat() / 4f, 0f) }
        assertTrue(atZero.max() - atZero.min() > 0.03f, "outline deviation ${atZero.max() - atZero.min()}")
        val later = orbOutlineScale(0f, 3f)
        assertTrue(abs(later - atZero[0]) > 0.01f, "outline did not move")
        var t = 0f
        while (t < 20f) {
            var a = 0f
            while (a < 2f * PI.toFloat()) {
                val s = orbOutlineScale(a, t)
                assertTrue(s in 0.95f..1.04f, "scale $s at t=$t")
                a += 0.35f
            }
            t += 0.5f
        }

        val scene = OrbScene()
        val first = silhouette(scene, 0.5f)
        val second = silhouette(scene, 6f)
        assertTrue(first.max() - first.min() > 2f, "rim is circular: ${first.toList()}")
        assertTrue(abs(first.indices.maxBy { first[it] } - second.indices.maxBy { second[it] }) > 0 ||
            (first.zip(second).maxOf { (a, b) -> abs(a - b) }) > 1.5f, "silhouette did not change")
    }

    private fun silhouette(scene: OrbScene, t: Float): FloatArray {
        scene.update(t, 0f, 0f, 100f)
        val bins = FloatArray(12)
        for (v in 0 until scene.vertexCount) {
            if (scene.colors[v] and 0xFFFFFF == 0) continue
            val x = scene.positions[v * 2]
            val y = scene.positions[v * 2 + 1]
            val r = sqrt(x * x + y * y)
            var bin = ((atan2(y, x) / (2f * PI.toFloat()) + 0.5f) * bins.size).toInt()
            if (bin >= bins.size) bin = bins.size - 1
            if (r > bins[bin]) bins[bin] = r
        }
        return bins
    }

    @Test
    fun updateFitsInAFrameBudget() {
        val scene = OrbScene()
        repeat(120) { scene.update(it / 60f, 360f, 360f, 330f) }
        val frames = 600
        val start = System.nanoTime()
        repeat(frames) { scene.update(it / 60f, 360f, 360f, 330f) }
        val perFrameMs = (System.nanoTime() - start) / 1e6 / frames
        println("OrbScene.update: %.2f ms/frame".format(perFrameMs))
        assertTrue(perFrameMs < 8.0, "update took $perFrameMs ms")
    }
}
