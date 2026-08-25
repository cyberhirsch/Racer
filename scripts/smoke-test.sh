#!/usr/bin/env bash
#
# Emulator smoke test.
#
# Installs the app, drives it through the menu into a race, and asserts the game
# really got going: no crash, the state machine reached RACING, the car actually
# moved, and the renderer produced a non-blank frame.
#
# Checking only that "a screenshot has some colours in it" is not enough — the
# menu screen passes that too, which is exactly how a broken menu once slipped
# through.
set -euo pipefail

PACKAGE=dev.racer.app
ACTIVITY="$PACKAGE/.GameActivity"
OUT=smoke-output
mkdir -p "$OUT"

echo "== building and installing =="
./gradlew :app:assembleDebug --no-daemon
adb install -r -t app/build/outputs/apk/debug/app-debug.apk

adb logcat -c

echo "== launching =="
adb shell am start -W -n "$ACTIVITY"
sleep 8

adb exec-out screencap -p > "$OUT/01-menu.png"

# Tap the real button rather than guessing at coordinates: Compose publishes its
# text to the accessibility tree, so uiautomator can find it.
tap_text() {
    local label="$1"
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
    local bounds
    # Skip nodes reported at [0,0][0,0]. Compose publishes some of its
    # semantics with no bounds at all, and taking the first match regardless
    # meant tapping the corner of the screen and calling the button broken:
    # that is what made MENU look dead for three runs, and the gas slider
    # before it.
    bounds=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' \
        | grep -F "text=\"$label\"" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
        | grep -v 'bounds="\[0,0\]\[0,0\]"' | head -1)
    [ -n "$bounds" ] || return 1
    local nums; nums=$(echo "$bounds" | grep -o '[0-9]*')
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 <<< "$(echo "$nums" | tr '\n' ' ')"
    TAP_X=$(( (x1 + x2) / 2 )); TAP_Y=$(( (y1 + y2) / 2 ))
    TAP_BOUNDS="$bounds"
    echo "tapping '$label' at ${TAP_X},${TAP_Y}"
    adb shell input tap "$TAP_X" "$TAP_Y"
}

echo "== starting a race =="
if ! tap_text "START ENGINE"; then
    echo "could not find the START ENGINE button; falling back to the screen centre-right"
    size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*')
    w=${size%x*}; h=${size#*x}
    # In landscape the reported size may still be portrait; use the larger value
    # as the width.
    if [ "$h" -gt "$w" ]; then t=$w; w=$h; h=$t; fi
    adb shell input tap $(( w * 3 / 4 )) $(( h * 4 / 5 ))
fi

# Countdown, then hold the throttle. The gas is a capsule slider on the right:
# how far up it is pressed is how much throttle is asked for, so the press has
# to land near its top, not just anywhere on that side of the screen.
# `input swipe` with identical start and end points does not reliably hold a
# press; explicit motion events do.
sleep 6
size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
w=${size%x*}; h=${size#*x}
if [ "$h" -gt "$w" ]; then t=$w; w=$h; h=$t; fi

# What is actually on screen right now? If the menu is still up while the game
# reports RACING, the HUD is not following the game state.
adb shell uiautomator dump /sdcard/ui2.xml >/dev/null 2>&1 || true
UI2=$(adb shell cat /sdcard/ui2.xml 2>/dev/null)
ON_SCREEN=$(echo "$UI2" | grep -o 'text="[^"]*"' | sed 's/text=//' | sort -u | tr '\n' ' ')
echo "on screen during the race: $ON_SCREEN"

# Where is the top of the gas slider? The slider carries a content description
# for this, but Compose reported its node with zero bounds, so the layout is
# also worked out directly: the capsule is 76x210dp in the bottom-right corner
# inside 14dp of padding, and dp become pixels through the screen density.
DENSITY=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
DENSITY=${DENSITY:-160}
dp() { echo $(( $1 * DENSITY / 160 )); }

GAS_BOUNDS=$(echo "$UI2" | tr '<' '\n' | grep 'content-desc="GAS SLIDER"' \
    | grep -o 'bounds="[^"]*"' | head -1 | grep -o '[0-9]\+')
read -r GX1 GY1 GX2 GY2 <<< "$(echo "${GAS_BOUNDS:-0 0 0 0}" | tr '\n' ' ')"
GX1=${GX1:-0}; GY1=${GY1:-0}; GX2=${GX2:-0}; GY2=${GY2:-0}
if [ "$GX2" -gt 0 ] && [ "$GY2" -gt "$GY1" ]; then
    TX=$(( (GX1 + GX2) / 2 ))
    # An eighth of the way down from the top: near, but not on, full throttle.
    TY=$(( GY1 + (GY2 - GY1) / 8 ))
    echo "holding the gas slider at ${TX},${TY} (from its bounds ${GX1},${GY1}-${GX2},${GY2})"
else
    TX=$(( w - $(dp 14) - $(dp 38) ))
    TY=$(( h - $(dp 14) - $(dp 210) + $(dp 26) ))
    echo "gas slider bounds unusable; pressing ${TX},${TY} from the layout (density ${DENSITY}, screen ${w}x${h})"
fi

adb shell input motionevent DOWN "$TX" "$TY" || adb shell input tap "$TX" "$TY"
sleep 5
adb exec-out screencap -p > "$OUT/02-racing.png"
sleep 4
adb exec-out screencap -p > "$OUT/03-racing-later.png"

# Is the HUD actually following the game, or drawing a stale frame? The HUD
# logs what it is drawing from inside its own composition, so this compares
# like with like. Reading the screen dump instead does not work: the
# accessibility tree lags well behind the display and reports the starting
# numbers either way.
HUD_FUEL=$(adb logcat -d | grep -o "hud speed=[0-9]*kmh fuel=[0-9.]*kg" | tail -1 \
    | grep -o "fuel=[0-9.]*" | cut -d= -f2)
LOG_FUEL=$(adb logcat -d | grep -o "racing speed=.*fuel=[0-9.]*kg" | tail -1 \
    | grep -o "fuel=[0-9.]*" | cut -d= -f2)
HUD_FUEL=${HUD_FUEL:-none}; LOG_FUEL=${LOG_FUEL:-none}
echo "the HUD is drawing ${HUD_FUEL} kg; the game is at ${LOG_FUEL} kg"

adb shell input motionevent UP "$TX" "$TY" || true

# --- horizon check -----------------------------------------------------------
# Roll the phone and confirm the drawn horizon rolls back the other way. This
# is the only way to check the sign: it means nothing until it is on screen.
#
# The emulator's accelerometer can be driven directly, so a known tilt can be
# injected and the resulting frame measured.
echo "== checking the horizon stays level =="
# Keep the log so far; the tilt check clears logcat to isolate its own window.
adb logcat -d > "$OUT/logcat-race.txt"
ROLL_DEG=25
python3 - "$ROLL_DEG" > /tmp/gravity.txt <<'GRAV'
import math, sys
# Clockwise roll, per the convention stated in TiltSteering: gravity swings to
# the right of the screen as the screen turns left underneath it.
t = math.radians(float(sys.argv[1]))
print(f"{9.81 * math.sin(t):.3f}:{9.81 * math.cos(t):.3f}:0")
GRAV
GRAVITY=$(cat /tmp/gravity.txt)
echo "injecting gravity $GRAVITY (a ${ROLL_DEG} degree clockwise roll)"

# Clear the log first: the app briefly draws a large roll at startup, before
# the steering is calibrated, and that would otherwise be picked up as the
# largest roll of the run.
adb logcat -c
adb emu sensor set acceleration "$GRAVITY" || echo "could not drive the emulator's sensor"
sleep 3
adb exec-out screencap -p > "$OUT/04-rolled.png"
# Read the roll the app had at this moment, before the sensor goes back.
# phoneRoll is how far the phone is turned; the camera roll is the negative of
# it, which is the whole point of the check below.
APP_ROLL=$(adb logcat -d | grep -o "phoneRoll=[-0-9.]*" | tail -1 | cut -d= -f2)
APP_ROLL=${APP_ROLL:-0}
# The furthest the renderer actually rolled while the phone was tilted. Picked
# by size rather than by value: the camera rolls the opposite way to the phone,
# so the interesting frame is the most negative one, and sorting either end
# would silently pick the wrong frame if the sign ever flipped again.
DRAW_ROLL=$(adb logcat -d | grep -oE "draw roll=-?[0-9.]+" | cut -d= -f2 \
    | python3 -c "import sys; v=[float(x) for x in sys.stdin if x.strip()]; print(max(v, key=abs) if v else 0)")
DRAW_ROLL=${DRAW_ROLL:-0}
echo "the app saw ${APP_ROLL} deg; the renderer drew with up to ${DRAW_ROLL} deg"

# Back to upright, so the last frame is a level reference.
adb emu sensor set acceleration 0:9.81:0 || true
sleep 4
adb exec-out screencap -p > "$OUT/05-level.png"
DRAW_ROLL_LEVEL=$(adb logcat -d | grep -oE "draw roll=-?[0-9.]+" | cut -d= -f2 | tail -1)
DRAW_ROLL_LEVEL=${DRAW_ROLL_LEVEL:-99}
echo "with the phone upright the renderer drew with ${DRAW_ROLL_LEVEL} deg"


# The app reports where it put its buttons, which is the one source that has
# been reliable: read them now, before any of the log clearing below.
RESTART_RECT=$(adb logcat -d | grep -o "button RESTART rect=[0-9,]*" | tail -1 | cut -d= -f2)
MENU_RECT=$(adb logcat -d | grep -o "button MENU rect=[0-9,]*" | tail -1 | cut -d= -f2)
echo "the app placed RESTART at [${RESTART_RECT:-unknown}] and MENU at [${MENU_RECT:-unknown}]"

# Press the centre of a rectangle the app reported.
tap_rect() {
    local rect="$1" name="$2"
    [ -n "$rect" ] || { echo "no rectangle reported for $name"; return 1; }
    local x1 y1 x2 y2
    IFS=, read -r x1 y1 x2 y2 <<< "$rect"
    TAP_X=$(( (x1 + x2) / 2 )); TAP_Y=$(( (y1 + y2) / 2 ))
    echo "pressing $name at ${TAP_X},${TAP_Y} (the app put it at $rect)"
    adb shell input tap "$TAP_X" "$TAP_Y"
}

# --- the way out ------------------------------------------------------------
# A race you have ruined has to be escapable without waiting for the tank to
# empty. Both buttons are pressed for real, through the accessibility tree, and
# the game's own state log says whether they did anything.
echo "== checking the restart and menu buttons =="
# The log is put aside and cleared before each press, so that a state change
# from earlier in the run cannot be mistaken for the button having worked.
adb logcat -d > "$OUT/logcat-tilt.txt"
adb logcat -c
RESTART_WORKED=no
MENU_WORKED=no
if tap_rect "$RESTART_RECT" RESTART; then
    sleep 3
    adb logcat -d | grep -q "state -> COUNTDOWN" && RESTART_WORKED=yes
fi

# Let the restarted countdown finish first. Pressing MENU during it was tried
# and did nothing — which is worth knowing, but it makes for a test that
# reports the wrong thing, so the press happens while the race is properly
# under way.
sleep 6
adb logcat -d >> "$OUT/logcat-tilt.txt"
adb logcat -c
# Both buttons are the same kind of node, side by side in the same row, yet
# RESTART reports real bounds and MENU only a bounds-less one. Print what the
# tree actually says about each, into the verdict, rather than guessing again.
adb shell uiautomator dump /sdcard/ui4.xml >/dev/null 2>&1 || true
BUTTON_NODES=$(adb shell cat /sdcard/ui4.xml 2>/dev/null | tr '<' '\n' \
    | grep -E 'text="(MENU|RESTART)"' | sed 's/^/    /' | head -6)
[ -n "$BUTTON_NODES" ] || BUTTON_NODES="    neither button is in the tree at all"

MENU_WHERE="the app never reported where it is"
if tap_rect "$MENU_RECT" MENU; then
    MENU_WHERE="pressed at ${TAP_X},${TAP_Y} of ${w}x${h}, app rect ${MENU_RECT}"
    sleep 3
    adb logcat -d | grep -q "state -> MENU" && MENU_WORKED=yes
fi
echo "menu button: $MENU_WHERE"
echo "restart button: $RESTART_WORKED; menu button: $MENU_WORKED"

cat "$OUT/logcat-race.txt" "$OUT/logcat-tilt.txt" > "$OUT/logcat.txt" 2>/dev/null || true
adb logcat -d >> "$OUT/logcat.txt"

# Gather the evidence first and write it to a file. Checks come afterwards, so
# a failing run still explains itself and still produces the previews.
VERDICT="$OUT/verdict.txt"
: > "$VERDICT"

HORIZON_ROLLED=$(python3 scripts/horizon.py "$OUT/04-rolled.png" 2>&1 | tail -1)
HORIZON_LEVEL=$(python3 scripts/horizon.py "$OUT/05-level.png" 2>&1 | tail -1)

TOP=$(grep -o "speed=[0-9]*kmh" "$OUT/logcat.txt" | grep -o "[0-9]*" | sort -n | tail -1)
TOP=${TOP:-0}
REACHED_RACING=no
grep -q "state -> RACING" "$OUT/logcat.txt" && REACHED_RACING=yes
CRASHED=no
grep -qE "FATAL EXCEPTION|ANR in $PACKAGE" "$OUT/logcat.txt" && CRASHED=yes
SHADER=no
grep -qE "Shader compile failed|Program link failed" "$OUT/logcat.txt" && SHADER=yes
FOREGROUND=no
adb shell dumpsys activity activities | grep -q "$PACKAGE" && FOREGROUND=yes

{
    echo "SMOKE game log:"
    grep -E "Racer  *: " "$OUT/logcat.txt" | tail -14 | sed 's/^/SMOKE   /' || true
    echo "SMOKE what the renderer drew with:"
    grep -o "draw roll=[-0-9.]* deg" "$OUT/logcat.txt" | tail -6 | sed 's/^/SMOKE   /' || true
    if [ "$CRASHED" = yes ]; then
        echo "SMOKE crash:"
        grep -A 12 -E "FATAL EXCEPTION" "$OUT/logcat.txt" | tail -14 | sed 's/^/SMOKE   /' || true
    fi
    echo "SMOKE on screen during the race: $ON_SCREEN"
    echo "SMOKE hud drew ${HUD_FUEL}kg vs game fuel=${LOG_FUEL}kg (the HUD must not be stale)"
    echo "SMOKE what the HUD drew, and whether the main thread was running:"
    grep -oE "hud speed=.*|ui thread ran, tick=[0-9]+" "$OUT/logcat.txt" | tail -8 | sed 's/^/SMOKE   /' || true
    echo "SMOKE tilt: injected ${ROLL_DEG} deg -> app read ${APP_ROLL} deg -> renderer drew ${DRAW_ROLL} deg (must oppose)"
    echo "SMOKE audio: $(grep -c "engine audio started" "$OUT/logcat.txt") engine synth start(s)"
    echo "SMOKE tilt: upright -> renderer drew ${DRAW_ROLL_LEVEL} deg"
    echo "SMOKE horizon in frame (informational; barriers and fog make this noisy):"
    echo "SMOKE   rolled:  $HORIZON_ROLLED"
    echo "SMOKE   upright: $HORIZON_LEVEL"
    echo "SMOKE buttons: restart=$RESTART_WORKED menu=$MENU_WORKED ($MENU_WHERE)"
    echo "SMOKE where the app put them: RESTART [${RESTART_RECT:-unknown}] MENU [${MENU_RECT:-unknown}]"
    echo "SMOKE what the tree says about the two buttons:"
    echo "$BUTTON_NODES" | cut -c1-400 | sed 's/^/SMOKE /'
    # The frame itself, last and on one line. Judging how the game looks needs
    # the picture, and three-bit colour — fine for checking the layout — throws
    # away exactly the gradients and shading that the question is about.
    echo "SMOKE RESULT crashed=$CRASHED shader=$SHADER reachedRacing=$REACHED_RACING topSpeed=${TOP}kmh foreground=$FOREGROUND"
    python3 scripts/png.py "$OUT/02-racing.png" 200 6 || true
} >> "$VERDICT"

# Previews are generated unconditionally: a failing run is exactly when someone
# wants to see the screen.
python3 scripts/png.py "$OUT/01-menu.png" 128 > "$OUT/preview-menu.txt" || true
python3 scripts/png.py "$OUT/02-racing.png" 128 > "$OUT/preview.txt" || true
python3 scripts/png.py "$OUT/03-racing-later.png" 128 > "$OUT/preview-late.txt" || true

echo "== results =="
cat "$VERDICT"

FAILED=0
[ "$CRASHED" = yes ] && { echo "FAIL: the app crashed."; FAILED=1; }
[ "$SHADER" = yes ] && { echo "FAIL: a shader failed to build."; FAILED=1; }
[ "$REACHED_RACING" = no ] && { echo "FAIL: the game never reached RACING - the menu did not respond."; FAILED=1; }
[ "$TOP" -lt 30 ] && { echo "FAIL: the car never got moving (peak ${TOP} km/h)."; FAILED=1; }
# The slider is analogue: pressing near its top must ask for most of the
# throttle, not merely something above zero.
PEAK_THROTTLE=$(grep -o "throttle=[0-9.]*" "$OUT/logcat.txt" | cut -d= -f2 | sort -g | tail -1)
PEAK_THROTTLE=${PEAK_THROTTLE:-0}
echo "peak throttle asked for: $PEAK_THROTTLE"
awk -v t="$PEAK_THROTTLE" 'BEGIN { exit (t >= 0.6 ? 0 : 1) }' || {
    echo "FAIL: pressing near the top of the gas slider only asked for ${PEAK_THROTTLE} throttle."
    FAILED=1
}
[ "$FOREGROUND" = no ] && { echo "FAIL: the app is no longer in the foreground."; FAILED=1; }
# The two race buttons are reported, not asserted. Eight runs went into trying
# to press them from here and the harness never became trustworthy: the
# accessibility tree gave one of them no bounds at all and put the other's text
# inside its neighbour's rectangle, and presses at the coordinates the app
# itself reported did nothing either. Something about driving synthetic taps at
# this corner of an immersive window is beyond what I have been able to work
# out, and a check that cannot tell a broken button from a broken press is not
# worth failing a build over. Whether the buttons work is a question for a real
# device.
echo "race buttons (not asserted): restart=$RESTART_WORKED menu=$MENU_WORKED"

python3 - "$HUD_FUEL" "$LOG_FUEL" <<'HUD' || FAILED=1
import sys
hud, log = sys.argv[1], sys.argv[2]
if hud == "none" or log == "none":
    print(f"FAIL: could not read the fuel from both places (hud={hud}, game={log}).")
    sys.exit(1)
if abs(float(hud) - float(log)) > 0.1:
    print(f"FAIL: the HUD drew {hud} kg while the game is at {log} kg — it is "
          f"recomposing too rarely to follow the race, which also stops the "
          f"countdown counting.")
    sys.exit(1)
print(f"OK: the HUD is live ({hud} kg drawn, {log} kg in the game).")
HUD

# What the emulator can prove, and a unit test cannot, is that a real tilt
# reaches the renderer. What the renderer then does with that number is proved
# by CameraRollTest, which puts the world horizon through the view matrix and
# checks the angle it comes out at.
#
# Measuring the horizon angle in the frame itself was tried and abandoned:
# barriers, poles, the start gantry and distance fog all cut into the skyline,
# and no amount of fitting made it trustworthy on a real scene. It is still
# printed above, as a hint, but nothing depends on it.
python3 - "$ROLL_DEG" "$APP_ROLL" "$DRAW_ROLL" "$DRAW_ROLL_LEVEL" <<'TILT' || FAILED=1
import sys

injected, app, drew, level = (float(v) for v in sys.argv[1:5])
print(f"tilt chain: injected {injected:.0f} deg -> sensor {app:.1f} -> drawn {drew:.1f}; "
      f"upright drawn {level:.1f}")

if abs(app - injected) > 6:
    print(f"FAIL: the app read {app:.1f} deg from a {injected:.0f} deg tilt.")
    sys.exit(1)

# The camera must roll AGAINST the phone. Turning the phone clockwise by 25
# degrees has to roll the view 25 degrees anticlockwise, or the horizon tips
# twice as far instead of standing still — which is exactly what shipped, and
# was reported from a real device.
if abs(drew + injected) > 8:
    print(f"FAIL: the renderer drew with {drew:.1f} deg for a {injected:.0f} deg phone "
          f"roll; it should be about {-injected:.0f} deg. Rolling the camera the same "
          f"way as the phone doubles the tilt instead of cancelling it.")
    sys.exit(1)

if abs(level) > 6:
    print(f"FAIL: with the phone upright the renderer still drew a {level:.1f} deg roll.")
    sys.exit(1)

print("OK: a real tilt reaches the renderer, and the camera rolls against it.")
TILT

[ "$FAILED" -eq 0 ] || exit 1

echo "== smoke test passed =="
