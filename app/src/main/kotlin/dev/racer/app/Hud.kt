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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * The heads-up display and menus.
 *
 * Everything is read straight off the game, refreshed once per frame.
 */
@Composable
fun Hud(
    game: Game,
    tick: MutableIntState,
    steering: TiltSteering,
    tiltAvailable: Boolean,
    onStart: (Int) -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    onMenu: () -> Unit,
    onRecentre: () -> Unit,
    onInvert: () -> Unit,
    onPedals: (throttle: Float, brake: Boolean) -> Unit
) {
    // Read the tick here, so it is this composable that Compose marks for
    // recomposition when the game moves on — and pass it down, so the racing
    // HUD cannot be skipped either. The GL surface beside it has no changing
    // input and is left alone.
    //
    // The tick is pushed by the game thread rather than pulled from Compose's
    // frame clock. Two attempts at pulling it have now failed on a real
    // device: the first read the state in the wrong scope, and the second,
    // which read it here, still left the HUD drawing its opening numbers all
    // race. Something stops that clock; what it is does not matter as much as
    // this not depending on it.
    val frame = tick.intValue

    Box(Modifier.fillMaxSize()) {
        when (game.state) {
            Game.State.MENU -> Menu(game, tiltAvailable, onStart)
            Game.State.RACING ->
                Racing(game, frame, steering, onRecentre, onInvert, onPedals, onRetry, onMenu)
            Game.State.FINISHED -> Result(game, finished = true, onNext = onNext, onMenu = onMenu)
            Game.State.FAILED -> Result(game, finished = false, onNext = onRetry, onMenu = onMenu)
        }
    }
}

/* -------------------------------------------------------------------- HUD */

@Composable
private fun Racing(
    game: Game,
    frame: Int,
    steering: TiltSteering,
    onRecentre: () -> Unit,
    onInvert: () -> Unit,
    onPedals: (throttle: Float, brake: Boolean) -> Unit,
    onRestart: () -> Unit,
    onMenu: () -> Unit
) {
    // `frame` exists so that Compose cannot skip this composable: the game
    // state it draws lives outside Compose and changes without notice, so the
    // only thing that can force a redraw is a parameter that differs each
    // time. It is also reported below, which is how a HUD that never
    // recomposed can be told from one that recomposed with stale numbers.
    //
    // And once a second, the HUD says what it is drawing. Nothing else can:
    // the accessibility tree that a screen dump reads lags behind the display,
    // so it reports a stale HUD and a live one identically. This line comes
    // from the composition itself, so if it stops moving while the game log
    // does not, the display really has frozen.
    reportWhatIsDrawn(game, frame)
    Box(Modifier.fillMaxSize().padding(14.dp)) {

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

        // Top left: the steering needle and the two calibration buttons. They
        // used to sit in the bottom corner, which is now the brake's.
        //
        // Clear of the top edge, like the two on the right. In immersive mode
        // that strip belongs to the system and a press in it is taken away:
        // CENTRE sat at y=39 and every tap on it was swallowed, which cost a
        // round of tilt debugging where the wheel was never actually centred.
        Row(
            Modifier.align(Alignment.TopStart).padding(top = 34.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier.size(72.dp, 36.dp).clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                    .background(Color(0xA60A0C10)),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    Modifier.rotate(steering.steer.toFloat() * 90f)
                        .width(3.dp).height(32.dp).background(Red)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallButton(
                    "CENTRE",
                    active = steering.neutral != 0.0,
                    modifier = Modifier.reportRect("CENTRE"),
                    onClick = onRecentre
                )
                SmallButton("INVERT", active = steering.invert, onClick = onInvert)
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
                Modifier.width(26.dp).height(110.dp).clip(RoundedCornerShape(13.dp))
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

        // Middle bottom: speed, gear and the rev bar, clear of both thumbs.
        Column(
            Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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

        // The two controls, each with its own gesture and its own corner. A
        // whole-screen-half split meant a thumb resting anywhere counted, and
        // gave no way to ask for part throttle. Each reports only its own
        // change, so the last value of the other one is kept here.
        val pedals = remember { Pedals() }
        BrakeButton(Modifier.align(Alignment.BottomStart)) {
            pedals.brake = it; onPedals(pedals.gas, it)
        }
        GasSlider(Modifier.align(Alignment.BottomEnd)) {
            pedals.gas = it; onPedals(it, pedals.brake)
        }

        if (game.vehicle.offTrack && game.state == Game.State.RACING) {
            // Two different messages, because they mean different things: one
            // is costing you time, the other means the ground is about to run
            // out and the car will bog down whatever you do.
            val lost = game.beyondDeepGrass
            Text(
                if (lost) "TURN BACK" else "OFF TRACK",
                color = if (lost) Color(0xFFFF5A45) else Color(0xFFFFD166),
                fontSize = if (lost) 14.sp else 12.sp,
                fontWeight = if (lost) FontWeight.Bold else FontWeight.Normal,
                letterSpacing = 3.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
            )
        }

        // Out at any time, without waiting for the fuel to run dry: a race you
        // have already ruined is not worth sitting through, and there was no
        // way out of one until now.
        //
        // Held down from the very top edge, which in immersive mode belongs to
        // the system: a swipe there brings the bars back. That is a reason to
        // keep clear of it, but it was not why MENU looked dead on the
        // emulator — the test was reading a bounds-less semantics node and
        // tapping the corner of the screen instead of the button.
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SmallButton("RESTART", modifier = Modifier.reportRect("RESTART"), onClick = onRestart)
            SmallButton("MENU", modifier = Modifier.reportRect("MENU"), onClick = onMenu)
        }

        StartLights(game, Modifier.align(Alignment.TopCenter))
    }
}

/**
 * Log the numbers the HUD is currently drawing, about once a second.
 *
 * Called from composition, so it only happens when the HUD actually
 * recomposes — which is the thing being reported.
 */
@Composable
private fun reportWhatIsDrawn(game: Game, tick: Int) {
    val last = remember { longArrayOf(0L) }
    val now = System.nanoTime()
    if (now - last[0] > 1_000_000_000L) {
        last[0] = now
        android.util.Log.i(
            "Racer",
            "hud speed=${game.speedKmh}kmh fuel=${"%.2f".format(game.vehicle.fuel)}kg " +
                "lights=${game.startLightsLit} tick=$tick"
        )
    }
}

/**
 * Log where a control has actually been placed on screen.
 *
 * The emulator has to press these buttons to prove they work, and the
 * accessibility tree it would normally read them from is not reliable here:
 * RESTART came back with real bounds while MENU, beside it in the same row and
 * the same kind of node, came back as [0,0][0,0] — the same bounds-less report
 * that made the gas slider look unpressable. Layout knows exactly where each
 * control is, so it says so.
 */
private fun Modifier.reportRect(name: String): Modifier = this.onGloballyPositioned { c ->
    val at = c.positionInWindow()
    android.util.Log.i(
        "Racer",
        "button $name rect=${at.x.toInt()},${at.y.toInt()}," +
            "${(at.x + c.size.width).toInt()},${(at.y + c.size.height).toInt()}"
    )
}

/** The last reading from each control, so either can report on its own. */
private class Pedals {
    var gas = 0f
    var brake = false
}

/**
 * The throttle: a capsule you slide a thumb up.
 *
 * How far up decides how much, which a screen half could never express — and
 * with the tank as the clock, feathering it is the whole game. It springs back
 * to nothing when released, because a throttle that stayed where you left it
 * would keep the car accelerating into a corner after you had let go.
 */
@Composable
private fun GasSlider(modifier: Modifier, onChange: (Float) -> Unit) {
    var level by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .size(76.dp, 210.dp)
            // Named so the emulator smoke test can find the control and press
            // a known point on it, rather than guessing at screen fractions.
            .semantics { contentDescription = "GAS SLIDER" }
            .clip(RoundedCornerShape(38.dp))
            .background(Color(0x9E0A0C10))
            .border(2.dp, Color(0x3C3CDC82), RoundedCornerShape(38.dp))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.firstOrNull { it.pressed }
                        level = if (down == null) 0f
                        else (1f - down.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                        onChange(level)
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // The fill is the reading: how high it stands is how much you asked for.
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(level.coerceAtLeast(0.02f))
                .background(
                    Brush.verticalGradient(listOf(Color(0xCC5CE39A), Color(0x803CDC82)))
                )
        )
        Text(
            "GAS", color = Color(0xCCFFFFFF), fontSize = 10.sp, letterSpacing = 3.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

/** The brake: a small circle, all or nothing, under the left thumb. */
@Composable
private fun BrakeButton(modifier: Modifier, onChange: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(96.dp)
            .semantics { contentDescription = "BRAKE BUTTON" }
            .clip(CircleShape)
            .background(if (down) Color(0xCCFF3C28) else Color(0x9E0A0C10))
            .border(2.dp, Color(0x66FF3C28), CircleShape)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        down = event.changes.any { it.pressed }
                        onChange(down)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "BRAKE", color = Color(0xCCFFFFFF), fontSize = 10.sp, letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * The five-lamp starting gantry.
 *
 * Pure decoration: the car is live from the first frame, so the lights come on
 * one by one and then go out, fading away rather than gating the race.
 */
@Composable
private fun StartLights(game: Game, modifier: Modifier) {
    if (!game.startLightsVisible) return
    val lit = game.startLightsLit
    val fade = game.startLightsFade
    Row(
        modifier
            .padding(top = 46.dp)
            .alpha(fade)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC07090D))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        repeat(5) { i ->
            val on = i < lit
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (on) Color(0xFFFF2010) else Color(0xFF241014))
                    .border(1.dp, if (on) Color(0x66FF6A50) else Color(0x1AFFFFFF), CircleShape)
            )
        }
    }
}


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

                HowTo("⟲", "Steer", "Hold the phone in landscape and rotate it like a wheel. " +
                    "Straight ahead is level, and the horizon stays level with it.")
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
private fun SmallButton(
    text: String,
    active: Boolean = false,
    wide: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier.let { if (wide) it.fillMaxWidth() else it }
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
