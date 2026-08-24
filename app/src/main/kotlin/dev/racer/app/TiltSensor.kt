package dev.racer.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import dev.racer.core.TiltSteering

/**
 * Feeds the device's gravity vector into [TiltSteering].
 *
 * Prefers TYPE_GRAVITY, which is already filtered; falls back to the raw
 * accelerometer with a low-pass filter on devices that do not provide it.
 * The maths itself lives in :core so it can be unit-tested.
 */
class TiltSensor(context: Context, private val steering: TiltSteering) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** True when this device can actually drive the steering. */
    val available: Boolean get() = gravity != null || accelerometer != null

    // Low-pass state, only used on the accelerometer fallback path.
    private var filteredX = 0f
    private var filteredY = 0f
    private var hasFiltered = false

    fun start() {
        val sensor = gravity ?: accelerometer ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        var x = event.values[0]
        var y = event.values[1]

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Separate gravity from movement: the steering only cares about
            // which way is down, not about the car's bumps and the player's hands.
            val a = 0.15f
            if (!hasFiltered) { filteredX = x; filteredY = y; hasFiltered = true }
            filteredX += (x - filteredX) * a
            filteredY += (y - filteredY) * a
            x = filteredX
            y = filteredY
        }

        steering.onGravity(x.toDouble(), y.toDouble(), displayRotationDegrees())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * How far the display is rotated from the device's natural orientation.
     * Sensor axes are fixed to the hardware, so without this the steering would
     * be sideways in landscape.
     */
    private fun displayRotationDegrees(): Int {
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
