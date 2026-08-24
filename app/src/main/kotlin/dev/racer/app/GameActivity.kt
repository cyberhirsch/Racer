package dev.racer.app

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.racer.core.Game
import dev.racer.core.Input
import dev.racer.core.TiltSteering

/**
 * The whole app: a GLSurfaceView drawing the world, with a Compose HUD on top.
 *
 * The physics and rendering are driven from the GL thread's frame callback, and
 * the HUD reads the resulting state each recomposition.
 */
class GameActivity : ComponentActivity() {

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: GlRenderer
    private lateinit var tiltSensor: TiltSensor

    private val steering = TiltSteering()
    private lateinit var game: Game

    /** Screen halves: right is the throttle, left is the brake. */
    private var throttleDown = false
    private var brakeDown = false
    private var throttle = 0.0
    private var brake = 0.0

    /** Mirrors of the game state, for Compose to observe. */
    private var uiTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        game = Game(Prefs(this))
        game.loadLevel(0)

        tiltSensor = TiltSensor(this, steering)
        renderer = GlRenderer(game).apply {
            onFrame = { dt ->
                advance(dt)
                // The HUD reads the game directly; this just asks Compose to
                // look again now that the frame has advanced.
                runOnUiThread { uiTick++ }
            }
        }

        surface = object : GLSurfaceView(this) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                handleTouch(event)
                return true
            }
        }.apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
                Hud(
                    game = game,
                    steering = steering,
                    tick = uiTick,
                    tiltAvailable = tiltSensor.available,
                    onStart = { level ->
                        game.loadLevel(level)
                        game.resetCamera()
                        renderer.setTrack(game.track!!)
                        steering.calibrate()
                        game.startCountdown()
                    },
                    onRetry = {
                        game.retry()
                        game.resetCamera()
                        renderer.setTrack(game.track!!)
                        steering.calibrate()
                    },
                    onNext = {
                        game.nextLevel()
                        game.resetCamera()
                        renderer.setTrack(game.track!!)
                        steering.calibrate()
                    },
                    onMenu = { game.toMenu() },
                    onRecentre = { steering.calibrate() },
                    onInvert = { steering.invert = !steering.invert }
                )
            }
        }
        goFullScreen()
    }

    private fun advance(dt: Double) {
        // Pedals ramp rather than snap: a real driver rolls onto the throttle.
        val rate = 6.5 * dt
        throttle = (throttle + if (throttleDown) rate else -rate * 2).coerceIn(0.0, 1.0)
        brake = (brake + if (brakeDown) rate * 2 else -rate * 2.5).coerceIn(0.0, 1.0)

        val steer = steering.update(dt)
        val wasRacing = game.state == Game.State.RACING
        game.update(dt, Input(throttle, brake, steer))

        if (wasRacing && game.lastImpact > 6.0) vibrate(40)
        if (wasRacing && game.state == Game.State.FINISHED) vibrate(120)
    }

    private fun handleTouch(event: MotionEvent) {
        // Multi-touch aware, so braking and accelerating together works.
        var left = false
        var right = false
        val action = event.actionMasked
        for (i in 0 until event.pointerCount) {
            val lifted = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                    && i == event.actionIndex
            if (lifted) continue
            if (event.getX(i) > surface.width / 2f) right = true else left = true
        }
        if (action == MotionEvent.ACTION_CANCEL) { left = false; right = false }
        throttleDown = right
        brakeDown = left
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
    }

    override fun onPause() {
        super.onPause()
        surface.onPause()
        tiltSensor.stop()
        throttleDown = false
        brakeDown = false
    }
}
