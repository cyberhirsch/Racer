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
 *  - **Rigid bodies.** Every piece has a mass, an inertia tensor, a velocity,
 *    an orientation and a spin, and meets the ground at the corners of its own
 *    box through an impulse solver with friction — see [Rigid]. Nothing here
 *    is animated or scripted: a piece cartwheels because it came down on one
 *    end and the arithmetic says it should.
 *  - **Glue that breaks.** Pieces are held on by joints with a finite
 *    strength. An impulse through a joint stronger than it can take detaches
 *    that piece, which then flies off on its own. The front wing is designed
 *    to let go; the tub is designed not to.
 *  - **Panels that deform.** Every piece is skinned to a lattice of point
 *    masses that a blow actually pushes around — see [SoftCage]. The fold
 *    develops over the frames after the impact rather than being stamped on
 *    at it, it carries through the panel instead of stopping at the edge of
 *    the contact, and past a yield strain it stays. Damage accumulates: a
 *    piece that breaks off and then lands on its edge is bent by both.
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
    ) : Rigid.Movable {

        /**
         * Mass, and how hard it is to spin about each of its own axes.
         *
         * Taken from the bounding box, which is why a front wing resists being
         * spun about its span far more readily than about its chord without
         * anybody having written that down.
         */
        override val inertia = Rigid.Inertia(mass, bounds.half)

        /**
         * The deformable lattice this piece's bodywork is skinned to.
         *
         * Every piece carries one, whether or not anything has hit it yet: a
         * sleeping cage costs one boolean test per step, and building one
         * mid-crash would stall the frame the impact lands on.
         */
        val cage: SoftCage = SoftCage.around(base, bounds)
        /** Rough size, for how far a blow spreads over this piece. */
        val radius: Float get() = bounds.radius

        /** How far the piece reaches below its pivot, as it is turned now. */
        val reachBelow: Float get() = bounds.reachBelow(orientation.toMat4())

        override var position = Vec3(0f, 0f, 0f)
        override var orientation = Quat.identity()
        override var velocity = Vec3(0f, 0f, 0f)
        override var spin = Vec3(0f, 0f, 0f)

        /** Where the box's centre sits relative to the pivot the body spins about. */
        val centreOffset: Vec3 get() = bounds.centre - meshOrigin

        /** False once the mounting has failed and the piece is on its own. */
        var attached = true
            internal set

        var resting = false
            internal set

        /** The current, deformed shape. Owned by the lattice. */
        val vertices: FloatArray get() = cage.vertices
        val shapeVersion: Int get() = cage.shapeVersion

        /** How many separate blows this piece has taken. */
        var blows = 0
            private set

        /**
         * How much of the lattice has been bent past its yield point.
         *
         * A count of permanently deformed constraints rather than a
         * displacement, so it keeps climbing after the panel is folded as far
         * as anyone can see — which [damage] does not.
         */
        val yielded: Int get() = cage.yielded

        /**
         * Push the panel about.
         *
         * The lattice works in the piece's own mesh coordinates, so a blow
         * arriving in world space is brought into them here — which is what
         * lets a piece that has broken off and is tumbling still crumple
         * correctly wherever it lands.
         */
        internal fun strike(atWorld: Vec3, directionWorld: Vec3, speed: Float, reach: Float) {
            // A wheel is a rim, a hub and a tyre, and none of them crumple the
            // way a carbon panel does. Letting the lattice have them turned
            // them into the most spectacularly destroyed part of the car,
            // which is not what happens to wheels.
            if (wheel != null) return
            if (speed < STRIKE_FLOOR) return
            blows++
            val relative = atWorld - position
            val at = orientation.inverse().rotate(relative) + meshOrigin
            val direction = orientation.inverse().rotate(directionWorld)
            cage.strike(at, direction, speed * STRIKE_GAIN, reach)
        }

        /** How far this piece has been bent out of shape, metres. */
        val damage: Float get() = cage.damage

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

    /** Reused for the eight corners of whichever box is being resolved. */
    private val cornerScratch = Array(8) { Vec3(0f, 0f, 0f) }

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
        // The panels crumple on the same clock as the bodies carrying them,
        // so a fold that starts in slow motion finishes in slow motion.
        for (b in bodies) b.cage.step(dt)
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
        // The assembly is one body but several boxes, so every piece that is
        // still bolted on offers its corners and they are all resolved
        // through the tub. That is what lands a car on its nose and lets it
        // pivot over onto its roof instead of slapping down flat.
        for (b in bodies) {
            if (!b.attached) continue
            groundImpulses(chassis, b, dt)
        }
        clampSpin(chassis)

        chassis.resting = false
        if (chassis.velocity.length() < SLEEP_SPEED && chassis.spin.length() < SLEEP_SPIN) {
            chassis.velocity = Vec3(0f, 0f, 0f)
            chassis.spin = Vec3(0f, 0f, 0f)
            chassis.resting = true
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
        b.position = Vec3(b.position.x, max(b.position.y, rest), b.position.z)

        groundImpulses(b, b, dt)
        clampSpin(b)

        if (b.velocity.length() > SLEEP_SPEED || b.spin.length() > SLEEP_SPIN) return
        b.velocity = Vec3(0f, 0f, 0f)
        b.spin = Vec3(0f, 0f, 0f)
        b.resting = true
    }

    /**
     * Resolve [box]'s eight corners against the ground, moving [mover].
     *
     * The two are the same body for a loose piece and different ones for
     * anything still bolted on, where the corners belong to the piece and the
     * momentum belongs to the tub.
     *
     * Every corner below the ground gets its own impulse, in sequence, each
     * one seeing the velocity the last one left behind. That is a Gauss-Seidel
     * sweep, and it is why a body landing flat settles instead of being
     * launched: the first corner takes most of the blow and the other three
     * find nothing left to do.
     */
    private fun groundImpulses(mover: Body, box: Body, dt: Float) {
        Rigid.corners(box, box.bounds.half, box.centreOffset, cornerScratch)
        var worst = 0f
        var worstAt: Vec3? = null
        for (c in cornerScratch) {
            // The same skin the airborne test uses, and for a sharper reason:
            // resolving a contact puts the lowest corner exactly on the
            // ground, so a strict test reads it as clear on the very next
            // step. A body could then slide for ever at whatever speed it
            // landed with, never touching anything that could slow it down.
            if (c.y > CONTACT_SKIN) continue
            val j = Rigid.resolve(mover, c, GROUND_NORMAL, RESTITUTION, GROUND_FRICTION)
            if (j > worst) { worst = j; worstAt = c }
        }
        val at = worstAt ?: return

        // Rolling resistance. Impulses alone leave a body in contact with a
        // small residual drift it never loses, and a wreck that drifts is a
        // wreck that never sleeps — so anything touching the ground is bled
        // down as well as bounced.
        val bleed = 1f - min(0.5f, ROLLING * dt)
        mover.velocity = mover.velocity * bleed
        mover.spin = mover.spin * bleed

        // The impulse is in newton-seconds; what bends a panel is the speed
        // that impulse represents to the piece that took it.
        val severity = worst * box.inertia.invMass
        if (severity < 0.15f) return
        box.strike(at, GROUND_NORMAL, severity, max(0.25f, box.radius * BLOW_REACH))
        debris.burst(Vec3(at.x, 0.05f, at.z), GROUND_NORMAL, severity, grass = true)
        // Only a real landing shakes the camera. A wreck grinding to a halt
        // resolves a contact on every sub-step, and topping the meter up from
        // each of them would leave the picture shaking for ever.
        if (severity > TRAUMA_LANDING) {
            trauma = min(1.0, trauma + severity / (TRAUMA_SPEED * 2f))
        }
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
        // Only a real blow shakes the camera. A wreck grinding along the
        // ground delivers small ones on nearly every sub-step, and letting
        // each of them top the meter up leaves the picture shaking for ever.
        if (speed > TRAUMA_LANDING) trauma = min(1.0, trauma + speed / TRAUMA_SPEED)
        debris.burst(atWorld, direction, speed)
        // Whatever it hit was standing in the rough, so the blow tears up the
        // ground around it too.
        if (atWorld.y < GRASS_HEIGHT) debris.burst(atWorld, Vec3(0f, 1f, 0f), speed * 0.7f, grass = true)

        val broken = ArrayList<Body>(2)
        for (b in bodies) {
            val distance = (b.position - atWorld).length()
            val share = falloff(distance, spread)
            if (share < 0.02f) continue

            // The blow has to reach into the panel without swallowing it: a
            // shove that touches every node equally moves the piece and
            // strains nothing, and the lattice comes through undamaged.
            b.strike(atWorld, direction, speed * share, max(0.25f, b.radius * BLOW_REACH))

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
            val normal = Vec3(nx, 0f, nz)
            // The contact is on the trunk's surface, not at the body's centre,
            // so hitting a tree square-on stops the piece and hitting it a
            // glancing blow slews it round.
            val at = Vec3(ox + nx * o.radius.toFloat(), b.position.y, oz + nz * o.radius.toFloat())
            val closing = -(b.velocity.dot(normal))
            val j = Rigid.resolve(b, at, normal, RESTITUTION, STANDING_FRICTION)
            if (j <= 0f) continue
            b.resting = false
            clampSpin(b)
            blow(b, at, normal, closing)
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

                val normal = Vec3(nx, ny, nz)
                val relative = b.velocity - a.velocity
                val closing = -relative.dot(normal)
                // Halfway between the two surfaces, which for two spheres is
                // where they actually touch.
                val where = (a.position + b.position) * 0.5f
                val j = Rigid.resolvePair(a, b, where, normal, RESTITUTION, PIECE_FRICTION)
                if (j <= 0f) continue
                a.resting = false; b.resting = false
                clampSpin(a); clampSpin(b)
                blow(a, where, normal, closing)
                blow(b, where, normal * -1f, closing)
            }
        }
    }

    /** A single dent from a contact, and the shake that goes with it. */
    private fun blow(b: Body, atWorld: Vec3, direction: Vec3, closing: Float) {
        if (closing < 0.5f) return
        if (closing > TRAUMA_LANDING) trauma = min(1.0, trauma + closing / (TRAUMA_SPEED * 3f))
        debris.burst(atWorld, direction, closing)
        b.strike(atWorld, direction, closing, max(0.25f, b.radius * BLOW_REACH))
    }

    private fun clampSpin(b: Body) {
        val l = b.spin.length()
        if (l > MAX_SPIN) b.spin = b.spin * (MAX_SPIN / l)
    }

    /** How much of a blow a piece [distance] away from it feels. */
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
        private const val RESTITUTION = 0.22f
        private const val GROUND_FRICTION = 0.85f
        private const val SLEEP_SPEED = 0.45f
        private const val SLEEP_SPIN = 0.60f
        private const val MAX_SPIN = 12f

        /** How slowly the moment of impact plays, and for how long. */
        private const val SLOWMO_FLOOR = 0.12
        private const val SLOWMO_HOLD = 0.75
        private const val SLOWMO_END = 2.6
        private const val TRAUMA_FADE = 1.6

        /** The impact speed that fills the shake meter on its own, m/s. */
        private const val TRAUMA_SPEED = 30f
        /** Below this a blow is close enough to the ground to tear up turf. */
        private const val GRASS_HEIGHT = 1.2f
        /** Straight up, which is the only surface normal the ground has. */
        private val GROUND_NORMAL = Vec3(0f, 1f, 0f)

        /** Coulomb friction against a trunk, and between two loose pieces. */
        private const val STANDING_FRICTION = 0.45f
        private const val PIECE_FRICTION = 0.40f

        /**
         * How much of an impact speed reaches the lattice.
         *
         * A blow at 30 m/s does not push the panel's material at 30 m/s — most
         * of it goes into moving the piece. This is the share that goes into
         * bending it instead, and it is the single knob for how fragile the
         * car looks.
         */
        private const val STRIKE_GAIN = 1.1f

        /** Below this a blow is a scuff and does not trouble the lattice, m/s. */
        private const val STRIKE_FLOOR = 0.2f

        /** A ground contact gentler than this is a scrape, not a landing, m/s. */
        private const val TRAUMA_LANDING = 3.5f

        /** How fast a body in contact with the ground bleeds off speed. */
        private const val ROLLING = 1.1f

        /** How far into a piece a blow reaches, as a share of its size. */
        private const val BLOW_REACH = 0.8f

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
