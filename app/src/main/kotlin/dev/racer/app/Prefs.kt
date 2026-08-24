package dev.racer.app

import android.content.Context
import dev.racer.core.Game

/** Best times, kept in SharedPreferences. */
class Prefs(context: Context) : Game.Storage {
    private val prefs = context.getSharedPreferences("racer", Context.MODE_PRIVATE)

    override fun bestTime(level: Int): Double? {
        val v = prefs.getFloat(key(level), -1f)
        return if (v < 0) null else v.toDouble()
    }

    override fun setBestTime(level: Int, seconds: Double) {
        prefs.edit().putFloat(key(level), seconds.toFloat()).apply()
    }

    private fun key(level: Int) = "best_$level"
}
