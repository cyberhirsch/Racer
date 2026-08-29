package dev.racer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rigid-body dynamics: inertia, and impulses at contact points.
 *
 * What this replaces is worth naming, because the difference is most of what a
 * wreck looks like. The first version treated a piece as a point with a
 * "reach below" — one contact, straight down, resolved by snapping the body to
 * the ground and flipping the sign of its vertical speed. That gives a wreck
 * that lands flat, slides, and needs a hand-written rule to make anything tip
 * over.
 *
 * A body here has a real inertia tensor, taken from its bounding box, and its
 * box is tested corner by corner. A wing that comes down on one endplate has
 * one corner in the ground and seven out of it, so the impulse acts at the end
 * of a long lever and the thing cartwheels because the arithmetic says it
 * should, not because it was told to. Friction at each corner is what makes it
 * dig in and slew round rather than skate.
 *
 * Impulses rather than forces, because contact is not a spring: a penetrating
 * corner needs its velocity changed *now*, and a stiff enough spring to do
 * that in one step is a stiff enough spring to explode at the next.
 */
object Rigid {

    /**
     * A body's mass properties.
     *
     * The inertia of a solid box about its own centre, which is close enough
     * for bodywork: what matters is that a long piece is far harder to spin
     * about its short axis than its long one, and that is the whole content
     * of these three numbers.
     */
    class Inertia(mass: Float, half: Vec3) {
        val invMass = if (mass > 0f) 1f / mass else 0f

        /** The inverse principal moments, in the body's own frame. */
        val invLocal: Vec3

        init {
            val w = max(MIN_EXTENT, half.x * 2f)
            val h = max(MIN_EXTENT, half.y * 2f)
            val d = max(MIN_EXTENT, half.z * 2f)
            val k = mass / 12f
            val ix = k * (h * h + d * d)
            val iy = k * (w * w + d * d)
            val iz = k * (w * w + h * h)
            invLocal = Vec3(
                if (ix > 0f) 1f / ix else 0f,
                if (iy > 0f) 1f / iy else 0f,
                if (iz > 0f) 1f / iz else 0f
            )
        }

        /**
         * Apply the inverse inertia to a world-space vector.
         *
         * The tensor is diagonal in the body's frame and is not diagonal in
         * the world's, so rather than building and rotating a matrix every
         * step the vector is taken into the body's frame, scaled, and brought
         * back. Same answer, three rotations instead of a matrix triple
         * product, and nothing to keep in sync.
         */
        fun applyInverse(orientation: Quat, v: Vec3): Vec3 {
            val local = orientation.inverse().rotate(v)
            return orientation.rotate(
                Vec3(local.x * invLocal.x, local.y * invLocal.y, local.z * invLocal.z)
            )
        }
    }

    /** Everything a contact solver needs to know about one body. */
    interface Movable {
        val inertia: Inertia
        var position: Vec3
        var orientation: Quat
        var velocity: Vec3
        /** Angular velocity, world frame, radians per second. */
        var spin: Vec3
    }

    /** One resolved contact, for whoever wants to dent the panel that took it. */
    class Contact(
        /** Where it happened, in the world. */
        val at: Vec3,
        /** Out of the surface, toward the body. Unit length. */
        val normal: Vec3,
        /** How fast the two were closing, along the normal, m/s. */
        val closing: Float
    )

    /**
     * The eight corners of a body's box, in the world.
     *
     * Written into the caller's array so that resolving contacts every
     * sub-step for a dozen bodies does not allocate.
     */
    fun corners(b: Movable, half: Vec3, centreOffset: Vec3, out: Array<Vec3>) {
        var n = 0
        for (sx in -1..1 step 2) for (sy in -1..1 step 2) for (sz in -1..1 step 2) {
            val local = Vec3(half.x * sx, half.y * sy, half.z * sz) + centreOffset
            out[n++] = b.position + b.orientation.rotate(local)
        }
    }

    /**
     * Resolve one contact point against a fixed surface.
     *
     * [at] is the contact in the world and [normal] points out of the surface.
     * Returns the normal impulse applied, which is what a caller uses to
     * decide whether the blow was hard enough to bend anything.
     *
     * The denominator is the body's *effective mass* along the normal at this
     * point: how much velocity one unit of impulse buys, once the leverage of
     * the contact about the centre of mass is taken into account. It is what
     * makes a blow on a corner spin a piece instead of stopping it.
     */
    fun resolve(
        b: Movable,
        at: Vec3,
        normal: Vec3,
        restitution: Float,
        friction: Float
    ): Float {
        val r = at - b.position
        val relative = b.velocity + b.spin.cross(r)
        val alongNormal = relative.dot(normal)
        if (alongNormal >= 0f) return 0f

        val k = effectiveMass(b, r, normal)
        if (k <= 0f) return 0f

        // Restitution is dropped for slow contacts. Otherwise a body resting
        // on the ground keeps being given a small bounce by its own settling
        // and never sleeps.
        val bounce = if (-alongNormal > BOUNCE_THRESHOLD) restitution else 0f
        val j = -(1f + bounce) * alongNormal / k
        applyImpulse(b, r, normal * j)

        // Coulomb friction, in whatever direction the contact is actually
        // sliding. Clamped to the normal impulse, which is what stops a piece
        // sliding along the ground being brought to a halt so hard it is
        // thrown back the way it came.
        val after = b.velocity + b.spin.cross(r)
        val slide = after - normal * after.dot(normal)
        val speed = slide.length()
        if (speed > 1e-4f) {
            val t = slide * (1f / speed)
            // Enough to stop the sliding outright, or as much as the normal
            // impulse allows — whichever is less.
            val jt = min(friction * j, speed / effectiveMass(b, r, t))
            applyImpulse(b, r, t * -jt)
        }
        return j
    }

    private fun effectiveMass(b: Movable, r: Vec3, direction: Vec3): Float {
        val angular = b.inertia.applyInverse(b.orientation, r.cross(direction)).cross(r)
        return b.inertia.invMass + angular.dot(direction)
    }

    private fun applyImpulse(b: Movable, r: Vec3, impulse: Vec3) {
        b.velocity = b.velocity + impulse * b.inertia.invMass
        b.spin = b.spin + b.inertia.applyInverse(b.orientation, r.cross(impulse))
    }

    /**
     * Resolve two bodies against each other along [normal], which points from
     * [a] toward [b]. The same arithmetic, with both effective masses in the
     * denominator, so a wheel shoulders a wing aside and not the reverse.
     */
    fun resolvePair(
        a: Movable,
        b: Movable,
        at: Vec3,
        normal: Vec3,
        restitution: Float,
        friction: Float
    ): Float {
        val ra = at - a.position
        val rb = at - b.position
        val relative = (b.velocity + b.spin.cross(rb)) - (a.velocity + a.spin.cross(ra))
        val alongNormal = relative.dot(normal)
        if (alongNormal >= 0f) return 0f

        val k = effectiveMass(a, ra, normal) + effectiveMass(b, rb, normal)
        if (k <= 0f) return 0f
        val bounce = if (-alongNormal > BOUNCE_THRESHOLD) restitution else 0f
        val j = -(1f + bounce) * alongNormal / k
        applyImpulse(a, ra, normal * -j)
        applyImpulse(b, rb, normal * j)

        val after = (b.velocity + b.spin.cross(rb)) - (a.velocity + a.spin.cross(ra))
        val slide = after - normal * after.dot(normal)
        val speed = slide.length()
        if (speed > 1e-4f) {
            val t = slide * (1f / speed)
            val jt = min(friction * j, speed / (effectiveMass(a, ra, t) + effectiveMass(b, rb, t)))
            applyImpulse(a, ra, t * jt)
            applyImpulse(b, rb, t * -jt)
        }
        return j
    }

    /** How far the box reaches along [direction], from its centre. */
    fun support(half: Vec3, orientation: Quat, direction: Vec3): Float {
        val local = orientation.inverse().rotate(direction)
        return abs(local.x) * half.x + abs(local.y) * half.y + abs(local.z) * half.z
    }

    /** Nothing is treated as thinner than this, or its inertia blows up. */
    private const val MIN_EXTENT = 0.02f

    /** Below this closing speed a contact does not bounce at all, m/s. */
    private const val BOUNCE_THRESHOLD = 0.6f
}
