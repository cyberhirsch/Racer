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
    bounds=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' \
        | grep -F "text=\"$label\"" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
    [ -n "$bounds" ] || return 1
    local nums; nums=$(echo "$bounds" | grep -o '[0-9]*')
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 <<< "$(echo "$nums" | tr '\n' ' ')"
    echo "tapping '$label' at $(( (x1 + x2) / 2 )),$(( (y1 + y2) / 2 ))"
    adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
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

# The slider carries a content description for exactly this purpose.
GAS_BOUNDS=$(echo "$UI2" | tr '<' '\n' | grep 'content-desc="GAS SLIDER"' \
    | grep -o 'bounds="[^"]*"' | head -1 | grep -o '[0-9]\+')
if [ -n "$GAS_BOUNDS" ]; then
    read -r GX1 GY1 GX2 GY2 <<< "$(echo "$GAS_BOUNDS" | tr '\n' ' ')"
    TX=$(( (GX1 + GX2) / 2 ))
    # An eighth of the way down from the top: near, but not on, full throttle.
    TY=$(( GY1 + (GY2 - GY1) / 8 ))
    echo "holding the gas slider at ${TX},${TY} (slider ${GX1},${GY1}-${GX2},${GY2})"
else
    TX=$(( w * 93 / 100 )); TY=$(( h * 60 / 100 ))
    echo "could not find the gas slider; pressing ${TX},${TY} (screen ${w}x${h})"
fi

adb shell input motionevent DOWN "$TX" "$TY" || adb shell input tap "$TX" "$TY"
sleep 5
adb exec-out screencap -p > "$OUT/02-racing.png"
sleep 4
adb exec-out screencap -p > "$OUT/03-racing-later.png"

# Is the HUD actually following the game, or drawing a stale frame? Compose
# skips a composable whose inputs have not changed, and the game state it draws
# lives outside Compose — so the display once sat on the starting numbers while
# the race ran underneath it, which is what stopped the countdown counting.
# The fuel reading is the check: it is on screen and in the log, in the same
# format, and it only ever falls.
adb shell uiautomator dump /sdcard/ui3.xml >/dev/null 2>&1 || true
HUD_FUEL=$(adb shell cat /sdcard/ui3.xml 2>/dev/null | grep -o 'text="[0-9.]* kg"' \
    | grep -o '[0-9.]*' | head -1)
LOG_FUEL=$(adb logcat -d | grep -o "fuel=[0-9.]*kg" | tail -1 | grep -o '[0-9.]*')
HUD_FUEL=${HUD_FUEL:-none}; LOG_FUEL=${LOG_FUEL:-none}
echo "HUD shows ${HUD_FUEL} kg; the game is at ${LOG_FUEL} kg"

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


cat "$OUT/logcat-race.txt" > "$OUT/logcat.txt" 2>/dev/null || true
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
    echo "SMOKE hud fuel=${HUD_FUEL}kg vs game fuel=${LOG_FUEL}kg (the HUD must not be stale)"
    echo "SMOKE tilt: injected ${ROLL_DEG} deg -> app read ${APP_ROLL} deg -> renderer drew ${DRAW_ROLL} deg (must oppose)"
    echo "SMOKE audio: $(grep -c "engine audio started" "$OUT/logcat.txt") engine synth start(s)"
    echo "SMOKE tilt: upright -> renderer drew ${DRAW_ROLL_LEVEL} deg"
    echo "SMOKE horizon in frame (informational; barriers and fog make this noisy):"
    echo "SMOKE   rolled:  $HORIZON_ROLLED"
    echo "SMOKE   upright: $HORIZON_LEVEL"
    echo "SMOKE RESULT crashed=$CRASHED shader=$SHADER reachedRacing=$REACHED_RACING topSpeed=${TOP}kmh foreground=$FOREGROUND"
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
[ "$FOREGROUND" = no ] && { echo "FAIL: the app is no longer in the foreground."; FAILED=1; }

python3 - "$HUD_FUEL" "$LOG_FUEL" <<'HUD' || FAILED=1
import sys
hud, log = sys.argv[1], sys.argv[2]
if hud == "none" or log == "none":
    print(f"FAIL: could not read the fuel from both places (hud={hud}, game={log}).")
    sys.exit(1)
if abs(float(hud) - float(log)) > 0.1:
    print(f"FAIL: the HUD shows {hud} kg while the game is at {log} kg — it is "
          f"drawing a stale frame, which also stops the countdown counting.")
    sys.exit(1)
print(f"OK: the HUD is live ({hud} kg on screen, {log} kg in the game).")
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
