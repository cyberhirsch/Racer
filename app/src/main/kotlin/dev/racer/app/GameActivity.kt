package dev.racer.app

import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.racer.core.Game
import dev.racer.core.Input
import dev.racer.core.Spec
import dev.racer.core.TiltSteering

/**
 * The whole app: a GLSurfaceView drawing the world, with a Compose HUD on top.
 *
 * Physics and rendering are driven from the GL thread's frame callback; the HUD
 * reads the resulting state each recomposition.
 *
 * All touch input is handled in Compose. The GLSurfaceView is for drawing only
 * — an embedded Android View that consumes touches sits inside the Compose
 * hierarchy and swallows gestures before the HUD's buttons ever see them, which
 * makes the menus unusable.
 */
class GameActivity : ComponentActivity() {

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: GlRenderer
    private lateinit var tiltSensor: TiltSensor
    private val engine = EngineSound()

    private val steering = TiltSteering()
    private lateinit var game: Game

    /** What the two controls are asking for: the gas slider is analogue. */
    @Volatile private var throttleWanted = 0.0
    @Volatile private var brakeDown = false
    private var throttle = 0.0
    private var brake = 0.0

    /**
     * Tells the HUD the game has moved on.
     *
     * Pushed from the physics thread rather than pulled from Compose's frame
     * clock, which was tried twice and left the HUD drawing the numbers it
     * started with for a whole race.
     *
     * Two things keep this from repeating the original mistake, where a post
     * per frame grew a queue the main thread could never drain: it is capped
     * at twenty a second, and a new post is not made while one is still
     * waiting, so a slow device simply gets fewer updates instead of a
     * backlog.
     */
    private val uiTick = mutableIntStateOf(0)
    private val tickPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var tickTimer = 0.0

    private var lastLoggedState: Game.State? = null
    private var logTimer = 0.0

    /** Decaying blip that revs the engine on each beat of the countdown. */
    private var revBlip = 0.0
    private var lastCount: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        game = Game(Prefs(this))
        game.loadLevel(0)

        tiltSensor = TiltSensor(this, steering)
        renderer = GlRenderer(game).apply {
            onFrame = { dt -> advance(dt) }
        }

        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())

                Hud(
                    game = game,
                    tick = uiTick,
                    steering = steering,
                    tiltAvailable = tiltSensor.available,
                    onStart = { level -> beginRace { game.loadLevel(level); game.startCountdown() } },
                    onRetry = { beginRace { game.retry() } },
                    onNext = { beginRace { game.nextLevel() } },
                    onMenu = { game.toMenu() },
                    onRecentre = { steering.calibrate() },
                    onInvert = { steering.invert = !steering.invert },
                    onPedals = { gas, braking ->
                        throttleWanted = gas.toDouble()
                        brakeDown = braking
                    }
                )
            }
        }
        goFullScreen()
    }

    /** Shared setup for starting, retrying or advancing a level. */
    private fun beginRace(load: () -> Unit) {
        load()
        game.resetCamera()
        game.track?.let { renderer.setTrack(it) }
        steering.calibrate()
        throttleWanted = 0.0
        brakeDown = false
        throttle = 0.0
        brake = 0.0
        Log.i(TAG, "level ${game.levelIndex + 1} (${game.config.name}) starting")
    }

    private fun advance(dt: Double) {
        // Pedals ramp rather than snap: a real driver rolls onto the throttle.
        // The slider says how much, so the ramp only has to cover the travel
        // between where the pedal is and where the thumb has asked for.
        val rate = 6.5 * dt
        throttle += (throttleWanted - throttle).coerceIn(-rate * 2, rate)
        throttle = throttle.coerceIn(0.0, 1.0)
        brake = (brake + if (brakeDown) rate * 2 else -rate * 2.5).coerceIn(0.0, 1.0)

        val steer = steering.update(dt)
        // Cancel the phone's rotation in the view so the horizon stays level.
        game.viewRoll = steering.viewRoll
        val wasRacing = game.state == Game.State.RACING
        game.update(dt, Input(throttle, brake, steer))

        updateEngineSound(dt)

        if (wasRacing && game.state == Game.State.FINISHED) vibrate(120)

        pokeHud(dt)
        logProgress(dt)
    }

    private fun pokeHud(dt: Double) {
        tickTimer += dt
        if (tickTimer < HUD_INTERVAL) return
        tickTimer = 0.0
        if (!tickPending.compareAndSet(false, true)) return
        runOnUiThread {
            tickPending.set(false)
            uiTick.intValue++
        }
    }

    /**
     * Feed the engine synth.
     *
     * While racing it simply follows the crank. On the grid there is nothing
     * for it to follow — the car is stationary and the physics is not running
     * — so each beat of the countdown throws a blip of throttle at it, which
     * decays like a real one: the driver sits there revving it while the
     * lights come down.
     */
    private fun updateEngineSound(dt: Double) {
        val counting = game.state == Game.State.COUNTDOWN
        if (counting) {
            val count = game.countdownLabel
            if (count != null && count != lastCount) {
                lastCount = count
                // The last beat is 'go': hold the revs rather than let them drop.
                revBlip = if (count == 0) 1.0 else 0.85
            }
            revBlip = (revBlip - dt * 1.6).coerceAtLeast(0.0)
            engine.rpm = Spec.IDLE_RPM + (Spec.REDLINE - Spec.IDLE_RPM) * 0.72 * revBlip
            engine.throttle = revBlip
        } else {
            lastCount = null
            revBlip = 0.0
            engine.rpm = game.vehicle.rpm
            engine.throttle = throttle
        }
        engine.running = counting || game.state == Game.State.RACING
    }

    /**
     * Log state changes and a periodic heartbeat.
     *
     * This is what the CI smoke test asserts against: it proves the whole chain
     * — touch, physics, rendering — is actually working, which a screenshot
     * alone cannot show.
     */
    private fun logProgress(dt: Double) {
        if (game.state != lastLoggedState) {
            lastLoggedState = game.state
            Log.i(TAG, "state -> ${game.state}")
            logTimer = 0.0
        }
        if (game.state == Game.State.RACING) {
            logTimer += dt
            if (logTimer >= 1.0) {
                logTimer = 0.0
                Log.i(
                    TAG,
                    "racing speed=${game.speedKmh}kmh gear=${game.gearLabel} " +
                        "fuel=${"%.2f".format(game.vehicle.fuel)}kg " +
                        "cp=${game.nextCheckpoint}/${game.checkpointTotal} " +
                        "throttle=${"%.2f".format(throttle)} steer=${"%.2f".format(steering.steer)} " +
                        "phoneRoll=${"%.1f".format(Math.toDegrees(steering.rollFromNeutral))} " +
                        "viewRoll=${"%.1f".format(Math.toDegrees(steering.viewRoll))}"
                )
            }
        }
    }

    private fun vibrate(ms: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
    }

    private fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
        tiltSensor.start()
        engine.start()
    }

    override fun onPause() {
        super.onPause()
        surface.onPause()
        tiltSensor.stop()
        engine.running = false
        engine.stop()
        throttleWanted = 0.0
        brakeDown = false
    }

    private companion object {
        const val TAG = "Racer"

        /** Twenty HUD updates a second: past that nobody reads the digits. */
        const val HUD_INTERVAL = 1.0 / 20.0
    }
}
