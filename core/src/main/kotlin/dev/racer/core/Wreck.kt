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
 *  - **Things to hit.** The tree or rock that started it is still standing,
 *    and the pieces are still solid to each other. A wing that comes off
 *    forwards can be flung back off the trunk, and the tub can be caught by
 *    its own wheel. Both are resolved as spheres against a cylinder and
 *    spheres against spheres — with ten-odd bodies the "broad phase" is the
 *    pair loop itself, and it buys the two moments a crash is actually
 *    watched for: the rebound, and the pile-up.
 */
class Wreck(
    car: CarMesh.Car,
    start: Pose,
    impact: Impact,
    /** What the car hit, still standing where it was. */
    private val struck: Standing? = null
) {

    /** A tree or a rock: an upright cylinder the wreck can bounce off. */
    class Standing(val x: Double, val z: Double, val radius: Double, val height: Double)

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

        /**
         * How many separate blows this piece has taken.
         *
         * [damage] is the worst single displacement, which is what the HUD
         * wants but saturates: a wing that comes off already crumpled to the
         * limit cannot report a larger number however much more it takes.
         */
        val dentCount: Int get() = dents.size

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

    /**
     * Carbon and turf, thrown by every blow.
     *
     * Owned by the wreck rather than by the game, because everything that
     * throws debris is in here and it has to be stepped on the wreck's clock —
     * shards that carry on at full speed through Impact Time look like they
     * belong to a different scene.
     */
    val debris = Debris()

    /** True once everything has stopped moving; nothing more will change. */
    var settled = false
        private set

    var elapsed = 0.0
        private set

    /** Seconds of physics the bodies have actually been through. */
    var simulated = 0.0
        private set

    /**
     * How fast the crash is playing, as a fraction of real time.
     *
     * Impact Time, after the trick Burnout built its whole reward loop on: the
     * moment of the hit is the thing the player wants to see, and at full
     * speed it is over in three frames. It opens at a crawl, holds while the
     * pieces come off, and winds back to normal as everything settles.
     *
     * Applied to the wreck alone. The HUD, the camera easing and the result
     * panel all still run on the wall clock, so the game does not feel like it
     * has hung.
     */
    val timeScale: Double
        get() = when {
            elapsed >= SLOWMO_END -> 1.0
            elapsed <= SLOWMO_HOLD -> SLOWMO_FLOOR
            else -> {
                val t = ((elapsed - SLOWMO_HOLD) / (SLOWMO_END - SLOWMO_HOLD)).coerceIn(0.0, 1.0)
                SLOWMO_FLOOR + (1.0 - SLOWMO_FLOOR) * (t * t)
            }
        }

    /**
     * How hard the last blow was, decaying, for the camera shake.
     *
     * Trauma rather than a shake: the camera squares it, so a big hit reads as
     * violent and a small one barely registers, and it dies away on its own.
     */
    var trauma = 0.0
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
        trauma = max(0.0, trauma - frameDelta * TRAUMA_FADE)
        if (settled) return
        var remaining = min(frameDelta, 0.25) * timeScale
        while (remaining > 1e-6) {
            val dt = min(STEP, remaining)
            substep(dt.toFloat())
            remaining -= dt
            // Simulated time, which is what the bodies experience.
            simulated += dt
        }
        // Wall-clock time, which is what the slow motion is scheduled against.
        // Advancing [elapsed] by the simulated amount instead would stretch
        // the ramp by however much it was already slowing things down, and the
        // crash would never come back up to speed.
        elapsed += min(frameDelta, 0.25)
        if (simulated > MAX_DURATION) settled = true
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
        struck?.let { standingContact(it) }
        pieceContacts()
        debris.step(dt)
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

        // Low enough that scraping along the ground keeps marking the piece
        // up, not just the one hard landing: a wreck that slides to a halt
        // should arrive scuffed all over.
        val closing = -b.velocity.y
        if (closing > 0.15f) {
            b.dent(
                Dent(
                    at = localPointOf(b, Vec3(b.position.x, 0f, b.position.z)),
                    direction = localDirectionOf(b, Vec3(0f, 1f, 0f)),
                    depth = min(MAX_DENT, closing * DENT_PER_SPEED * 0.6f),
                    reach = max(0.25f, b.radius * 0.8f)
                )
            )
            debris.burst(
                Vec3(b.position.x, 0.05f, b.position.z),
                Vec3(0f, 1f, 0f), closing, grass = true
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
        // Every blow shakes the camera, the first one hardest. Squared by the
        // camera, so this stays linear in the speed and the violence comes
        // from the squaring rather than from tuning two curves at once.
        trauma = min(1.0, trauma + speed / TRAUMA_SPEED)
        debris.burst(atWorld, direction, speed)
        // Whatever it hit was standing in the rough, so the blow tears up the
        // ground around it too.
        if (atWorld.y < GRASS_HEIGHT) debris.burst(atWorld, Vec3(0f, 1f, 0f), speed * 0.7f, grass = true)

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
            Vec3(0f, min(5.0f, speed * 0.28f), 0f)
        b.spin = b.spin + lever.cross(direction * speed) * 1.2f
        clampSpin(b)
    }

    /**
     * Bounce the wreck off whatever it hit.
     *
     * The obstacle is an upright cylinder and each body is a sphere of its own
     * reach, which is crude and is the right amount of crude: what the eye
     * reads is that the tree stopped the piece and threw it back, not the
     * shape of the contact patch. Only the horizontal part matters — nothing
     * here ever gets above a tree.
     */
    private fun standingContact(o: Standing) {
        val ox = o.x.toFloat(); val oz = o.z.toFloat()
        val top = o.height.toFloat()
        for (b in bodies) {
            // The assembly is moved by its tub, so only the tub is tested for
            // it; testing every bolted-on piece would apply the same push
            // several times over.
            if (b.attached && b !== chassis) continue
            if (b.position.y - b.radius > top) continue
            val dx = b.position.x - ox
            val dz = b.position.z - oz
            val distance = sqrt(dx * dx + dz * dz)
            val reach = o.radius.toFloat() + b.radius * SPHERE_SHRINK
            if (distance >= reach) continue
            val nx = if (distance > 1e-5f) dx / distance else 1f
            val nz = if (distance > 1e-5f) dz / distance else 0f

            b.position = Vec3(ox + nx * reach, b.position.y, oz + nz * reach)
            val closing = -(b.velocity.x * nx + b.velocity.z * nz)
            if (closing <= 0f) continue
            b.resting = false
            b.velocity = Vec3(
                b.velocity.x + nx * closing * (1f + RESTITUTION),
                b.velocity.y,
                b.velocity.z + nz * closing * (1f + RESTITUTION)
            )
            b.spin = b.spin + Vec3(0f, 1f, 0f).cross(Vec3(nx, 0f, nz)) * (closing * 0.30f)
            clampSpin(b)
            blow(b, Vec3(b.position.x - nx * b.radius, b.position.y, b.position.z - nz * b.radius),
                Vec3(nx, 0f, nz), closing)
            if (b === chassis) followChassis()
        }
    }

    /**
     * Pieces against each other.
     *
     * Only what has come off: two parts still bolted on cannot collide,
     * because they move as one body and were built not overlapping. That
     * leaves a handful of loose pieces, so the pair loop is the broad phase.
     */
    private fun pieceContacts() {
        val loose = bodies.filter { !it.attached }
        for (i in loose.indices) {
            for (j in i + 1 until loose.size) {
                val a = loose[i]; val b = loose[j]
                val dx = b.position.x - a.position.x
                val dy = b.position.y - a.position.y
                val dz = b.position.z - a.position.z
                val distance = sqrt(dx * dx + dy * dy + dz * dz)
                val reach = (a.radius + b.radius) * SPHERE_SHRINK
                if (distance >= reach || distance < 1e-5f) continue
                val nx = dx / distance; val ny = dy / distance; val nz = dz / distance

                // Split the overlap by mass, so a wheel shoulders a wing aside
                // rather than the two meeting in the middle.
                val total = a.mass + b.mass
                val overlap = reach - distance
                val aShare = b.mass / total
                a.position = a.position - Vec3(nx, ny, nz) * (overlap * aShare)
                b.position = b.position + Vec3(nx, ny, nz) * (overlap * (1f - aShare))

                val rvx = b.velocity.x - a.velocity.x
                val rvy = b.velocity.y - a.velocity.y
                val rvz = b.velocity.z - a.velocity.z
                val closing = -(rvx * nx + rvy * ny + rvz * nz)
                if (closing <= 0f) continue
                a.resting = false; b.resting = false
                val impulse = closing * (1f + RESTITUTION)
                a.velocity = a.velocity - Vec3(nx, ny, nz) * (impulse * aShare)
                b.velocity = b.velocity + Vec3(nx, ny, nz) * (impulse * (1f - aShare))
                a.spin = a.spin + Vec3(nx, ny, nz).cross(Vec3(rvx, rvy, rvz)) * 0.25f
                b.spin = b.spin - Vec3(nx, ny, nz).cross(Vec3(rvx, rvy, rvz)) * 0.25f
                clampSpin(a); clampSpin(b)

                val where = Vec3(
                    a.position.x + nx * a.radius,
                    a.position.y + ny * a.radius,
                    a.position.z + nz * a.radius
                )
                blow(a, where, Vec3(nx, ny, nz), closing)
                blow(b, where, Vec3(-nx, -ny, -nz), closing)
            }
        }
    }

    /** A single dent from a contact, and the shake that goes with it. */
    private fun blow(b: Body, atWorld: Vec3, direction: Vec3, closing: Float) {
        if (closing < 0.5f) return
        trauma = min(1.0, trauma + closing / (TRAUMA_SPEED * 3f))
        debris.burst(atWorld, direction, closing)
        b.dent(
            Dent(
                at = localPointOf(b, atWorld),
                direction = localDirectionOf(b, direction),
                depth = min(MAX_DENT, closing * DENT_PER_SPEED * 0.7f),
                reach = max(0.30f, b.radius * 0.8f)
            )
        )
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
        private const val RESTITUTION = 0.42f
        private const val GROUND_FRICTION = 0.55f
        private const val SLEEP_SPEED = 0.35f
        private const val SLIDE_TO_SPIN = 1.6f
        private const val SLEEP_SPIN = 0.60f
        private const val MAX_SPIN = 12f
        private const val MAX_DENTS = 6

        /** How slowly the moment of impact plays, and for how long. */
        private const val SLOWMO_FLOOR = 0.12
        private const val SLOWMO_HOLD = 0.75
        private const val SLOWMO_END = 2.6
        private const val TRAUMA_FADE = 1.6

        /** The impact speed that fills the shake meter on its own, m/s. */
        private const val TRAUMA_SPEED = 30f
        /** Below this a blow is close enough to the ground to tear up turf. */
        private const val GRASS_HEIGHT = 1.2f
        private const val CONTACT_SKIN = 0.02f

        /**
         * How much of a body's own reach counts as solid.
         *
         * A body's radius is the corner-to-corner reach of its bounding box,
         * which for a long thin thing like a wing is far more than the piece
         * really occupies. Taking it whole makes wreckage hover apart; this is
         * the fudge that stops it looking wrong in the common case.
         */
        private const val SPHERE_SHRINK = 0.62f
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
