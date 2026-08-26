package dev.racer.core

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The game itself: level flow, checkpoints, the fuel deadline, and the chase
 * camera.
 *
 * Deliberately free of Android APIs so the whole game loop can be driven from
 * tests. The Android layer only feeds it input and draws what it reports.
 */
class Game(private val storage: Storage = Storage.InMemory()) {

    /** Somewhere to keep best times. The Android app backs this with SharedPreferences. */
    interface Storage {
        fun bestTime(level: Int): Double?
        fun setBestTime(level: Int, seconds: Double)

        class InMemory : Storage {
            private val map = HashMap<Int, Double>()
            override fun bestTime(level: Int) = map[level]
            override fun setBestTime(level: Int, seconds: Double) { map[level] = seconds }
        }
    }

    enum class State { MENU, RACING, FINISHED, FAILED }

    var state = State.MENU
        private set
    var levelIndex = 0
        private set
    var track: Track? = null
        private set
    val vehicle = Vehicle()

    var raceTime = 0.0
        private set
    var nextCheckpoint = 0
        private set
    var lapProgress = 0.0
        private set
    var topSpeed = 0.0
        private set
    var startFuel = 1.0
        private set
    var failReason: String? = null
        private set
    var newBest = false
        private set

    /** Which checkpoint gates are still to be passed, for the renderer. */
    fun gateVisible(i: Int) = i >= nextCheckpoint

    private var trackHint = 0
    private var accumulator = 0.0

    val config: LevelConfig get() = Levels.config(levelIndex)
    fun bestTime(level: Int = levelIndex) = storage.bestTime(level)

    fun loadLevel(index: Int) {
        levelIndex = index
        wreck = null
        val cfg = Levels.config(index)
        val t = Track(cfg)
        track = t

        val (x, z, yaw) = t.startPose
        vehicle.reset(x, z, yaw)
        vehicle.fuel = cfg.fuel
        startFuel = cfg.fuel

        trackHint = 0
        accumulator = 0.0
        raceTime = 0.0
        nextCheckpoint = 0
        lapProgress = 0.0
        topSpeed = 0.0
        failReason = null
        newBest = false
        state = State.MENU
        beyondDeepGrass = false
    }

    /**
     * Go racing. There is nothing to wait for.
     *
     * There used to be a four-second countdown holding the car still. It was
     * never right on a real device and it was time the player spent watching
     * rather than driving. The start lights still run — see [startLightsLit] —
     * but they are scenery: the car is live from the first frame.
     */
    fun start() {
        if (track == null) loadLevel(levelIndex)
        sinceStart = 0.0
        wreck = null
        state = State.RACING
    }

    /** Seconds since the race began, for the start lights. */
    var sinceStart = 0.0
        private set

    /**
     * How many of the five start lights are lit, as a real gantry does it: the
     * first comes on as the sequence begins, one more every half second, and
     * then all five go out together.
     */
    val startLightsLit: Int
        get() = when {
            sinceStart >= LIGHTS_OUT -> 0
            else -> min(5, (sinceStart / LIGHT_INTERVAL).toInt() + 1)
        }

    /** True while the lights are worth drawing at all. */
    val startLightsVisible: Boolean
        get() = state == State.RACING && sinceStart < LIGHTS_GONE

    /** 1 while the lights hold, falling to 0 as they fade away after going out. */
    val startLightsFade: Float
        get() = ((LIGHTS_GONE - sinceStart) / (LIGHTS_GONE - LIGHTS_OUT))
            .coerceIn(0.0, 1.0).toFloat()

    /**
     * Advance the game by a frame.
     *
     * Physics runs at a fixed 120 Hz regardless of the frame rate, so the car
     * behaves identically on a 60 Hz and a 120 Hz screen. If the renderer
     * stalls, the backlog is dropped rather than simulated in one huge lurch.
     */
    fun update(frameDelta: Double, input: Input) {
        val dt = min(frameDelta, MAX_FRAME_DELTA)

        when (state) {
            State.RACING -> {
                sinceStart += dt
                accumulator += dt
                var steps = 0
                while (accumulator >= STEP && steps < MAX_STEPS) {
                    if (!physicsStep(input)) break
                    accumulator -= STEP
                    steps++
                }
                if (steps >= MAX_STEPS) accumulator = 0.0
                if (state == State.RACING) {
                    raceTime += dt
                    topSpeed = max(topSpeed, vehicle.speed)
                    checkProgress()
                }
            }
            // A crash is not over when the race is: the wreck goes on
            // tumbling, shedding pieces and bending itself, until it stops.
            State.FAILED -> wreck?.step(dt)
            else -> Unit
        }
    }

    private fun physicsStep(input: Input): Boolean {
        val t = track ?: return false
        val surf = t.surface(vehicle.x, vehicle.z, trackHint)
        trackHint = surf.loc.index
        vehicle.gripScale = surf.grip
        vehicle.offTrack = surf.offTrack

        vehicle.step(STEP, input)
        holdInsideTheWorld(t)
        if (!hitScenery(t)) return false

        if (vehicle.fuel <= 0.0) {
            fail("OUT OF FUEL")
            return false
        }
        return true
    }

    /**
     * Keep the car inside the ground that exists.
     *
     * Past the deep grass the going gets heavier the further out you are, so a
     * car pointed at the horizon slows to a crawl and stops of its own accord.
     * It can always be driven back; nothing pushes it anywhere.
     *
     * The clamp at the very edge is a backstop, not the mechanism. Reaching it
     * means the drag above failed to stop the car — at which point the choice
     * is between an abrupt halt and driving off the end of the world.
     */
    /**
     * Trees and rocks are solid.
     *
     * Above walking pace, hitting one ends the race: an F1 car is a carbon tub
     * and a tree is a tree. Below it, nudging one just stops you — a race
     * should not end because the car rolled into a rock at 5 km/h while you
     * were working out which way to point it.
     *
     * @return false if the race is over, matching the physics step's contract.
     */
    private fun hitScenery(t: Track): Boolean {
        val hit = t.obstacleNear(vehicle.x, vehicle.z, trackHint, CAR_REACH) ?: return true

        val dx = vehicle.x - hit.x
        val dz = vehicle.z - hit.z
        val distance = hypot(dx, dz)
        val nx = if (distance > 1e-6) dx / distance else 1.0
        val nz = if (distance > 1e-6) dz / distance else 0.0
        val impact = vehicle.speed
        // Taken before the collision response, which zeroes all of it: the
        // wreck has to inherit the motion the car had when it hit, not the
        // nothing it has immediately afterwards.
        val heading = sin(vehicle.yaw) to cos(vehicle.yaw)
        val worldVx = vehicle.vx * heading.first + vehicle.vy * heading.second
        val worldVz = vehicle.vx * heading.second - vehicle.vy * heading.first
        val wasYawRate = vehicle.yawRate

        vehicle.hitSomethingSolid(nx, nz, hit.radius + CAR_REACH - distance + 0.05)
        lastImpact = impact

        if (impact > CRASH_SPEED) {
            wreck = Wreck(
                car,
                Wreck.Pose(vehicle.x, vehicle.z, vehicle.yaw, worldVx, worldVz, wasYawRate),
                Wreck.Impact(
                    // Where the car touched it, not where the obstacle's
                    // centre is: a tree is hit on its bark. The normal points
                    // from the obstacle toward the car, so this steps out to
                    // the side of it the car arrived on — the other sign puts
                    // the blow a whole trunk away, on the far side, and the
                    // damage comes out far too gentle for the speed.
                    x = hit.x + nx * hit.radius,
                    z = hit.z + nz * hit.radius,
                    height = IMPACT_HEIGHT,
                    normalX = nx, normalZ = nz, speed = impact
                )
            )
            fail(if (hit.tree) "HIT A TREE" else "HIT A ROCK")
            return false
        }
        return true
    }

    /**
     * The car's geometry.
     *
     * Built once and shared: the renderer draws it and [Wreck] takes it apart,
     * and building it at the moment of a crash would put a hitch in exactly
     * the frame nobody wants one in.
     */
    val car: CarMesh.Car by lazy { CarMesh.build() }

    /**
     * What is left of the car, once there is anything left of it.
     *
     * Non-null from the moment of a crash until the next race starts. While it
     * exists it, and not [vehicle], is where the car actually is.
     */
    var wreck: Wreck? = null
        private set

    /**
     * True while the crash is still worth watching.
     *
     * The result panel covers the whole screen, and putting it up on the frame
     * of the impact would hide the one thing the player wants to see. It waits
     * until the wreck has stopped moving, or until a few seconds have passed
     * for a shunt that is still cartwheeling across the grass.
     */
    val crashPlaying: Boolean
        get() = wreck?.let { !it.settled && it.elapsed < RESULT_DELAY } ?: false

    /** Set when the car hits something solid, for the haptics. */
    var lastImpact = 0.0
        private set

    /** True while the car is out in the deep grass, for the HUD's warning. */
    var beyondDeepGrass = false
        private set

    private fun holdInsideTheWorld(t: Track) {
        // Where the car has ended up, not where it was before the step: this
        // is the last thing standing between it and the end of the ground.
        val loc = t.locate(vehicle.x, vehicle.z, trackHint)
        val beyond = abs(loc.lateral) - t.deepGrass
        beyondDeepGrass = beyond > 0.0
        if (beyond <= 0.0) return

        // 0 at the edge of the deep grass, 1 where the ground runs out.
        val k = (beyond / max(1.0, t.edge - t.deepGrass)).coerceIn(0.0, 1.0)
        vehicle.scrub(1.0 - exp(-(1.2 + 9.0 * k) * STEP))

        if (abs(loc.lateral) <= t.edge) return

        val f = loc.frame
        val side = if (loc.lateral >= 0) 1.0 else -1.0
        vehicle.x = f.pos.x + f.right.x * t.edge * side
        vehicle.z = f.pos.z + f.right.z * t.edge * side
        vehicle.scrub(1.0)
    }

    private fun checkProgress() {
        val t = track ?: return
        val n = t.frames.size - 1
        val loc = t.locate(vehicle.x, vehicle.z, trackHint)

        // Update progress first: crossing the finish line wraps the frame index
        // back to zero, so finish() must have the last word on it.
        lapProgress = (loc.index.toDouble() / n).coerceIn(0.0, 1.0)

        if (nextCheckpoint < t.checkpoints.size) {
            val target = t.checkpoints[nextCheckpoint]
            // Compare indices the short way round: the finish line sits at index
            // n, the same place as index 0, so plain subtraction would read the
            // finish as a lap away exactly as you cross it.
            if (abs(Autopilot.wrapIndex(loc.index - target, n)) < CHECKPOINT_RADIUS) {
                nextCheckpoint++
                if (nextCheckpoint >= t.checkpoints.size) finish()
            }
        }
    }

    private fun finish() {
        state = State.FINISHED
        lapProgress = 1.0
        val previous = storage.bestTime(levelIndex)
        if (previous == null || raceTime < previous) {
            storage.setBestTime(levelIndex, raceTime)
            newBest = true
        }
    }

    private fun fail(reason: String) {
        if (state != State.RACING) return
        state = State.FAILED
        failReason = reason
    }

    fun retry() { loadLevel(levelIndex); start() }

    fun nextLevel() { loadLevel(levelIndex + 1); start() }

    fun toMenu() { state = State.MENU }

    /* ------------------------------------------------------------- camera */

    class Camera(
        val eye: Vec3,
        val target: Vec3,
        val fovDegrees: Float,
        /** Camera roll about the view axis, radians. See [viewRoll]. */
        val rollRadians: Float
    )

    /**
     * How far the phone is currently rolled away from its calibrated neutral,
     * in radians. The camera rolls to cancel it.
     *
     * This is what actually keeps the horizon level. The rendered image is
     * fixed to the screen, and the player physically rotates the screen to
     * steer — so an image that is level within its own frame appears tilted by
     * exactly the amount the phone is tilted. Cancelling that rotation in the
     * view is the only way the horizon stays level to the person holding it.
     */
    var viewRoll: Double = 0.0

    private var camX = 0.0
    private var camY = 0.0
    private var camZ = 0.0
    private var camYaw = 0.0
    private var camInitialised = false

    /**
     * The direction the car is actually travelling, as an angle in the
     * renderer's convention.
     *
     * Not the same as which way it is pointing. A car sliding through a corner
     * is going somewhere its nose is not, and a camera bolted to the nose
     * looks off into the scenery at exactly the moment the driver most needs
     * to see where the car is heading.
     *
     * Below walking pace the direction of travel is noise — a car barely
     * rolling has a course made mostly of tyre scrub — so the nose is used
     * instead. Reversing keeps the nose too: swinging the view round to look
     * backwards would be worse than useless.
     */
    private fun travelDirection(): Double {
        val v = vehicle
        if (v.vx < 1.0) return v.yaw
        val s = sin(v.yaw); val c = cos(v.yaw)
        val worldX = v.vx * s + v.vy * c
        val worldZ = v.vx * c - v.vy * s
        if (abs(worldX) < 1e-6 && abs(worldZ) < 1e-6) return v.yaw
        return atan2(worldX, worldZ)
    }

    /**
     * Chase camera.
     *
     * Rolls by [viewRoll] to keep the horizon level in the player's eyes while
     * they rotate the phone to steer.
     */
    fun camera(dt: Double, aspect: Float): Camera {
        val v = vehicle
        // Only a little pull-back with speed: too much and the car shrinks to a
        // dot exactly when it should feel fastest. The speed sensation comes
        // from the field-of-view creep below instead.
        val back = 7.2 + (v.speed * 0.022).coerceIn(0.0, 1.8)
        val height = 2.45 + (v.speed * 0.008).coerceIn(0.0, 0.65)

        // The camera sits behind, and looks along, the direction of travel —
        // eased into rather than snapped to, so a flick of oversteer does not
        // throw the whole view sideways.
        val course = travelDirection()
        if (!camInitialised) {
            camYaw = course
        } else {
            camYaw += wrapPi(course - camYaw) * (1.0 - exp(-CAM_YAW_RATE * dt))
        }

        // Once the car is a wreck it is the tub, not the vehicle, that is
        // where the car is: the vehicle stopped dead against the tree while
        // the tub went cartwheeling past it. Following the vehicle would leave
        // the camera staring at the impact point with the crash happening off
        // to one side of the screen.
        val w = wreck
        val focusX = w?.chassis?.position?.x?.toDouble() ?: v.x
        val focusZ = w?.chassis?.position?.z?.toDouble() ?: v.z

        val wantX = focusX - sin(camYaw) * back
        val wantY = height
        val wantZ = focusZ - cos(camYaw) * back

        if (!camInitialised) {
            camX = wantX; camY = wantY; camZ = wantZ; camInitialised = true
        } else {
            // Exponential follow, tighter the faster the car is going.
            val blend = 1.0 - exp(-(4.5 + (v.speed * 0.06).coerceIn(0.0, 3.0)) * dt)
            camX += (wantX - camX) * blend
            camY += (wantY - camY) * blend
            camZ += (wantZ - camZ) * blend
        }

        // Look ahead of the car so corners open up early.
        val lead = 7.0 + (v.speed * 0.30).coerceIn(0.0, 18.0)

        // Vertical FOV: trim it as the screen gets wider, or a landscape phone
        // gives a fish-eye view.
        val baseFov = (52f - (aspect - 1.6f) * 6f).coerceIn(40f, 56f)
        val fov = baseFov + (v.speed * 0.11).coerceIn(0.0, 8.0).toFloat()

        // Clamp so a wild flick, or a phone turned right over, cannot put the
        // world upside down.
        val roll = viewRoll.coerceIn(-MAX_VIEW_ROLL, MAX_VIEW_ROLL)

        return Camera(
            Vec3(camX, camY, camZ),
            // A wreck is what the player wants to watch, so look straight at
            // it rather than well past it down a road nobody is driving.
            if (w == null) Vec3(v.x + sin(camYaw) * lead, 0.9, v.z + cos(camYaw) * lead)
            else Vec3(focusX, w.chassis.position.y.toDouble().coerceIn(0.4, 3.0), focusZ),
            fov,
            roll.toFloat()
        )
    }

    fun resetCamera() { camInitialised = false }

    /** Shortest way round from one angle to another. */
    private fun wrapPi(a: Double) = atan2(sin(a), cos(a))

    /* ---------------------------------------------------------------- hud */

    val speedKmh: Int get() = (abs(vehicle.speed) * 3.6).toInt()
    val gearLabel: String
        get() = if (vehicle.speed < 0.6 && state != State.RACING) "N" else (vehicle.gear + 1).toString()
    val revFraction: Float
        get() = ((vehicle.rpm - Spec.IDLE_RPM) / (Spec.REDLINE - Spec.IDLE_RPM)).coerceIn(0.0, 1.0).toFloat()
    val fuelFraction: Float
        get() = (vehicle.fuel / max(startFuel, 1e-6)).coerceIn(0.0, 1.0).toFloat()
    val checkpointTotal: Int get() = track?.checkpoints?.size ?: Track.CHECKPOINT_COUNT

    companion object {
        const val STEP = 1.0 / 120.0
        private const val MAX_STEPS = 8
        private const val MAX_FRAME_DELTA = 0.05
        private const val CHECKPOINT_RADIUS = 12

        /** How far the car's body reaches from its centre, for hitting things. */
        private const val CAR_REACH = 1.6

        /** Above this, in m/s, hitting a tree or a rock is the end of it. */
        private const val CRASH_SPEED = 6.0

        /** Roughly nose height: where a car meets a tree. */
        private const val IMPACT_HEIGHT = 0.45

        /** How long a crash gets the screen to itself, seconds. */
        private const val RESULT_DELAY = 3.2

        /** Seconds between one start light coming on and the next. */
        private const val LIGHT_INTERVAL = 0.5
        /** When all five go out together. */
        private const val LIGHTS_OUT = 5 * LIGHT_INTERVAL
        /** When the gantry has faded from the screen. */
        private const val LIGHTS_GONE = LIGHTS_OUT + 0.8

        /** Radians (75 degrees) beyond which the view stops following the phone. */
        private const val MAX_VIEW_ROLL = 1.31

        /** How quickly the view swings round to the direction of travel. */
        private const val CAM_YAW_RATE = 4.0

        fun formatTime(seconds: Double): String {
            val m = (seconds / 60).toInt()
            val s = seconds - m * 60
            return "%d:%05.2f".format(m, s)
        }
    }
}
