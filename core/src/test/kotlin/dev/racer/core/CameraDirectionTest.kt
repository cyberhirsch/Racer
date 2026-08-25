package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos

/**
 * The camera looks where the car is going, not where it is pointing.
 *
 * These are the same thing right up until the car slides, which is exactly
 * when it matters: a view bolted to the nose swings off into the scenery
 * halfway through a corner, while the driver is trying to see the exit.
 */
class CameraDirectionTest {

    /** The direction the camera is looking, as an angle in the world. */
    private fun viewDirection(g: Game): Double {
        val cam = g.camera(1.0 / 60.0, 2f)
        val dx = (cam.target.x - cam.eye.x).toDouble()
        val dz = (cam.target.z - cam.eye.z).toDouble()
        return atan2(dx, dz)
    }

    private fun settle(g: Game, seconds: Double = 2.0) {
        repeat((seconds * 60).toInt()) { g.camera(1.0 / 60.0, 2f) }
    }

    @Test
    fun `driving straight, the view looks where the nose points`() {
        val g = Game()
        g.loadLevel(0)
        g.vehicle.yaw = 0.7
        g.vehicle.vx = 50.0
        g.vehicle.vy = 0.0
        g.resetCamera()
        settle(g)
        assertEquals(0.7, viewDirection(g), 0.02)
    }

    @Test
    fun `sliding sideways, the view follows the direction of travel`() {
        val g = Game()
        g.loadLevel(0)
        // Pointing straight down +Z but travelling well to the left of that:
        // twenty degrees of slip, which is a big but not absurd slide.
        g.vehicle.yaw = 0.0
        g.vehicle.vx = 40.0
        g.vehicle.vy = 40.0 * kotlin.math.tan(0.35)
        g.resetCamera()
        settle(g)

        val view = viewDirection(g)
        println("nose at 0.0 rad, travelling at %.2f rad, view at %.2f rad".format(0.35, view))
        assertEquals("the view should have followed the car's course", 0.35, view, 0.03)
    }

    @Test
    fun `the view swings round rather than snapping`() {
        val g = Game()
        g.loadLevel(0)
        g.vehicle.yaw = 0.0
        g.vehicle.vx = 40.0
        g.resetCamera()
        settle(g)

        // A sudden, violent slide: the view must not jump with it.
        g.vehicle.vy = 40.0 * kotlin.math.tan(0.5)
        val before = viewDirection(g)
        g.camera(1.0 / 60.0, 2f)
        val afterOneFrame = viewDirection(g)
        assertTrue("the view jumped ${abs(afterOneFrame - before)} rad in one frame",
            abs(afterOneFrame - before) < 0.05)

        settle(g)
        assertEquals("but it should get there", 0.5, viewDirection(g), 0.03)
    }

    @Test
    fun `barely moving, the view stays with the nose rather than chasing noise`() {
        val g = Game()
        g.loadLevel(0)
        g.vehicle.yaw = 1.2
        g.vehicle.vx = 0.2
        g.vehicle.vy = 0.3          // mostly scrub; the course here means nothing
        g.resetCamera()
        settle(g)
        assertEquals(1.2, viewDirection(g), 0.02)
    }
}
