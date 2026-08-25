package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives every circuit headlessly to prove the levels are actually playable.
 *
 * This is the closest thing to playtesting that can run in CI, and it is what
 * the fuel budgets are derived from: a level's tank is the autopilot's measured
 * consumption times a margin.
 */
class AutopilotTest {

    @Test
    fun `every level is completable, and difficulty rises`() {
        println("lvl  track                 len    time     avg    top    fuel    tank   margin hits  off    minR")
        val avgSpeeds = mutableListOf<Double>()

        for (i in 0 until Levels.BUILT_IN.size) {
            val r = Autopilot.simulate(i)
            val cfg = r.cfg
            val margin = cfg.fuel / r.fuelUsed
            avgSpeeds += r.avgSpeed

            println(
                "%-4d %-21s %5.0fm %6.1fs %5.0f %6.0f %6.2fkg %6.2fkg %6.2fx %5.1fs %6.0fm".format(
                    i + 1, cfg.name, r.track.length, r.time, r.avgSpeed * 3.6,
                    r.topSpeed * 3.6, r.fuelUsed, cfg.fuel, margin,
                    r.offTrackTime, r.track.tightestRadius
                )
            )

            assertTrue("level ${i + 1} (${cfg.name}) could not be completed", r.finished)
            // Nothing stops a car leaving the circuit any more, so time spent
            // off it is the measure of whether the corners can actually be
            // taken: a lap driven mostly on the grass is not a drivable one.
            assertTrue("level ${i + 1} spent ${r.offTrackTime}s off the circuit — it is not cleanly drivable",
                r.offTrackTime < r.time * 0.25)

            // The tank must be enough for a clean lap, but not so generous that
            // fuel stops being the challenge.
            assertTrue("level ${i + 1} tank ${cfg.fuel}kg cannot cover a clean lap (${r.fuelUsed}kg)",
                margin > 1.15)
            assertTrue("level ${i + 1} tank ${cfg.fuel}kg is too generous (${margin}x a clean lap)",
                margin < 2.6)
        }

        // Difficulty is measured as how fast the same reference driver gets
        // round: the last level must be clearly harder than the first, and no
        // level may be harder than the finale.
        assertTrue("the last level (${avgSpeeds.last() * 3.6} km/h) should be clearly harder " +
            "than the first (${avgSpeeds.first() * 3.6} km/h)",
            avgSpeeds.last() < avgSpeeds.first() * 0.85)
        assertEquals("the last level should be the slowest",
            avgSpeeds.min(), avgSpeeds.last(), 1e-9)
    }

    @Test
    fun `the endless levels past the built-in set stay completable`() {
        for (i in Levels.BUILT_IN.size until Levels.BUILT_IN.size + 4) {
            val r = Autopilot.simulate(i)
            println("%-22s %5.0fm %6.1fs avg %3.0f km/h  fuel %.2f/%.2fkg  off %.1fs".format(
                r.cfg.name, r.track.length, r.time, r.avgSpeed * 3.6, r.fuelUsed, r.cfg.fuel, r.offTrackTime))
            assertTrue("${r.cfg.name} could not be completed", r.finished)
            assertTrue("${r.cfg.name} tank cannot cover a clean lap", r.cfg.fuel / r.fuelUsed > 1.1)
        }
    }

    @Test
    fun `running out of fuel actually stops the car`() {
        val r = Autopilot.simulate(0, fuel = 0.35)
        println("with a 0.35kg tank: finished=${r.finished} after ${"%.1f".format(r.time)}s")
        assertTrue("a near-empty tank should not complete the lap", !r.finished)
    }
}
