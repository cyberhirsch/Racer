package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the whole game loop the way the Android layer does, so the level flow
 * is covered without an emulator.
 */
class GameTest {

    /** Play a level with the reference driver until it ends or times out. */
    private fun play(g: Game, maxSeconds: Double = 300.0, frame: Double = 1.0 / 60.0): Double {
        var t = 0.0
        var steer = 0.0
        var hint = 0
        while (t < maxSeconds && g.state != Game.State.FINISHED && g.state != Game.State.FAILED) {
            val track = g.track!!
            val (input, newHint) = Autopilot.input(g.vehicle, track, hint, steer, frame)
            hint = newHint
            steer = input.steer
            g.update(frame, if (g.state == Game.State.RACING) input else Input())
            t += frame
        }
        return t
    }

    @Test
    fun `a fresh game starts in the menu with nothing loaded`() {
        val g = Game()
        assertEquals(Game.State.MENU, g.state)
        assertNull(g.track)
    }

    @Test
    fun `loading a level puts the car on the grid with a full tank`() {
        val g = Game()
        g.loadLevel(2)
        val t = g.track!!
        assertEquals(Levels.config(2).name, g.config.name)
        assertEquals(Levels.config(2).fuel, g.vehicle.fuel, 1e-9)
        assertEquals(0, g.nextCheckpoint)
        assertEquals(0.0, g.raceTime, 1e-9)

        val (x, z, _) = t.startPose
        assertEquals(x, g.vehicle.x, 1e-9)
        assertEquals(z, g.vehicle.z, 1e-9)
    }

    @Test
    fun `the car cannot move until the countdown finishes`() {
        val g = Game()
        g.loadLevel(0)
        g.startCountdown()
        assertEquals(Game.State.COUNTDOWN, g.state)

        // Full throttle during the countdown must do nothing.
        repeat(60) { g.update(1.0 / 60.0, Input(throttle = 1.0)) }
        assertEquals(0.0, g.vehicle.speed, 1e-9)
        assertNotNull(g.countdownLabel)

        repeat(240) { g.update(1.0 / 60.0, Input()) }
        assertEquals(Game.State.RACING, g.state)
        assertNull(g.countdownLabel)
    }

    @Test
    fun `a full lap passes every checkpoint in order and records a best time`() {
        val g = Game()
        g.loadLevel(0)
        g.startCountdown(0.0)
        g.update(0.01, Input())
        assertEquals(Game.State.RACING, g.state)

        play(g)
        assertEquals("did not finish", Game.State.FINISHED, g.state)
        assertEquals("did not pass every checkpoint", g.checkpointTotal, g.nextCheckpoint)
        assertTrue("race time looks wrong: ${g.raceTime}", g.raceTime in 15.0..60.0)
        assertTrue("should have finished with fuel left", g.vehicle.fuel > 0)
        assertTrue("first completion should be a personal best", g.newBest)
        assertEquals(g.raceTime, g.bestTime()!!, 1e-9)
        assertEquals(1.0, g.lapProgress, 1e-9)
        println("lap: ${Game.formatTime(g.raceTime)}, ${"%.2f".format(g.vehicle.fuel)}kg left")
    }

    @Test
    fun `a slower second lap does not overwrite the best time`() {
        val storage = Game.Storage.InMemory()
        storage.setBestTime(0, 5.0)
        val g = Game(storage)
        g.loadLevel(0)
        g.startCountdown(0.0)
        g.update(0.01, Input())
        play(g)
        assertEquals(Game.State.FINISHED, g.state)
        assertEquals("an impossible 5s best should have survived", 5.0, g.bestTime()!!, 1e-9)
        assertTrue(!g.newBest)
    }

    @Test
    fun `running out of fuel ends the race`() {
        val g = Game()
        g.loadLevel(0)
        g.startCountdown(0.0)
        g.update(0.01, Input())
        g.vehicle.fuel = 0.05
        play(g, maxSeconds = 60.0)
        assertEquals(Game.State.FAILED, g.state)
        assertEquals("OUT OF FUEL", g.failReason)
        assertTrue("should not have finished", g.nextCheckpoint < g.checkpointTotal)
    }

    @Test
    fun `retry restarts the same level and next level advances`() {
        val g = Game()
        g.loadLevel(1)
        g.startCountdown(0.0)
        g.update(0.01, Input())
        repeat(120) { g.update(1.0 / 60.0, Input(throttle = 1.0)) }
        assertTrue(g.vehicle.fuel < Levels.config(1).fuel)

        g.retry()
        assertEquals(1, g.levelIndex)
        assertEquals(Levels.config(1).fuel, g.vehicle.fuel, 1e-9)
        assertEquals(Game.State.COUNTDOWN, g.state)

        g.nextLevel()
        assertEquals(2, g.levelIndex)
        assertEquals(Levels.config(2).name, g.config.name)
        assertEquals(Levels.config(2).fuel, g.vehicle.fuel, 1e-9)
    }

    /**
     * Physics runs on a fixed step, so a 30 fps device and a 120 fps device must
     * produce the same car, or the game would be easier on better hardware.
     */
    @Test
    fun `the result does not depend on the frame rate`() {
        fun run(frame: Double): Triple<Double, Double, Double> {
            val g = Game()
            g.loadLevel(0)
            g.startCountdown(0.0)
            g.update(0.001, Input())
            var t = 0.0
            while (t < 12.0) { g.update(frame, Input(throttle = 1.0, steer = 0.25)); t += frame }
            return Triple(g.vehicle.x, g.vehicle.z, g.vehicle.speed)
        }
        val fast = run(1.0 / 120.0)
        val slow = run(1.0 / 30.0)
        println("120fps: %.2f, %.2f @ %.1f m/s   30fps: %.2f, %.2f @ %.1f m/s"
            .format(fast.first, fast.second, fast.third, slow.first, slow.second, slow.third))
        assertEquals("x drifted with frame rate", fast.first, slow.first, 2.0)
        assertEquals("z drifted with frame rate", fast.second, slow.second, 2.0)
        assertEquals("speed drifted with frame rate", fast.third, slow.third, 0.5)
    }

    @Test
    fun `the camera trails the car and never rolls the horizon`() {
        val g = Game()
        g.loadLevel(0)
        g.startCountdown(0.0)
        g.update(0.01, Input())
        repeat(600) { g.update(1.0 / 60.0, Input(throttle = 1.0, steer = 0.6)) }

        val cam = g.camera(1.0 / 60.0, 2.0f)
        val dx = cam.eye.x - g.vehicle.x.toFloat()
        val dz = cam.eye.z - g.vehicle.z.toFloat()
        val distance = kotlin.math.sqrt(dx * dx + dz * dz)
        assertTrue("camera is $distance m from the car", distance in 4f..14f)
        assertTrue("camera should be above the car", cam.eye.y in 1.5f..4.5f)

        // The look target must be ahead of the car, not behind it.
        val ahead = (cam.target.x - g.vehicle.x.toFloat()) * kotlin.math.sin(g.vehicle.yaw).toFloat() +
                (cam.target.z - g.vehicle.z.toFloat()) * kotlin.math.cos(g.vehicle.yaw).toFloat()
        assertTrue("camera should look ahead of the car, not behind", ahead > 0)
        assertTrue("field of view out of range", cam.fovDegrees in 40f..64f)
    }

    @Test
    fun `checkpoint gates disappear as they are passed`() {
        val g = Game()
        g.loadLevel(0)
        g.startCountdown(0.0)
        g.update(0.01, Input())
        assertTrue(g.gateVisible(0))
        play(g)
        // After finishing, every gate has been passed.
        for (i in 0 until g.checkpointTotal) assertTrue("gate $i still showing", !g.gateVisible(i))
    }
}
