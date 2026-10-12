package com.jetbrains.kmpapp.siri

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
