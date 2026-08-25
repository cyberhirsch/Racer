package dev.racer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.racer.core.Game
import dev.racer.core.Levels
import dev.racer.core.TiltSteering

private val Red = Color(0xFFFF2800)
private val RedDeep = Color(0xFFC41000)
private val Ink = Color(0xFF0A0C10)
private val Paper = Color(0xFFEEF1F5)
private val Muted = Color(0xFF93A0AE)
private val Panel = Color(0xE60E1118)

/**
 * A counter that advances once per rendered frame.
 *
 * The game state lives outside Compose, so the HUD needs something to tell it
 * to look again. This is driven by Compose's frame clock: `withFrameNanos`
 * suspends until the next frame, so exactly one update happens per frame and
 * late frames simply delay the next one.
 *
 * Posting an update per frame from the render thread instead (runOnUiThread)
 * is what froze the HUD: on a slow device each recomposition takes longer than
 * a frame, the queue grows without bound, and the main thread never catches up
 * — leaving the menu on screen, and taking taps, while a race was underway.
 */
@Composable
private fun rememberFrameTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            tick++
        }
    }
    return tick
}

/**
 * The heads-up display and menus.
 *
 * Everything is read straight off the game, refreshed once per frame.
 */
@Composable
fun Hud(
    game: Game,
    steering: TiltSteering,
    tiltAvailable: Boolean,
    onStart: (Int) -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    onMenu: () -> Unit,
    onRecentre: () -> Unit,
    onInvert: () -> Unit,
    onPedals: (throttle: Boolean, brake: Boolean) -> Unit
) {
    // Read the frame tick here so this composable is what recomposes each
    // frame, leaving the GL surface beside it untouched.
    rememberFrameTick()

    Box(Modifier.fillMaxSize()) {
        when (game.state) {
            Game.State.MENU -> Menu(game, tiltAvailable, onStart)
            Game.State.COUNTDOWN, Game.State.RACING ->
                Racing(game, steering, onRecentre, onInvert, onPedals)
            Game.State.FINISHED -> Result(game, finished = true, onNext = onNext, onMenu = onMenu)
            Game.State.FAILED -> Result(game, finished = false, onNext = onRetry, onMenu = onMenu)
        }
    }
}

/* -------------------------------------------------------------------- HUD */

@Composable
private fun Racing(
    game: Game,
    steering: TiltSteering,
    onRecentre: () -> Unit,
    onInvert: () -> Unit,
    onPedals: (throttle: Boolean, brake: Boolean) -> Unit
) {
    // The pedals live on this Box — the same node that draws the GAS and BRAKE
    // hints — so there is exactly one owner of the gesture. Splitting the
    // visual from the touch target across separate layers makes which one
    // actually receives a press a matter of z-order luck.
    //
    // The HUD's own buttons are children of this Box and are hit-tested first,
    // so CENTRE and INVERT still work.
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val half = size.width / 2f
                        var left = false
                        var right = false
                        for (change in event.changes) {
                            if (!change.pressed) continue
                            if (change.position.x > half) right = true else left = true
                        }
                        onPedals(right, left)
                    }
                }
            }
            .padding(14.dp)
    ) {

        // Pedal hints, so it is obvious which half does what.
        Row(Modifier.fillMaxSize()) {
            PedalHint("BRAKE", Color(0x40FF3C28), Modifier.weight(1f).fillMaxHeight())
            PedalHint("GAS", Color(0x3C3CDC82), Modifier.weight(1f).fillMaxHeight())
        }

        // Top: level, time, checkpoints, progress.
        Row(
            Modifier.align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Chip("LEVEL ${game.levelIndex + 1}", game.config.name)
            Chip("TIME", Game.formatTime(game.raceTime))
            Chip("CP", "${game.nextCheckpoint}/${game.checkpointTotal}")
            Box(
                Modifier.width(120.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(Color(0xB30A0C10))
            ) {
                Box(
                    Modifier.fillMaxWidth(game.lapProgress.toFloat()).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Red, Color(0xFFFF8A5C))))
                )
            }
        }

        // Left: the fuel column — the thing that actually ends the race.
        Column(Modifier.align(Alignment.CenterStart), horizontalAlignment = Alignment.CenterHorizontally) {
            val fuel = game.fuelFraction
            val fuelColor = when {
                fuel < 0.15f -> Color(0xFFFF5A45)
                fuel < 0.35f -> Color(0xFFFFD166)
                else -> Color(0xFF4ADE80)
            }
            Box(
                Modifier.width(26.dp).height(120.dp).clip(RoundedCornerShape(13.dp))
                    .background(Color(0xA60A0C10)).border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(fuel).background(fuelColor))
            }
            Spacer(Modifier.height(5.dp))
            Label("FUEL")
            Text(
                "%.2f kg".format(game.vehicle.fuel),
                color = Paper, fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }

        // Right: speed and gear.
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${game.speedKmh}", color = Paper, fontSize = 44.sp,
                    fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(4.dp))
                Text("km/h", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("GEAR")
                    Text(
                        game.gearLabel, color = Red, fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.width(170.dp).height(7.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB30A0C10))
            ) {
                Box(
                    Modifier.fillMaxWidth(game.revFraction).fillMaxHeight().background(
                        Brush.horizontalGradient(listOf(Color(0xFF3AD17A), Color(0xFFFFD166), Color(0xFFFF3B1F)))
                    )
                )
            }
        }

        // Bottom left: steering indicator and the two calibration buttons.
        Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.size(72.dp, 36.dp).clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
                    .background(Color(0xA60A0C10)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    Modifier.rotate(steering.steer.toFloat() * 90f)
                        .width(3.dp).height(32.dp).background(Red)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallButton("CENTRE", onClick = onRecentre)
                SmallButton("INVERT", active = steering.invert, onClick = onInvert)
            }
        }

        if (game.vehicle.offTrack && game.state == Game.State.RACING) {
            Text(
                "OFF TRACK", color = Color(0xFFFFD166), fontSize = 12.sp,
                letterSpacing = 3.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
            )
        }

        Countdown(game)
    }
}

/**
 * The starting lights.
 *
 * The number used to be a bare white glyph dropped on the middle of the track
 * — over a pale kerb or the sky it was barely there, and it shared the centre
 * of the screen with everything else the HUD draws. It now dims the scene
 * behind it, sits in its own disc, and pops on each new count, so there is no
 * question which second you are on.
 *
 * The scale and fade come from the fractional part of the count rather than an
 * animation: the HUD already recomposes every frame, and driving it from the
 * clock the game itself is using keeps the pop exactly on the beat.
 */
@Composable
private fun Countdown(game: Game) {
    val n = game.countdownLabel ?: return
    val go = n == 0

    // How far through the current second we are, 0 at the moment it changes.
    val into = (1.0 - (game.countdown - kotlin.math.floor(game.countdown))).toFloat()
    val pop = 1f + 0.35f * (1f - (into * 3.2f).coerceIn(0f, 1f))
    val fade = (1f - (into - 0.75f) * 3f).coerceIn(0.25f, 1f)

    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .scale(pop)
                .alpha(fade)
                .size(if (go) 200.dp else 140.dp, 140.dp)
                .clip(RoundedCornerShape(70.dp))
                .background(if (go) Red else Color(0xCC0A0C10))
                .border(3.dp, if (go) Color.White else Red, RoundedCornerShape(70.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (go) "GO!" else "$n",
                color = Color.White,
                fontSize = if (go) 56.sp else 84.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = if (go) 4.sp else 0.sp
            )
        }
    }
}

@Composable
private fun PedalHint(text: String, tint: Color, modifier: Modifier) {
    Box(modifier.background(Brush.verticalGradient(listOf(Color.Transparent, tint))),
        contentAlignment = Alignment.BottomCenter) {
        Text(text, color = Color(0x66FFFFFF), fontSize = 10.sp, letterSpacing = 3.sp,
            modifier = Modifier.padding(bottom = 4.dp))
    }
}

/* ------------------------------------------------------------------ menus */

@Composable
private fun Menu(game: Game, tiltAvailable: Boolean, onStart: (Int) -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Row(
            Modifier.padding(20.dp).widthIn(max = 820.dp).verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(Red))
                    Spacer(Modifier.width(8.dp))
                    Text("RACER", color = Paper, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text("F1", color = Red, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text("Your phone is the steering wheel.", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))

                HowTo("⟲", "Steer", "Hold the phone in landscape and rotate it like a wheel. The horizon stays level.")
                HowTo("▶", "Gas", "Touch and hold anywhere on the right half.")
                HowTo("◀", "Brake", "Touch and hold anywhere on the left half.")
                HowTo("⛽", "Fuel is the clock",
                    "Reach the finish before the tank runs dry. Full throttle everywhere will not make it.")

                if (!tiltAvailable) {
                    Text(
                        "No motion sensor found on this device — steering will not work.",
                        color = Color(0xFFFFD166), fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Label("SELECT TRACK")
                Spacer(Modifier.height(8.dp))
                Levels.BUILT_IN.forEachIndexed { i, level ->
                    LevelRow(i, level.name, game.bestTime(i), i == game.levelIndex) { onStart(i) }
                    Spacer(Modifier.height(5.dp))
                }
                Spacer(Modifier.height(10.dp))
                CtaButton("START ENGINE") { onStart(game.levelIndex) }
            }
        }
    }
}

@Composable
private fun Result(game: Game, finished: Boolean, onNext: () -> Unit, onMenu: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xDB06070A)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp).clip(RoundedCornerShape(16.dp))
                .background(Panel).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (finished) "TRACK COMPLETE" else (game.failReason ?: "RETIRED"),
                color = if (finished) Color(0xFF4ADE80) else Color(0xFFFF5A45),
                fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
            )
            Spacer(Modifier.height(14.dp))

            if (finished) {
                Stat("Time", Game.formatTime(game.raceTime) + if (game.newBest) "  NEW BEST" else "")
                Stat("Fuel left", "%.2f kg".format(game.vehicle.fuel))
                Stat("Top speed", "${(game.topSpeed * 3.6).toInt()} km/h")
            } else {
                Stat("Progress", "${(game.lapProgress * 100).toInt()}%")
                Stat("Checkpoints", "${game.nextCheckpoint}/${game.checkpointTotal}")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Lift and coast — part throttle burns far less fuel.",
                    color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    SmallButton("TRACKS", onClick = onMenu, wide = true)
                }
                Box(Modifier.weight(1.4f)) {
                    CtaButton(if (finished) "NEXT LEVEL" else "RETRY", onClick = onNext)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ parts */

@Composable
private fun Chip(label: String, value: String) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x9E0A0C10))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Label(label)
        Spacer(Modifier.width(6.dp))
        Text(value, color = Paper, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = Paper, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun HowTo(icon: String, title: String, body: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Color(0x24FF2800)),
            contentAlignment = Alignment.Center
        ) { Text(icon, color = Red, fontSize = 13.sp) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = Paper, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFFCCD3DB), fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun LevelRow(index: Int, name: String, best: Double?, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(if (active) Color(0x21FF2800) else Color(0x09FFFFFF))
            .border(1.dp, if (active) Red else Color(0x12FFFFFF), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${index + 1}", color = Red, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(10.dp))
        Text(name, color = Paper, fontSize = 13.sp, modifier = Modifier.weight(1f))
        // Difficulty pips.
        Text(
            "▰".repeat(index + 1) + "▱".repeat(Levels.BUILT_IN.size - index - 1),
            color = Red, fontSize = 9.sp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            best?.let { Game.formatTime(it) } ?: "—",
            color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun CtaButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
            .background(Brush.verticalGradient(listOf(Red, RedDeep)))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
    }
}

@Composable
private fun SmallButton(text: String, active: Boolean = false, wide: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.let { if (wide) it.fillMaxWidth() else it }
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) RedDeep else Color(0xA60A0C10))
            .border(1.dp, if (active) Red else Color(0x24FFFFFF), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (active) Color.White else Color(0xFFC4CCD5), fontSize = 10.sp, letterSpacing = 2.sp)
    }
}
