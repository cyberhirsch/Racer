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
sleep 6
size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*')
w=${size%x*}; h=${size#*x}
if [ "$h" -gt "$w" ]; then t=$w; w=$h; h=$t; fi
adb shell input swipe $(( w * 4 / 5 )) $(( h / 2 )) $(( w * 4 / 5 )) $(( h / 2 )) 8000 &
SWIPE=$!
sleep 5
adb exec-out screencap -p > "$OUT/02-racing.png"
sleep 4
adb exec-out screencap -p > "$OUT/03-racing-later.png"
wait $SWIPE || true

adb logcat -d > "$OUT/logcat.txt"

echo "== checking for crashes =="
if grep -qE "FATAL EXCEPTION|ANR in $PACKAGE" "$OUT/logcat.txt"; then
    echo "The app crashed:"
    grep -A 40 -E "FATAL EXCEPTION" "$OUT/logcat.txt" || true
    exit 1
fi
if grep -qE "Shader compile failed|Program link failed" "$OUT/logcat.txt"; then
    echo "OpenGL shader problem:"
    grep -B 2 -A 20 -E "Shader compile failed|Program link failed" "$OUT/logcat.txt"
    exit 1
fi

echo "== checking the game actually started =="
grep -E "Racer.*(state ->|racing )" "$OUT/logcat.txt" | tail -20 || true

if ! grep -q "state -> RACING" "$OUT/logcat.txt"; then
    echo "FAIL: the game never reached RACING — the menu did not respond to the tap."
    exit 1
fi

# The car must have moved. This is the end-to-end proof: touch -> physics ->
# a car with speed on it.
TOP=$(grep -o "speed=[0-9]*kmh" "$OUT/logcat.txt" | grep -o "[0-9]*" | sort -n | tail -1)
TOP=${TOP:-0}
echo "top speed seen in the log: ${TOP} km/h"
if [ "$TOP" -lt 30 ]; then
    echo "FAIL: the car never got moving (peak ${TOP} km/h), so the throttle is not reaching the physics."
    exit 1
fi

echo "== checking it is still in the foreground =="
if ! adb shell dumpsys activity activities | grep -q "$PACKAGE"; then
    echo "The app is no longer running."
    exit 1
fi

echo "== checking the renderer produced a real frame =="
python3 - "$OUT/02-racing.png" <<'CHECK'
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

# Emit small previews into the log, so the rendering can be reviewed even when
# the artifact store is unreachable.
python3 scripts/png.py "$OUT/01-menu.png" 128 > "$OUT/preview-menu.txt"
python3 scripts/png.py "$OUT/02-racing.png" 128 > "$OUT/preview.txt"
python3 scripts/png.py "$OUT/03-racing-later.png" 128 > "$OUT/preview-late.txt"

echo "== smoke test passed =="
