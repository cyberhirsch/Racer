#!/usr/bin/env bash
#
# Emulator smoke test.
#
# Installs the app, plays it for a few seconds, and fails if it crashed, if it
# never reached the foreground, or if the renderer never produced a frame.
# Screenshots are saved so the rendering can be inspected by eye afterwards.
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

# Let it settle, then drive it: tap START ENGINE, sit through the countdown,
# and hold the throttle so the car is actually moving when we photograph it.
sleep 6
adb shell input tap 1500 1000 || true      # right-hand column: START ENGINE
sleep 2
adb exec-out screencap -p > "$OUT/01-after-start.png"
sleep 6                                     # countdown
adb shell input swipe 1800 700 1800 700 6000 &   # hold the throttle (right half)
sleep 5
adb exec-out screencap -p > "$OUT/02-racing.png"
sleep 4
adb exec-out screencap -p > "$OUT/03-racing-later.png"
wait || true

adb logcat -d > "$OUT/logcat.txt"

echo "== checking for crashes =="
if grep -qE "FATAL EXCEPTION|ANR in $PACKAGE|Force finishing activity" "$OUT/logcat.txt"; then
    echo "The app crashed:"
    grep -A 40 -E "FATAL EXCEPTION" "$OUT/logcat.txt" || true
    exit 1
fi

# Our own failure paths: a shader that will not compile, or a GL error, throws.
if grep -qE "Shader compile failed|Program link failed" "$OUT/logcat.txt"; then
    echo "OpenGL shader problem:"
    grep -B 2 -A 20 -E "Shader compile failed|Program link failed" "$OUT/logcat.txt"
    exit 1
fi

echo "== checking it is still in the foreground =="
if ! adb shell dumpsys activity activities | grep -q "$PACKAGE"; then
    echo "The app is no longer running."
    exit 1
fi

# A screenshot of a blank screen means the renderer drew nothing. Compare the
# racing shot against the sky colour and the dark menu background: a live frame
# has road, car and HUD in it, so it must have a decent spread of colours.
echo "== checking the renderer produced a real frame =="
# A blank screenshot means the renderer drew nothing. A live frame has road,
# car and HUD in it, so it must contain a decent spread of colours.
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

# Emit a small preview into the log, so the rendering can be reviewed even when
# the artifact store is unreachable.
python3 scripts/png.py "$OUT/02-racing.png" 200 > "$OUT/preview.txt"
python3 scripts/png.py "$OUT/01-after-start.png" 160 > "$OUT/preview-menu.txt"

echo "== smoke test passed =="
