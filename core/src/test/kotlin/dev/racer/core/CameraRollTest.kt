package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.abs

/**
 * The camera roll that keeps the horizon level.
 *
 * A roll sign means nothing until it is drawn, and this one shipped wrong, so
 * it is measured here the same way it is measured on a real frame: take the
 * direction the world's horizon runs, put it through the view matrix, and look
 * at the angle it comes out at.
 */
class CameraRollTest {

    /**
     * The on-screen angle of the world horizon, in degrees, positive when it
     * runs down to the right (screen y measured downwards, as in a screenshot).
     */
    private fun horizonAngle(roll: Float): Double {
        val eye = Vec3(0f, 3f, 0f)
        val target = Vec3(0f, 1f, 20f)          // looking down +Z, slightly down
        val view = Mat4.lookAtRolled(eye, target, Vec3(0f, 1f, 0f), roll)

        // Two distant points at the same height: the horizon runs between them.
        val a = view.transformPoint(Vec3(-400f, 0f, 900f))
        val b = view.transformPoint(Vec3(400f, 0f, 900f))

        // Perspective divide, then flip y because screens count downwards.
        var ax = a.x / -a.z; var ay = -a.y / -a.z
        var bx = b.x / -b.z; var by = -b.y / -b.z

        // Measure left-to-right across the screen. Looking down +Z with +Y up,
        // world +X falls on the left of the frame, so the two sample points can
        // arrive in either order depending on the roll.
        if (ax > bx) {
            val tx = ax; val ty = ay
            ax = bx; ay = by
            bx = tx; by = ty
        }
        return Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble()))
    }

    @Test
    fun `with no roll the horizon is level`() {
        assertEquals(0.0, horizonAngle(0f), 0.05)
    }

    /**
     * The whole point: rolling the camera one way must tilt the drawn horizon
     * the other, so that when the player rotates the phone clockwise the
     * horizon rotates anticlockwise on the glass and stays level in their eyes.
     */
    @Test
    fun `a positive roll tilts the horizon the opposite way`() {
        for (deg in listOf(10.0, 25.0, 40.0)) {
            val measured = horizonAngle(Math.toRadians(deg).toFloat())
            println("roll %+.0f deg -> horizon %+.1f deg".format(deg, measured))
            assertEquals(
                "a +$deg deg camera roll should draw the horizon at -$deg deg",
                -deg, measured, 1.0
            )
        }
    }

    @Test
    fun `the roll is symmetric`() {
        for (deg in listOf(15.0, 30.0)) {
            val positive = horizonAngle(Math.toRadians(deg).toFloat())
            val negative = horizonAngle(Math.toRadians(-deg).toFloat())
            assertEquals(-positive, negative, 0.5)
        }
    }

    @Test
    fun `the game clamps the roll so the world cannot go upside down`() {
        val g = Game()
        g.loadLevel(0)
        g.viewRoll = 3.0                       // phone turned right over
        val cam = g.camera(1.0 / 60.0, 2f)
        assertTrue("roll should be clamped, was ${cam.rollRadians}", abs(cam.rollRadians) < 1.4f)
    }
}
