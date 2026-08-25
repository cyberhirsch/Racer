package dev.racer.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Procedural race circuits.
 *
 * A track is a Catmull-Rom loop through generated control points, sampled into
 * frames carrying position, tangent and a right-hand vector. Everything else —
 * the road mesh, kerbs, barriers, checkpoints and all collision — derives from
 * those frames, so the physics and the visuals can never disagree about where
 * the track is.
 *
 * Difficulty rises across levels: corners get tighter and more frequent, the
 * road narrows, the lap gets longer, and the fuel margin shrinks.
 */
data class Vec2(val x: Double, val z: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, z + o.z)
    operator fun minus(o: Vec2) = Vec2(x - o.x, z - o.z)
    operator fun times(s: Double) = Vec2(x * s, z * s)
    fun length() = sqrt(x * x + z * z)
    fun distanceTo(o: Vec2) = (this - o).length()
    fun normalized(): Vec2 {
        val l = length()
        return if (l < 1e-9) Vec2(0.0, 1.0) else Vec2(x / l, z / l)
    }
}

class Frame(val pos: Vec2, val tangent: Vec2, val right: Vec2, val distance: Double)

data class LevelConfig(
    val index: Int,
    val name: String,
    val seed: Int,
    val corners: Int,
    val length: Double,
    val minRadius: Double,
    val width: Double,
    val fuel: Double
)

object Levels {
    /**
     * Tuned with [Autopilot], which is what AutopilotTest re-checks in CI.
     *
     * `corners` and `minRadius` set the difficulty — measured as how fast the
     * same reference driver gets round, which falls from ~237 km/h on level 1
     * to ~181 km/h on level 6. `fuel` is that driver's measured consumption
     * times a margin that tightens as the levels go on: 2.2x on level 1 down to
     * 1.45x on level 6.
     */
    val BUILT_IN = listOf(
        LevelConfig(0, "Fiorano Shakedown", 101, 6, 1500.0, 100.0, 15.0, 2.25),
        LevelConfig(1, "Monza Sprint", 202, 10, 1900.0, 72.0, 14.0, 2.36),
        LevelConfig(2, "Suzuka Esses", 303, 14, 2300.0, 58.0, 13.0, 2.46),
        LevelConfig(3, "Monaco Tight", 404, 18, 2600.0, 46.0, 11.5, 2.50),
        LevelConfig(4, "Spa Endurance", 505, 26, 3000.0, 38.0, 11.0, 2.25),
        LevelConfig(5, "The Gauntlet", 623, 40, 3600.0, 26.0, 10.0, 2.55)
    )

    /** Beyond the hand-tuned set, keep escalating forever. */
    fun config(index: Int): LevelConfig {
        if (index < BUILT_IN.size) return BUILT_IN[index]
        val over = index - BUILT_IN.size + 1
        val last = BUILT_IN.last()
        return last.copy(
            index = index,
            name = "The Gauntlet +$over",
            seed = 700 + over * 37,
            corners = last.corners + over * 4,
            length = last.length + over * 220,
            minRadius = max(22.0, last.minRadius - over),
            width = max(9.0, last.width - over * 0.25),
            fuel = last.fuel + over * 0.20
        )
    }
}

/** Deterministic PRNG so a level number always yields the same circuit. */
private class Mulberry32(seed: Int) {
    private var a: Int = seed
    fun next(): Double {
        a += 0x6D2B79F5
        var t = a
        t = (t xor (t ushr 15)) * (1 or t)
        t += (t xor (t ushr 7)) * (61 or t)
        t = t xor (t ushr 14)
        return (t.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}

/** Uniform Catmull-Rom spline through a closed loop of control points. */
private class ClosedSpline(private val pts: List<Vec2>, private val tension: Double = 0.5) {
    private fun at(i: Int) = pts[((i % pts.size) + pts.size) % pts.size]

    /** Position at parameter t in [0,1) over the whole loop. */
    fun point(t: Double): Vec2 {
        val n = pts.size
        val scaled = (t % 1.0 + 1.0) % 1.0 * n
        val i = scaled.toInt()
        val f = scaled - i
        val p0 = at(i - 1); val p1 = at(i); val p2 = at(i + 1); val p3 = at(i + 2)
        val m1 = (p2 - p0) * tension
        val m2 = (p3 - p1) * tension
        val f2 = f * f; val f3 = f2 * f
        val h00 = 2 * f3 - 3 * f2 + 1
        val h10 = f3 - 2 * f2 + f
        val h01 = -2 * f3 + 3 * f2
        val h11 = f3 - f2
        return p1 * h00 + m1 * h10 + p2 * h01 + m2 * h11
    }

    fun tangent(t: Double, eps: Double = 1e-4): Vec2 = (point(t + eps) - point(t - eps)).normalized()
}

class Track(val cfg: LevelConfig) {
    val halfWidth = cfg.width / 2
    /**
     * Half-width of the sealed run-off: tarmac, then kerb, then gravel.
     *
     * Nothing stops you at the edge of it. There are no barriers anywhere on
     * the circuit — run wide and you keep going, onto the grass and out into
     * the countryside, with only grip to argue about it. It still sets how far
     * apart two stretches of track have to be laid.
     */
    val runoff = halfWidth + 3.0

    val frames: List<Frame>
    val curvature: DoubleArray
    val length: Double
    val tightestRadius: Double

    /** Checkpoint frame indices; the last one is the finish line. */
    val checkpoints: List<Int>

    init {
        val fit = fitCircuit(cfg, runoff)
        frames = fit.frames
        curvature = fit.curvature
        length = frames.last().distance
        tightestRadius = fit.tightestRadius

        val count = CHECKPOINT_COUNT
        checkpoints = (1..count).map {
            min(frames.size - 1, ((it.toDouble() / count) * (frames.size - 1)).roundToInt())
        }
    }

    /** Yaw (renderer convention) pointing along the track at a frame. */
    fun headingAt(index: Int): Double {
        val t = frames[index].tangent
        return atan2(t.x, t.z)
    }

    val startPose: Triple<Double, Double, Double>
        get() = Triple(frames[0].pos.x, frames[0].pos.z, headingAt(0))

    class Location(val index: Int, val lateral: Double, val frame: Frame, val curvature: Double)

    /**
     * Locate a world position relative to the track. Searches outward from the
     * last known frame, so this is O(few) per call rather than a scan of the
     * whole circuit.
     */
    fun locate(x: Double, z: Double, hint: Int = 0): Location {
        val n = frames.size
        var best = hint
        var bestD2 = Double.MAX_VALUE
        for (o in -SEARCH_SPAN..SEARCH_SPAN) {
            val i = (((hint + o) % n) + n) % n
            val p = frames[i].pos
            val d2 = (p.x - x) * (p.x - x) + (p.z - z) * (p.z - z)
            if (d2 < bestD2) { bestD2 = d2; best = i }
        }
        val f = frames[best]
        val lateral = (x - f.pos.x) * f.right.x + (z - f.pos.z) * f.right.z
        return Location(best, lateral, f, curvature[best])
    }

    class Surface(val loc: Location, val grip: Double, val offTrack: Boolean)

    /**
     * Grip for a car position.
     *
     * There is no wall to run into, at any distance: leaving the circuit is
     * allowed and always was more interesting than pinballing off a barrier.
     * What stops you is grip — tarmac, then kerb, then gravel, then grass —
     * and the fuel you are wasting while you are out there.
     */
    fun surface(x: Double, z: Double, hint: Int): Surface {
        val loc = locate(x, z, hint)
        val off = abs(loc.lateral)
        var grip = 1.0
        var offTrack = false
        if (off > halfWidth) {
            offTrack = true
            grip = max(GRASS_GRIP, 1.0 - (off - halfWidth) * 0.28)
        }
        return Surface(loc, grip, offTrack)
    }

    /** Signed lateral offset of the finish line, for the finish gate mesh. */
    fun frameAt(index: Int): Frame = frames[index]

    companion object {
        const val CHECKPOINT_COUNT = 8

        /** All the grip there is once you are well off the circuit. */
        const val GRASS_GRIP = 0.35
        private const val SEARCH_SPAN = 40
        private const val FRAME_SPACING = 3.0

        private fun controlPoints(cfg: LevelConfig): List<Vec2> {
            val rnd = Mulberry32(cfg.seed)
            val n = cfg.corners + 4
            val baseRadius = cfg.length / (2 * PI)
            // Radial variation drives corner severity: deeper notches, tighter turns.
            val severity = 0.20 + 0.42 * (1 - cfg.minRadius / 100)
            return (0 until n).map { i ->
                val t = i.toDouble() / n
                val angle = t * PI * 2 + (rnd.next() - 0.5) * (PI * 2 / n) * 0.55
                val r = baseRadius * (1 + (rnd.next() - 0.5) * 2 * severity)
                Vec2(cos(angle) * r, sin(angle) * r)
            }
        }

        private fun buildFrames(spline: ClosedSpline, approxLength: Double): List<Frame> {
            val count = max(64, (approxLength / FRAME_SPACING).roundToInt())
            val out = ArrayList<Frame>(count + 1)
            var distance = 0.0
            var prev: Vec2? = null
            for (i in 0..count) {
                val t = i.toDouble() / count
                val pos = spline.point(t)
                val tan = spline.tangent(t)
                // Right-hand vector: with +X right, +Y up and +Z forward, the
                // vector pointing to the car's right as it travels along the
                // tangent is (tangent.z, -tangent.x). Getting this backwards
                // silently mirrors every lateral quantity in the game.
                val right = Vec2(tan.z, -tan.x)
                if (prev != null) distance += pos.distanceTo(prev)
                out.add(Frame(pos, tan, right, distance))
                prev = pos
            }
            return out
        }

        private fun roughLength(spline: ClosedSpline): Double {
            var d = 0.0
            var prev = spline.point(0.0)
            for (i in 1..512) {
                val p = spline.point(i / 512.0)
                d += p.distanceTo(prev); prev = p
            }
            return d
        }

        private fun curvatures(frames: List<Frame>): DoubleArray {
            val k = DoubleArray(frames.size)
            for (i in 1 until frames.size - 1) {
                val a = frames[i - 1].tangent; val b = frames[i + 1].tangent
                val cross = a.x * b.z - a.z * b.x
                val ds = frames[i + 1].distance - frames[i - 1].distance
                k[i] = asin(clamp(cross, -1.0, 1.0)) / (if (ds == 0.0) 1.0 else ds)
            }
            if (k.size > 2) { k[0] = k[1]; k[k.size - 1] = k[k.size - 2] }
            return k
        }

        private class Fit(val frames: List<Frame>, val curvature: DoubleArray, val tightestRadius: Double)

        /**
         * Build a circuit that actually obeys the level's stated minimum radius.
         *
         * Raw control points can produce hairpins far tighter than the car can
         * physically negotiate, which just pinballs you off the barriers. So:
         * sample, measure the sharpest corner, and if it is too tight, relax the
         * control points toward their neighbours and try again.
         */
        /**
         * Distance below which two non-adjacent parts of the centreline are
         * considered to be on top of each other. Two stretches of track need
         * room for both their barriers plus a little scenery between them.
         */
        private fun minClearance(runoff: Double) = runoff * 2 + 4.0

        /**
         * True if the loop passes close to itself anywhere, which would put two
         * stretches of track (and their barriers) in the same place. Such a
         * circuit is unplayable: the car gets pinned in the overlap, and
         * [locate]'s local search cannot tell the two stretches apart.
         *
         * Checked on a coarse subsample — an overlap is many frames wide, so it
         * cannot hide between samples.
         */
        private fun selfIntersects(frames: List<Frame>, runoff: Double): Boolean {
            val clearance = minClearance(runoff)
            val stride = 4
            val n = frames.size - 1
            // Ignore neighbours: points near each other along the track are of
            // course near each other in space.
            val skip = ((clearance * 2) / FRAME_SPACING).toInt() + 8
            var i = 0
            while (i < n) {
                var j = i + skip
                while (j < n) {
                    // Wrap-aware: the start and end of the loop are neighbours too.
                    if (min(j - i, n - (j - i)) > skip &&
                        frames[i].pos.distanceTo(frames[j].pos) < clearance
                    ) return true
                    j += stride
                }
                i += stride
            }
            return false
        }

        /**
         * Fit a circuit, retrying with a nudged seed until the result is a
         * simple (non-self-overlapping) loop.
         */
        private fun fitCircuit(cfg: LevelConfig, runoff: Double): Fit {
            for (attempt in 0 until MAX_SEED_ATTEMPTS) {
                val fit = fitOnce(cfg.copy(seed = cfg.seed + attempt * 7919))
                if (!selfIntersects(fit.frames, runoff)) return fit
            }
            // Should not happen for any sane config, but never fail to produce a
            // track: fall back to the smoothest shape we can make.
            return fitOnce(cfg.copy(seed = cfg.seed, minRadius = cfg.minRadius * 2))
        }

        private fun fitOnce(cfg: LevelConfig): Fit {
            var pts = controlPoints(cfg)
            val maxCurvature = 1.0 / cfg.minRadius
            var frames: List<Frame>
            var curvature: DoubleArray
            var pass = 0

            while (true) {
                var spline = ClosedSpline(pts)
                // Restore the intended lap distance first, so the curvature check
                // below applies to the geometry we will actually race on.
                // (Scaling up relaxes curvature, so doing it afterwards would
                // wash the corners out.)
                val scale = cfg.length / roughLength(spline)
                if (abs(scale - 1.0) > 0.005) {
                    pts = pts.map { it * scale }
                    spline = ClosedSpline(pts)
                }
                frames = buildFrames(spline, roughLength(spline))
                curvature = curvatures(frames)

                val worst = curvature.maxOf { abs(it) }
                if (worst <= maxCurvature || ++pass > MAX_PASSES) break

                // Laplacian relaxation: pull each point toward the midpoint of
                // its neighbours, rounding off hairpins without unravelling the
                // overall shape.
                val n = pts.size
                pts = pts.mapIndexed { i, p ->
                    val a = pts[(i - 1 + n) % n]; val b = pts[(i + 1) % n]
                    Vec2(
                        p.x + ((a.x + b.x) / 2 - p.x) * 0.18,
                        p.z + ((a.z + b.z) / 2 - p.z) * 0.18
                    )
                }
            }

            var tightest = Double.MAX_VALUE
            for (c in curvature) if (abs(c) > 1e-6) tightest = min(tightest, 1.0 / abs(c))
            return Fit(frames, curvature, tightest)
        }

        private const val MAX_PASSES = 30
        private const val MAX_SEED_ATTEMPTS = 60
    }
}
