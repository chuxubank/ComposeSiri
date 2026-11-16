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
    fun silhouetteIsDynamicAndNonCircular() {
        val scene = OrbScene()
        val r0 = outerSilhouette(scene, 0.5f)
        val r1 = outerSilhouette(scene, 4.0f)
        assertTrue(r0.max() - r0.min() > 2.0f, "silhouette too close to a circle: ${r0.toList()}")
        val delta = r0.indices.maxOf { abs(r0[it] - r1[it]) }
        assertTrue(delta > 1.2f, "silhouette did not deform over time: max delta $delta")
    }

    private fun outerSilhouette(scene: OrbScene, time: Float): FloatArray {
        scene.update(time, 0f, 0f, 100f)
        val bins = FloatArray(16)
        for (v in 0 until scene.vertexCount) {
            if (scene.colors[v] and 0xFFFFFF == 0) continue
            val x = scene.positions[v * 2]
            val y = scene.positions[v * 2 + 1]
            val d = sqrt(x * x + y * y)
            var b = ((atan2(y, x) / (2f * PI.toFloat()) + 0.5f) * bins.size).toInt()
            if (b >= bins.size) b = bins.size - 1
            if (d > bins[b]) bins[b] = d
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
