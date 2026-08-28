package dev.racer.core

import kotlin.math.max
import kotlin.math.min

/**
 * The stuff that flies off a crash.
 *
 * Carbon shards off the bodywork and torn grass off the verge: hundreds of
 * small flat pieces that are far too numerous to be rigid bodies and far too
 * visible to leave out. A crash without them reads as a car quietly coming
 * apart; with them it reads as a car exploding.
 *
 * They are simulated as points with a spin, and drawn as one triangle soup in
 * world space — every shard writes its four corners into a single buffer that
 * the renderer uploads once and draws in one call. That is the whole reason
 * this is not simply more [Wreck.Body] instances: two hundred bodies would be
 * two hundred draw calls and two hundred matrix uploads, and the geometry is
 * a quad.
 *
 * Nothing here is a collision: shards bounce off the ground and are otherwise
 * unaware of the world. At the speeds and sizes involved nobody can tell, and
 * the pieces that *are* worth resolving properly are already bodies.
 */
class Debris(private val capacity: Int = MAX_SHARDS) {

    private class Shard {
        var alive = false
        var position = Vec3(0f, 0f, 0f)
        var velocity = Vec3(0f, 0f, 0f)
        var orientation = Quat.identity()
        var spin = Vec3(0f, 0f, 0f)
        var size = 0f
        var life = 0f
        var maxLife = 1f
        var r = 0f; var g = 0f; var b = 0f; var specular = 0f
        /** Grass flutters and settles; carbon skitters. */
        var light = false
    }

    private val shards = Array(capacity) { Shard() }
    private var next = 0
    private var rng = Rng(0x5EEDL)

    /** Interleaved vertices for every live shard, in world space. */
    val vertices = FloatArray(capacity * VERTICES_PER_SHARD * Mesh.FLOATS_PER_VERTEX)

    /**
     * A fixed index buffer.
     *
     * Every shard owns the same four slots for its whole life, so the indices
     * never change and the renderer can upload them once. Dead shards write a
     * degenerate quad — four coincident points — which costs two triangles the
     * rasteriser throws away immediately and saves rebuilding the index buffer
     * every time one expires.
     */
    val indices = IntArray(capacity * 6).also {
        for (s in 0 until capacity) {
            val v = s * VERTICES_PER_SHARD
            val o = s * 6
            it[o] = v; it[o + 1] = v + 1; it[o + 2] = v + 2
            it[o + 3] = v; it[o + 4] = v + 2; it[o + 5] = v + 3
        }
    }

    /** Bumped whenever [vertices] changes, so a GPU copy knows to refresh. */
    var shapeVersion = 0
        private set

    val liveCount: Int get() = shards.count { it.alive }

    /**
     * Throw a burst of wreckage.
     *
     * [direction] is roughly where it goes and [speed] how hard it was hit;
     * the spread comes from the speed, so a light knock puffs and a heavy one
     * sprays.
     */
    fun burst(at: Vec3, direction: Vec3, speed: Float, grass: Boolean = false) {
        val count = min(capacity, (speed * (if (grass) GRASS_PER_SPEED else CARBON_PER_SPEED)).toInt())
        repeat(count) {
            val s = shards[next]
            next = (next + 1) % capacity

            s.alive = true
            s.position = at + randomInSphere() * 0.4f
            // Along the blow, opened out into a cone, and always with some
            // lift: debris that never gets above knee height is not read as
            // debris at all.
            val scatter = randomInSphere() * (speed * SPREAD)
            s.velocity = direction * (speed * (0.25f + rng.unit() * 0.55f)) + scatter +
                Vec3(0f, speed * 0.10f * rng.unit() + 1.5f, 0f)
            s.orientation = Quat.axisAngle(randomInSphere().normalized(), rng.unit() * 6.28f)
            s.spin = randomInSphere() * SPIN
            s.maxLife = LIFE * (0.6f + rng.unit() * 0.8f)
            s.life = s.maxLife
            s.light = grass

            if (grass) {
                s.size = 0.05f + rng.unit() * 0.07f
                val shade = 0.25f + rng.unit() * 0.35f
                s.r = 0.16f * shade; s.g = 0.55f * shade; s.b = 0.14f * shade
                s.specular = 0.05f
            } else {
                s.size = 0.04f + rng.unit() * 0.10f
                // Mostly carbon, with the odd flash of Ferrari red off a
                // painted panel — which is what makes the spray read as a
                // *car* coming apart rather than as generic sparks.
                if (rng.unit() < 0.22f) {
                    s.r = 0.72f; s.g = 0.06f; s.b = 0.05f; s.specular = 0.55f
                } else {
                    val shade = 0.10f + rng.unit() * 0.10f
                    s.r = shade; s.g = shade; s.b = shade * 1.1f; s.specular = 0.70f
                }
            }
        }
        if (count > 0) rebuild()
    }

    /** Advance every live shard. [dt] is simulated seconds, so slow motion applies. */
    fun step(dt: Float) {
        if (dt <= 0f) return
        var any = false
        for (s in shards) {
            if (!s.alive) continue
            any = true
            s.life -= dt
            if (s.life <= 0f) { s.alive = false; continue }

            val drag = if (s.light) GRASS_DRAG else CARBON_DRAG
            s.velocity = s.velocity + Vec3(0f, -GRAVITY * dt, 0f)
            s.velocity = s.velocity * (1f - min(0.8f, drag * dt))
            s.spin = s.spin * (1f - min(0.8f, SPIN_DRAG * dt))
            s.position = s.position + s.velocity * dt
            s.orientation = s.orientation.integrate(s.spin, dt)

            val floor = s.size * 0.5f
            if (s.position.y < floor) {
                s.position = Vec3(s.position.x, floor, s.position.z)
                if (s.velocity.y < 0f) {
                    s.velocity = Vec3(
                        s.velocity.x * GROUND_SLIDE,
                        -s.velocity.y * (if (s.light) 0.05f else BOUNCE),
                        s.velocity.z * GROUND_SLIDE
                    )
                }
                s.spin = s.spin * 0.6f
            }
        }
        if (any) rebuild()
    }

    fun clear() {
        for (s in shards) s.alive = false
        next = 0
        rng = Rng(0x5EEDL)
        rebuild()
    }

    /**
     * Write every shard's four corners into [vertices].
     *
     * A shard is a flat quad turned by its own orientation, so a tumbling
     * piece flashes as it catches the light edge-on and then face-on — which
     * is most of what sells it as a solid chip rather than a sprite. It
     * shrinks over the last of its life instead of fading, because the vertex
     * format has no alpha and adding one would cost a blend pass over the
     * whole scene for this.
     */
    private fun rebuild() {
        val stride = Mesh.FLOATS_PER_VERTEX
        for (i in shards.indices) {
            val s = shards[i]
            var o = i * VERTICES_PER_SHARD * stride
            if (!s.alive) {
                // Degenerate: four coincident points, no pixels.
                for (k in 0 until VERTICES_PER_SHARD * stride) vertices[o + k] = 0f
                continue
            }
            val fade = min(1f, s.life / max(1e-3f, s.maxLife * FADE_SHARE))
            val half = s.size * 0.5f * fade
            val right = s.orientation.rotate(Vec3(half, 0f, 0f))
            val up = s.orientation.rotate(Vec3(0f, 0f, half))
            val n = s.orientation.rotate(Vec3(0f, 1f, 0f))

            for (corner in 0 until 4) {
                val sx = if (corner == 0 || corner == 3) -1f else 1f
                val sz = if (corner < 2) -1f else 1f
                vertices[o] = s.position.x + right.x * sx + up.x * sz
                vertices[o + 1] = s.position.y + right.y * sx + up.y * sz
                vertices[o + 2] = s.position.z + right.z * sx + up.z * sz
                vertices[o + 3] = n.x; vertices[o + 4] = n.y; vertices[o + 5] = n.z
                vertices[o + 6] = s.r; vertices[o + 7] = s.g; vertices[o + 8] = s.b
                vertices[o + 9] = s.specular
                o += stride
            }
        }
        shapeVersion++
    }

    private fun randomInSphere(): Vec3 {
        while (true) {
            val x = rng.unit() * 2f - 1f
            val y = rng.unit() * 2f - 1f
            val z = rng.unit() * 2f - 1f
            val l = x * x + y * y + z * z
            if (l > 1e-4f && l <= 1f) return Vec3(x, y, z)
        }
    }

    /** Its own generator, so debris never disturbs the track's or the wreck's. */
    private class Rng(seed: Long) {
        private var state = seed or 1L
        fun unit(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toInt() and 0x7FFFFF).toFloat() / 8388608f
        }
    }

    companion object {
        /**
         * The whole budget, shared by every burst.
         *
         * One buffer of a fixed size, recycled oldest-first, so a crash that
         * throws six bursts costs exactly as much as one that throws one.
         */
        const val MAX_SHARDS = 220
        private const val VERTICES_PER_SHARD = 4

        private const val CARBON_PER_SPEED = 3.4f
        private const val GRASS_PER_SPEED = 2.0f
        private const val SPREAD = 0.22f
        private const val SPIN = 14f
        private const val LIFE = 3.2f

        /** The last fifth of a shard's life is spent shrinking away. */
        private const val FADE_SHARE = 0.2f

        private const val GRAVITY = 9.81f
        private const val CARBON_DRAG = 0.9f
        private const val GRASS_DRAG = 3.2f
        private const val SPIN_DRAG = 0.7f
        private const val BOUNCE = 0.30f
        private const val GROUND_SLIDE = 0.55f
    }
}
