package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The starting lights, as a sequence.
 *
 * The label is derived from a continuous timer, which is easy to get a second
 * out — showing four counts, or skipping "GO!" entirely. Stepping the clock
 * the way the game does and recording what would be on screen is the only way
 * to see the whole sequence at once.
 */
class CountdownTest {

    /** Every distinct label shown, in order, from lights out to green. */
    private fun sequence(): List<String> {
        val g = Game()
        g.loadLevel(0)
        g.startCountdown()

        val shown = mutableListOf<String>()
        var guard = 0
        while (g.state == Game.State.COUNTDOWN && guard++ < 10_000) {
            val label = g.countdownLabel?.let { if (it > 0) "$it" else "GO!" }
            if (label != null && shown.lastOrNull() != label) shown += label
            g.update(1.0 / 60.0, Input(0.0, 0.0, 0.0))
        }
        return shown
    }

    @Test
    fun `the countdown runs three two one go`() {
        assertEquals(listOf("3", "2", "1", "GO!"), sequence())
    }

    @Test
    fun `each count is held for about a second`() {
        val g = Game()
        g.loadLevel(0)
        g.startCountdown()

        val held = mutableMapOf<Int, Double>()
        val dt = 1.0 / 60.0
        var guard = 0
        while (g.state == Game.State.COUNTDOWN && guard++ < 10_000) {
            g.countdownLabel?.let { held[it] = (held[it] ?: 0.0) + dt }
            g.update(dt, Input(0.0, 0.0, 0.0))
        }
        for ((label, seconds) in held) {
            assertEquals("count $label was on screen for $seconds s", 1.0, seconds, 0.05)
        }
    }

    @Test
    fun `there is no label once the race is on`() {
        val g = Game()
        g.loadLevel(0)
        g.startCountdown()
        repeat(600) { g.update(1.0 / 60.0, Input(0.0, 0.0, 0.0)) }
        assertEquals(Game.State.RACING, g.state)
        assertNull(g.countdownLabel)
    }
}
