package dev.racer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What is left of the car after it hits something.
 *
 * The driving model is a bicycle model: two dimensions, no roll, wheels that
 * never leave the road. That is the right model for driving and the wrong one
 * for crashing, so at the moment of impact the car stops being a vehicle and
 * becomes this — a set of rigid bodies, glued to each other at the points a
 * real one is bolted together, tumbling under gravity until they stop.
 *
 * Three things are simulated, and they are the three the eye actually reads:
 *
 *  - **Rigid bodies.** Every piece has a mass, a velocity, an orientation and
 *    a spin, and bounces off the ground with friction. Nothing here is
 *    animated or scripted; a wreck plays out differently depending on how fast
 *    and how squarely you hit.
 *  - **Glue that breaks.** Pieces are held on by joints with a finite
 *    strength. An impulse through a joint stronger than it can take detaches
 *    that piece, which then flies off on its own. The front wing is designed
 *    to let go; the tub is designed not to.
 *  - **Panels that deform.** Every blow leaves a permanent dent in whatever
 *    took it — at the point of contact, in the direction of travel, deeper the
 *    harder the hit. See [Deform]. Damage accumulates: a piece that breaks off
 *    and then lands on its edge is bent by both.
 *
 * Deliberately not simulated: piece-against-piece collision. It costs a broad
 * phase and a contact solver, and with the pieces flying apart rather than
 * together it is very nearly never the thing you are looking at.
 */
class Wreck(car: CarMesh.Car, start: Pose, impact: Impact) {

    /** Where the car was, and how it was moving, when it stopped being a car. */
    class Pose(
        val x: Double,
        val z: Double,
        val yaw: Double,
        val velocityX: Double,
        val velocityZ: Double,
        val yawRate: Double
    )

    /**
     * The blow that started it.
     *
     * [normalX]/[normalZ] point from whatever was hit back toward the car, so
     * the car is pushed *away* along them — the same convention the vehicle's
     * own collision response uses.
     */
    class Impact(
        val x: Double,
        val z: Double,
        val height: Double,
        val normalX: Double,
        val normalZ: Double,
        val speed: Double
    )

    /**
     * One rigid body: a piece of car, wherever it has got to.
     *
     * [base] never changes — it is the shape the piece was built with, and
     * every dent is measured from it. [vertices] is that shape with the damage
     * applied, and [shapeVersion] counts how many times it has been rebuilt so
     * a renderer holding a GPU copy knows when to take a new one.
     */
    class Body(
        val part: CarMesh.Part?,
        val wheel: CarMesh.Wheel?,
        val base: Mesh,
        /** The point the piece pivots about, in car coordinates. */
        val pivot: Vec3,
        /** Where the piece's own mesh origin sits relative to that pivot. */
        val meshOrigin: Vec3,
        val bounds: Mesh.Bounds,
        val mass: Float,
        /** Impulse the mounting can take before it lets go, N·s. Zero = never. */
        val glueStrength: Float
    ) {
        /** Rough size, for how far a blow spreads over this piece. */
        val radius: Float get() = bounds.radius

        /** How far the piece reaches below its pivot, as it is turned now. */
        val reachBelow: Float get() = bounds.reachBelow(orientation.toMat4())

        var position = Vec3(0f, 0f, 0f)
        var orientation = Quat.identity()
        var velocity = Vec3(0f, 0f, 0f)
        var spin = Vec3(0f, 0f, 0f)

        /** False once the mounting has failed and the piece is on its own. */
        var attached = true
            internal set

        var resting = false
            internal set

        var vertices: FloatArray = base.vertices
            private set
        var shapeVersion = 0
            private set

        internal val dents = ArrayList<Dent>(4)

        internal fun dent(d: Dent) {
            if (d.depth < 0.004f) return
            dents.add(d)
            // Past a certain amount of damage more of it adds nothing anyone
            // can see, and every dent is re-applied on every rebuild.
            if (dents.size > MAX_DENTS) dents.removeAt(0)
            vertices = Deform.apply(base.vertices, base.indices, dents)
            shapeVersion++
        }

        /** How far this piece has been bent out of shape, metres. */
        val damage: Float get() = if (dents.isEmpty()) 0f else Deform.worstDisplacement(base.vertices, vertices)

        /**
         * Where to draw it.
         *
         * The mesh is in car coordinates, so it has to be brought to the pivot,
         * spun about it, and then put back out in the world.
         */
        fun modelMatrix(): Mat4 =
            Mat4.translation(position.x, position.y, position.z) *
                orientation.toMat4() *
                Mat4.translation(-meshOrigin.x, -meshOrigin.y, -meshOrigin.z)
    }

    val bodies: ArrayList<Body> = ArrayList(10)

    /** The tub. Everything else is glued to this, and the camera follows it. */
    val chassis: Body

    /** True once everything has stopped moving; nothing more will change. */
    var settled = false
        private set

    var elapsed = 0.0
        private set

    init {
        for ((part, mesh) in car.partList) {
            val b = mesh.bounds()
            bodies.add(
                Body(
                    part = part, wheel = null, base = mesh,
                    pivot = b.centre, meshOrigin = b.centre,
                    bounds = b, mass = massOf(part), glueStrength = glueOf(part)
                )
            )
        }
        for (w in car.wheels) {
            val hub = Vec3(w.x, if (w.front) CarMesh.FRONT_RADIUS else CarMesh.REAR_RADIUS, w.z)
            bodies.add(
                Body(
                    part = null, wheel = w, base = w.mesh,
                    // A wheel's mesh is built around its own origin, so its
                    // pivot in car space and its mesh origin are not the same
                    // point — unlike the bodywork, which is modelled in place.
                    pivot = hub, meshOrigin = Vec3(0f, 0f, 0f),
                    bounds = w.mesh.bounds(),
                    mass = WHEEL_MASS, glueStrength = WHEEL_GLUE
                )
            )
        }
        chassis = bodies.first { it.part == CarMesh.Part.CHASSIS }

        // Put the whole car where the vehicle left off.
        chassis.position = Vec3(start.x, 0.0, start.z)
        chassis.orientation = Quat.yaw(start.yaw.toFloat())
        chassis.velocity = Vec3(start.velocityX, 0.0, start.velocityZ)
        chassis.spin = Vec3(0f, start.yawRate.toFloat(), 0f)
        // The chassis body's own pivot is inside the tub, not at the car's
        // origin on the ground, so it has to be lifted onto it.
        chassis.position = chassis.position + chassis.orientation.rotate(chassis.pivot)
        followChassis()

        applyBlow(
            atWorld = Vec3(impact.x, impact.height, impact.z),
            direction = Vec3(impact.normalX, 0.06, impact.normalZ).normalized(),
            speed = impact.speed.toFloat(),
            spread = FIRST_BLOW_SPREAD
        )
        // Whatever was hit stops the car dead where it touched, and the rest of
        // the car pivots about that point. Without this the tub sails serenely
        // through the tree it just lost its nose to.
        val lever = chassis.position - Vec3(impact.x, impact.height, impact.z)
        chassis.velocity = Vec3(impact.normalX, 0.0, impact.normalZ) * (impact.speed.toFloat() * 0.45f) +
            Vec3(0f, min(2.4f, impact.speed.toFloat() * 0.16f), 0f)
        chassis.spin = chassis.spin + lever.cross(
            Vec3(impact.normalX, 0.0, impact.normalZ) * -impact.speed.toFloat()
        ) * 0.30f
        clampSpin(chassis)
    }

    /**
     * Advance the wreck.
     *
     * Fixed sub-steps, like the driving physics, so what you see does not
     * depend on the frame rate — which matters more here than it does on the
     * road, because a bounce resolved at 8 fps and one resolved at 120 fps
     * send a piece to quite different places.
     */
    fun step(frameDelta: Double) {
        if (settled) return
        var remaining = min(frameDelta, 0.25)
        while (remaining > 1e-6) {
            val dt = min(STEP, remaining)
            substep(dt.toFloat())
            remaining -= dt
            elapsed += dt
        }
        if (elapsed > MAX_DURATION) settled = true
    }

    private fun substep(dt: Float) {
        for (b in bodies) {
            if (!b.attached) integrate(b, dt)
        }
        integrate(chassis, dt)
        followChassis()
        assemblyGroundContact(dt)
        for (b in bodies) {
            if (!b.attached) groundContact(b, dt)
        }
        settled = bodies.all { it.resting }
    }

    private fun integrate(b: Body, dt: Float) {
        if (b.resting) return
        b.velocity = b.velocity + Vec3(0f, -GRAVITY * dt, 0f)
        // A little drag, so a wing that has been thrown into the air does not
        // carry on at the speed it left at.
        val damp = 1f - min(0.6f, AIR_DRAG * dt)
        b.velocity = b.velocity * damp
        b.spin = b.spin * (1f - min(0.6f, SPIN_DRAG * dt))
        b.position = b.position + b.velocity * dt
        b.orientation = b.orientation.integrate(b.spin, dt)
    }

    /** Carry every piece that is still bolted on along with the tub. */
    private fun followChassis() {
        for (b in bodies) {
            if (!b.attached || b === chassis) continue
            val offset = b.pivot - chassis.pivot
            b.position = chassis.position + chassis.orientation.rotate(offset)
            b.orientation = chassis.orientation
            b.velocity = chassis.velocity + chassis.spin.cross(chassis.orientation.rotate(offset))
            b.spin = chassis.spin
            b.resting = chassis.resting
        }
    }

    /**
     * The ground contact for everything still bolted together.
     *
     * The car and the pieces still on it are one object, so they get one
     * contact: whichever piece is deepest into the ground is the one holding
     * the assembly up. Resolving each piece separately was tried, and the
     * corrections fight — the nose lifts the tub, which drops the floor into
     * the ground, which lifts it again — so the wreck twitches on the spot
     * forever and never comes to rest.
     *
     * The blow also goes through that piece's mounting into the tub, which is
     * what stops a car that lands nose-first and, if the mounting is not up to
     * it, is what takes the nose off.
     */
    private fun assemblyGroundContact(dt: Float) {
        var deepest: Body? = null
        var penetration = -Float.MAX_VALUE
        for (b in bodies) {
            if (!b.attached) continue
            val p = b.reachBelow - b.position.y
            if (p > penetration) { penetration = p; deepest = b }
        }
        val hit = deepest
        if (hit == null || penetration < -CONTACT_SKIN) {
            for (b in bodies) if (b.attached) b.resting = false
            return
        }
        if (chassis.resting) return

        val closing = -chassis.velocity.y
        if (closing > 0.35f) {
            applyBlow(
                atWorld = Vec3(hit.position.x, 0f, hit.position.z),
                direction = Vec3(0f, 1f, 0f),
                speed = closing,
                spread = GROUND_BLOW_SPREAD
            )
        }

        chassis.position = chassis.position + Vec3(0f, max(0f, penetration), 0f)
        if (chassis.velocity.y < 0f) {
            chassis.velocity = Vec3(chassis.velocity.x, -chassis.velocity.y * RESTITUTION, chassis.velocity.z)
        }
        val slide = 1f - min(0.9f, GROUND_FRICTION * dt * 8f)
        chassis.velocity = Vec3(chassis.velocity.x * slide, chassis.velocity.y, chassis.velocity.z * slide)

        // Off-centre contact tips the car over rather than stopping it flat.
        if (closing > 0.2f) {
            val lever = hit.position - chassis.position
            chassis.spin = chassis.spin + lever.cross(Vec3(0f, closing, 0f)) * 0.20f
        }
        clampSpin(chassis)

        val stillMoving = chassis.velocity.length() > SLEEP_SPEED || chassis.spin.length() > SLEEP_SPIN
        chassis.resting = false
        if (!stillMoving) {
            if (unstable(chassis)) {
                topple(chassis, dt)
            } else {
                chassis.velocity = Vec3(0f, 0f, 0f)
                chassis.spin = Vec3(0f, 0f, 0f)
                chassis.resting = true
            }
        }
        followChassis()
    }

    private fun groundContact(b: Body, dt: Float) {
        val rest = b.reachBelow
        val penetration = rest - b.position.y
        // A skin, rather than a strict test. Resolving a contact puts the body
        // exactly on the ground, so the very next step reads a penetration of
        // zero and calls it airborne; it then falls, lands, and is airborne
        // again — flickering in and out of contact forever and never sleeping.
        if (penetration < -CONTACT_SKIN) {
            b.resting = false
            return
        }
        if (b.resting) return
        b.position = Vec3(b.position.x, rest, b.position.z)

        val closing = -b.velocity.y
        if (closing > 0.35f) {
            b.dent(
                Dent(
                    at = localPointOf(b, Vec3(b.position.x, 0f, b.position.z)),
                    direction = localDirectionOf(b, Vec3(0f, 1f, 0f)),
                    depth = min(MAX_DENT, closing * DENT_PER_SPEED * 0.6f),
                    reach = max(0.25f, b.radius * 0.8f)
                )
            )
        }

        if (b.velocity.y < 0f) {
            b.velocity = Vec3(b.velocity.x, -b.velocity.y * RESTITUTION, b.velocity.z)
        }
        // Sliding on the ground scrubs speed off and spins the piece up, which
        // is why a wing that lands flat skitters and one that lands on a
        // corner cartwheels.
        val slide = 1f - min(0.9f, GROUND_FRICTION * dt * 12f)
        val before = Vec3(b.velocity.x, 0f, b.velocity.z)
        b.velocity = Vec3(b.velocity.x * slide, b.velocity.y, b.velocity.z * slide)
        val scrubbed = before - Vec3(b.velocity.x, 0f, b.velocity.z)
        // Sliding on a corner spins the piece up; sliding on a flat face
        // barely does. The moment arm is how far the contact is from the
        // pivot, which is exactly what reachBelow measures.
        b.spin = b.spin + Vec3(0f, 1f, 0f).cross(scrubbed) * (SLIDE_TO_SPIN * rest)
        clampSpin(b)

        val stillMoving = b.velocity.length() > SLEEP_SPEED || b.spin.length() > SLEEP_SPIN
        if (stillMoving) return
        if (unstable(b)) {
            topple(b, dt)
            return
        }
        b.velocity = Vec3(0f, 0f, 0f)
        b.spin = Vec3(0f, 0f, 0f)
        b.resting = true
    }

    /**
     * Is this piece balanced on an edge or an end?
     *
     * Nothing here models a contact patch, so a body is just as happy standing
     * on its nose as lying on its floor — and since it is allowed to fall
     * asleep as soon as it stops moving, that is exactly where wrecks were
     * ending up. A piece is unstable when whatever is holding it up is much
     * further from its centre than its flattest side would be.
     */
    private fun unstable(b: Body): Boolean {
        val flattest = min(b.bounds.half.x, min(b.bounds.half.y, b.bounds.half.z))
        return b.reachBelow > flattest * 1.35f + 0.05f
    }

    /**
     * Tip a balanced piece over onto its flattest side.
     *
     * Stand a front wing on its endplate and it falls flat. Nothing in a rigid
     * body of this kind does that on its own, because the support and the
     * weight act through the same point, so wrecks were coming to rest
     * balanced on end.
     *
     * This turns the piece a little each step and keeps whichever direction
     * actually lowers it — which is what gravity is doing, arrived at by
     * asking the question directly rather than by deriving a torque whose sign
     * depends on which way up the piece happens to be. It runs only once a
     * piece has otherwise stopped, so it never argues with the dynamics.
     */
    private fun topple(b: Body, dt: Float) {
        val r = b.orientation.toMat4().m
        val axes = listOf(
            Vec3(r[0], r[1], r[2]), Vec3(r[4], r[5], r[6]), Vec3(r[8], r[9], r[10])
        )
        val standing = axes.maxByOrNull { abs(it.y) } ?: return
        var axis = standing.cross(Vec3(0f, 1f, 0f))
        // Exactly on end there is nothing to lever against, so any horizontal
        // axis will do to get it off the balance point.
        if (axis.length() < 1e-3f) axis = Vec3(1f, 0f, 0f)
        axis = axis.normalized()

        val step = TOPPLE_RATE * dt
        val oneWay = (Quat.axisAngle(axis, step) * b.orientation).normalized()
        val theOther = (Quat.axisAngle(axis, -step) * b.orientation).normalized()
        b.orientation =
            if (b.bounds.reachBelow(oneWay.toMat4()) <= b.bounds.reachBelow(theOther.toMat4())) oneWay
            else theOther
        // Stay on the ground while falling over, rather than sinking into it.
        b.position = Vec3(b.position.x, b.bounds.reachBelow(b.orientation.toMat4()), b.position.z)
        b.velocity = Vec3(0f, 0f, 0f)
        b.spin = Vec3(0f, 0f, 0f)
    }

    /**
     * Deliver a blow to the whole car: dent what it lands on, and test every
     * mounting it reaches.
     *
     * How much of it a given piece sees falls away with distance from the
     * point of contact, which is why a nose-first shunt takes the front wing
     * off and leaves the rear one bolted on.
     */
    private fun applyBlow(atWorld: Vec3, direction: Vec3, speed: Float, spread: Float) {
        val broken = ArrayList<Body>(2)
        for (b in bodies) {
            val distance = (b.position - atWorld).length()
            val share = falloff(distance, spread)
            if (share < 0.02f) continue

            b.dent(
                Dent(
                    at = localPointOf(b, atWorld),
                    direction = localDirectionOf(b, direction),
                    depth = min(MAX_DENT, speed * DENT_PER_SPEED * share),
                    reach = max(0.30f, b.radius * (0.7f + share))
                )
            )

            if (!b.attached || b === chassis || b.glueStrength <= 0f) continue
            // The mounting has to change this piece's momentum by roughly the
            // whole car's change in velocity, concentrated by how close it is
            // to where the car was hit.
            val throughJoint = b.mass * speed * share
            if (throughJoint > b.glueStrength) broken.add(b)
        }
        for (b in broken) detach(b, atWorld, direction, speed)
    }

    private fun detach(b: Body, atWorld: Vec3, direction: Vec3, speed: Float) {
        b.attached = false
        b.resting = false
        val lever = b.position - atWorld
        // It leaves along the blow, carrying the car's own motion with it, and
        // tumbling about whatever moment arm it happened to have.
        val kick = min(speed * 0.55f, 14f)
        b.velocity = chassis.velocity + direction * kick +
            Vec3(0f, min(3.5f, speed * 0.20f), 0f)
        b.spin = b.spin + lever.cross(direction * speed) * 1.2f
        clampSpin(b)
    }

    private fun clampSpin(b: Body) {
        val l = b.spin.length()
        if (l > MAX_SPIN) b.spin = b.spin * (MAX_SPIN / l)
    }

    /** A world point, in the piece's own frame — where its dents are recorded. */
    private fun localPointOf(b: Body, world: Vec3): Vec3 {
        val relative = world - b.position
        val inBody = conjugate(b.orientation).rotate(relative)
        return inBody + b.meshOrigin
    }

    private fun localDirectionOf(b: Body, world: Vec3): Vec3 =
        conjugate(b.orientation).rotate(world).normalized()

    private fun conjugate(q: Quat) = Quat(-q.x, -q.y, -q.z, q.w)

    /** 1 at the point of contact, tailing off over [spread] metres. */
    private fun falloff(distance: Float, spread: Float): Float {
        val t = (1f - distance / spread).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Whether anything at all came off, for the HUD and the sound. */
    val piecesLost: Int get() = bodies.count { !it.attached }

    /** The worst bending anywhere on the car, metres. */
    val worstDamage: Float get() = bodies.maxOf { it.damage }

    companion object {
        private const val STEP = 1.0 / 120.0
        private const val MAX_DURATION = 12.0
        private const val GRAVITY = 9.81f
        private const val AIR_DRAG = 0.22f
        private const val SPIN_DRAG = 1.35f
        private const val RESTITUTION = 0.28f
        private const val GROUND_FRICTION = 0.55f
        private const val SLEEP_SPEED = 0.35f
        private const val SLIDE_TO_SPIN = 1.6f
        private const val SLEEP_SPIN = 0.60f
        private const val MAX_SPIN = 12f
        private const val MAX_DENTS = 6
        private const val CONTACT_SKIN = 0.02f
        private const val TOPPLE_RATE = 5.5f

        /** Metres of crumple per metre-per-second of impact. */
        private const val DENT_PER_SPEED = 0.014f
        private const val MAX_DENT = 0.34f

        /** How far from the point of contact the first blow is felt, metres. */
        private const val FIRST_BLOW_SPREAD = 2.2f
        private const val GROUND_BLOW_SPREAD = 2.0f

        private const val WHEEL_MASS = 14f
        private const val WHEEL_GLUE = 150f

        /**
         * Masses in kilograms, adding up to about the 800 kg an F1 car has to
         * weigh. The tub carries the engine and the gearbox, which is why it
         * is most of the car and why it is the thing that keeps going.
         */
        private fun massOf(part: CarMesh.Part) = when (part) {
            CarMesh.Part.CHASSIS -> 520f
            CarMesh.Part.FRONT_WING -> 12f
            CarMesh.Part.SIDEPOD_LEFT, CarMesh.Part.SIDEPOD_RIGHT -> 22f
            CarMesh.Part.ENGINE_COVER -> 15f
            CarMesh.Part.REAR_WING -> 14f
        }

        /**
         * What each mounting can take, in newton-seconds.
         *
         * A front wing is meant to come off — it is held on by two pillars
         * designed to fail before the tub does, and it lets go in a shunt that
         * barely marks anything else. A sidepod has to be hit properly in the
         * side. The tub is the survival cell and never detaches from itself.
         */
        private fun glueOf(part: CarMesh.Part) = when (part) {
            CarMesh.Part.CHASSIS -> 0f
            CarMesh.Part.FRONT_WING -> 60f
            CarMesh.Part.SIDEPOD_LEFT, CarMesh.Part.SIDEPOD_RIGHT -> 200f
            CarMesh.Part.ENGINE_COVER -> 220f
            CarMesh.Part.REAR_WING -> 200f
        }
    }
}
