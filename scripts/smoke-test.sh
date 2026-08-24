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

# Countdown, then hold the throttle on the right-hand half of the screen.
# `input swipe` with identical start and end points does not reliably hold a
# press; explicit motion events do.
sleep 6
size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
w=${size%x*}; h=${size#*x}
if [ "$h" -gt "$w" ]; then t=$w; w=$h; h=$t; fi
TX=$(( w * 4 / 5 )); TY=$(( h / 2 ))
echo "holding the throttle at ${TX},${TY} (screen ${w}x${h})"

# What is actually on screen right now? If the menu is still up while the game
# reports RACING, the HUD is not following the game state.
adb shell uiautomator dump /sdcard/ui2.xml >/dev/null 2>&1 || true
ON_SCREEN=$(adb shell cat /sdcard/ui2.xml 2>/dev/null | grep -o 'text="[^"]*"' | sed 's/text=//' | sort -u | tr '\n' ' ')
echo "on screen during the race: $ON_SCREEN"

adb shell input motionevent DOWN "$TX" "$TY" || adb shell input tap "$TX" "$TY"
sleep 5
adb exec-out screencap -p > "$OUT/02-racing.png"
sleep 4
adb exec-out screencap -p > "$OUT/03-racing-later.png"
adb shell input motionevent UP "$TX" "$TY" || true

adb logcat -d > "$OUT/logcat.txt"

# Gather the evidence first and write it to a file. Checks come afterwards, so
# a failing run still explains itself and still produces the previews.
VERDICT="$OUT/verdict.txt"
: > "$VERDICT"

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
    grep -E "Racer  *: " "$OUT/logcat.txt" | tail -18 | sed 's/^/SMOKE   /' || true
    if [ "$CRASHED" = yes ]; then
        echo "SMOKE crash:"
        grep -A 12 -E "FATAL EXCEPTION" "$OUT/logcat.txt" | tail -14 | sed 's/^/SMOKE   /' || true
    fi
    echo "SMOKE on screen during the race: $ON_SCREEN"
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

python3 - "$OUT/02-racing.png" <<'CHECK' || FAILED=1
import sys
sys.path.insert(0, "scripts")
from png import read_png

width, height, channels, pixels = read_png(sys.argv[1])
colours = set()
for y in range(0, height, max(1, height // 60)):
    for x in range(0, width, max(1, width // 60)):
        o = (y * width + x) * channels
        colours.add((pixels[o] // 24, pixels[o + 1] // 24, pixels[o + 2] // 24))

print(f"{width}x{height}, {len(colours)} distinct colours sampled")
if len(colours) < 6:
    print("FAIL: the frame is nearly blank - the renderer drew nothing.")
    sys.exit(1)
print("OK: the renderer produced a real frame.")
CHECK

[ "$FAILED" -eq 0 ] || exit 1

echo "== smoke test passed =="
