package dev.racer.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import kotlin.math.PI
import kotlin.math.sin

/**
 * The engine, synthesised.
 *
 * There is no recording to loop: a sample pitched up and down gives away the
 * trick the moment it is stretched over a range as wide as 4,000 to 14,500
 * rpm, and a set of samples crossfaded together needs assets this project does
 * not have. Building the note instead means the pitch is exactly the engine's
 * and never drifts from what the car is doing.
 *
 * A V6 fires three times per crank revolution, so the note sits at rpm/20 Hz —
 * about 200 Hz at idle, 725 Hz on the limiter. Harmonics above it carry most
 * of the character, and they are what open up under load: a coasting engine is
 * nearly a sine, a screaming one is closer to a saw. Underneath sits a
 * half-order rumble, and over the top a breath of induction noise that grows
 * with throttle.
 *
 * All of it is generated on its own thread and streamed, so a stalled frame
 * cannot make the engine stutter.
 */
class EngineSound {

    /** Set from the game each frame. */
    @Volatile var rpm = IDLE
    @Volatile var throttle = 0.0
    /** False on the menus and between races: the engine fades out rather than cutting. */
    @Volatile var running = false

    private var thread: Thread? = null
    @Volatile private var draining = false

    @Synchronized fun start() {
        draining = false
        thread?.let { if (it.isAlive) return }
        thread = Thread({ run() }, "EngineSound").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Ask the engine to wind down and release the audio device.
     *
     * It is not joined: cutting the samples off mid-cycle is an audible click,
     * and waiting out the fade would block whichever lifecycle callback asked
     * for it. The thread notices the request, fades, and exits by itself; a
     * [start] that arrives before it has gone simply cancels the fade.
     */
    fun stop() {
        running = false
        draining = true
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        val minBytes = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) return
        // Three buffers' headroom: enough that a scheduling hiccup does not
        // underrun, short enough that the pitch still tracks the throttle.
        val bufferBytes = maxOf(minBytes, BLOCK * 2 * 3)

        val track = try {
            build(bufferBytes)
        } catch (e: Exception) {
            android.util.Log.w("Racer", "no audio: $e")
            return
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); return }

        track.play()
        android.util.Log.i("Racer", "engine audio started ($RATE Hz, $bufferBytes byte buffer)")
        val block = ShortArray(BLOCK)

        // Smoothed copies of the inputs: stepping them per sample is what
        // keeps a gear change from arriving as a click.
        var curRpm = IDLE
        var curThrottle = 0.0
        var curGain = 0.0

        var phase = 0.0        // firing order
        var subPhase = 0.0     // half-order rumble
        var noise = 0.0
        var rand = 0x2545F491L

        while (true) {
            if (draining && curGain < 0.002) break
            val targetRpm = rpm.coerceIn(IDLE * 0.7, REDLINE)
            val targetThrottle = throttle.coerceIn(0.0, 1.0)
            val targetGain = if (running) 1.0 else 0.0

            for (i in 0 until BLOCK) {
                // Per-sample glides. Pitch follows quickly, volume slowly, so
                // a blip is heard as a blip and a race start is not a thump.
                curRpm += (targetRpm - curRpm) * RPM_GLIDE
                curThrottle += (targetThrottle - curThrottle) * THROTTLE_GLIDE
                // Fading out is quicker than fading in: a race start should
                // swell, but a phone going to sleep should go quiet at once.
                curGain += (targetGain - curGain) *
                    (if (targetGain > curGain) GAIN_RISE else GAIN_FALL)

                val fire = curRpm / 20.0                     // V6: three fires a rev
                phase += fire / RATE
                subPhase += fire / (2.0 * RATE)
                if (phase >= 1.0) phase -= 1.0
                if (subPhase >= 1.0) subPhase -= 1.0

                // Brightness: how far up the harmonic series the note reaches.
                // Off throttle it collapses towards a hum; on it, it howls.
                val load = 0.28 + 0.72 * curThrottle
                val rev = ((curRpm - IDLE) / (REDLINE - IDLE)).coerceIn(0.0, 1.0)
                val bright = load * (0.55 + 0.45 * rev)

                val t = phase * TWO_PI
                var s = sin(t) * 0.55
                s += sin(t * 2) * 0.42 * bright
                s += sin(t * 3) * 0.30 * bright * bright
                s += sin(t * 4) * 0.20 * bright * bright * bright
                // A slightly detuned fifth harmonic: an exact series sounds
                // like an organ, and an engine never does.
                s += sin(t * 5.02) * 0.12 * bright * bright

                s += sin(subPhase * TWO_PI) * 0.30 * (1.0 - rev * 0.6)

                // Induction and exhaust hiss, low-passed so it is breath and
                // not static.
                rand = rand * 6364136223846793005L + 1442695040888963407L
                val white = (rand shr 40).toInt() / 8_388_608.0
                noise += (white - noise) * 0.35
                s += noise * 0.16 * load * (0.3 + 0.7 * rev)

                // Soft clip: an F1 engine is not a clean waveform, and this
                // keeps the peaks from wrapping when the harmonics line up.
                val shaped = tanhish(s * (0.85 + 0.55 * load))

                val volume = curGain * (0.30 + 0.55 * load) * MASTER
                block[i] = (shaped * volume * Short.MAX_VALUE).toInt()
                    .coerceIn(-32768, 32767).toShort()
            }

            track.write(block, 0, BLOCK)
        }

        // The loop only leaves once the gain has faded, so the tail is already
        // near silence; a zeroed block settles it exactly.
        for (i in 0 until BLOCK) block[i] = 0
        track.write(block, 0, BLOCK)
        track.stop()
        track.release()
    }

    private fun build(bufferBytes: Int): AudioTrack =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes, AudioTrack.MODE_STREAM
            )
        }

    /**
     * Cheap saturating curve; tanh itself is far too slow per sample.
     *
     * Cubic below one, flat above, which meet at the same value — a soft clip
     * with a step in it is just a different, worse distortion.
     */
    private fun tanhish(x: Double): Double =
        if (x > 1.0) TWO_THIRDS
        else if (x < -1.0) -TWO_THIRDS
        else x - x * x * x / 3.0

    private companion object {
        const val RATE = 44_100
        const val BLOCK = 1024
        const val TWO_PI = 2.0 * PI
        const val IDLE = 4_000.0
        const val REDLINE = 14_500.0
        const val MASTER = 0.55
        const val TWO_THIRDS = 2.0 / 3.0
        const val RPM_GLIDE = 0.0012
        const val THROTTLE_GLIDE = 0.0006
        const val GAIN_RISE = 0.00008
        const val GAIN_FALL = 0.00060
    }
}
