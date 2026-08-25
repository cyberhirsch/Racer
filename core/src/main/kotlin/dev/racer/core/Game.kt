package dev.racer.core

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

    enum class State { MENU, COUNTDOWN, RACING, FINISHED, FAILED }

    var state = State.MENU
        private set
    var levelIndex = 0
        private set
    var track: Track? = null
        private set
    val vehicle = Vehicle()

    var raceTime = 0.0
        private set
    var countdown = 0.0
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

    fun startCountdown(seconds: Double = 3.999) {
        if (track == null) loadLevel(levelIndex)
        countdown = seconds
        state = State.COUNTDOWN
    }

    /** Whole number to show during the countdown, or null once racing. */
    val countdownLabel: Int?
        get() = if (state == State.COUNTDOWN) max(0, kotlin.math.ceil(countdown - 1).toInt()) else null

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
            State.COUNTDOWN -> {
                countdown -= dt
                if (countdown <= 0) state = State.RACING
            }
            State.RACING -> {
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

    fun retry() { loadLevel(levelIndex); startCountdown() }

    fun nextLevel() { loadLevel(levelIndex + 1); startCountdown() }

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
    private var camInitialised = false

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
        val wantX = v.x - sin(v.yaw) * back
        val wantY = height
        val wantZ = v.z - cos(v.yaw) * back

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
            Vec3(v.x + sin(v.yaw) * lead, 0.9, v.z + cos(v.yaw) * lead),
            fov,
            roll.toFloat()
        )
    }

    fun resetCamera() { camInitialised = false }

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

        /** Radians (75 degrees) beyond which the view stops following the phone. */
        private const val MAX_VIEW_ROLL = 1.31

        fun formatTime(seconds: Double): String {
            val m = (seconds / 60).toInt()
            val s = seconds - m * 60
            return "%d:%05.2f".format(m, s)
        }
    }
}
