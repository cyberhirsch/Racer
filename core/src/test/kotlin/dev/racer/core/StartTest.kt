package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The race is live from the first frame.
 *
 * There used to be a four-second countdown holding the car still. It never
 * worked properly on a real device, and it was time the player spent watching
 * rather than driving. The start lights remain, but as scenery.
 */
class StartTest {

    @Test
    fun `the car can be driven from the very first frame`() {
        val g = Game()
        g.loadLevel(0)
        g.start()
        assertEquals(Game.State.RACING, g.state)

        repeat(60) { g.update(1.0 / 60.0, Input(throttle = 1.0)) }
        println("after one second of throttle: %d km/h".format(g.speedKmh))
        assertTrue("the car should be moving a second in, not waiting", g.speedKmh > 30)
    }

    /**
     * Five lights, one every half second, then all out together — the way a
     * real gantry does it. They are cosmetic, so nothing here should affect
     * whether the car can be driven.
     */
    @Test
    fun `the start lights come on one at a time and go out together`() {
        val g = Game()
        g.loadLevel(0)
        g.start()

        val seen = mutableListOf<Int>()
        repeat((6.0 * 60).toInt()) {
            if (seen.lastOrNull() != g.startLightsLit) seen += g.startLightsLit
            g.update(1.0 / 60.0, Input())
        }
        println("lights: $seen")
        // The first light is on the moment the sequence starts, as on a real
        // gantry — the driver is not waiting for something to happen.
        assertEquals(listOf(1, 2, 3, 4, 5, 0), seen)
    }

    @Test
    fun `the lights fade away and stop being drawn`() {
        val g = Game()
        g.loadLevel(0)
        g.start()
        assertTrue(g.startLightsVisible)

        repeat((10.0 * 60).toInt()) { g.update(1.0 / 60.0, Input()) }
        assertTrue("the gantry should be long gone by ten seconds", !g.startLightsVisible)
    }
}
