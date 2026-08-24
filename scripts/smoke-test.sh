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
python3 - "$OUT/02-racing.png" <<'PY'
import struct, sys, zlib

path = sys.argv[1]
data = open(path, 'rb').read()
assert data[:8] == b'\x89PNG\r\n\x1a\n', "not a PNG"

pos, idat, width, height, bitdepth, colortype = 8, b'', 0, 0, 0, 0
while pos < len(data):
    length = struct.unpack('>I', data[pos:pos+4])[0]
    tag = data[pos+4:pos+8]
    chunk = data[pos+8:pos+8+length]
    if tag == b'IHDR':
        width, height, bitdepth, colortype = struct.unpack('>IIBB', chunk[:10])
    elif tag == b'IDAT':
        idat += chunk
    pos += 12 + length

channels = {0: 1, 2: 3, 4: 2, 6: 4}[colortype]
assert bitdepth == 8, f"unexpected bit depth {bitdepth}"
raw = zlib.decompress(idat)
stride = width * channels

# Undo the PNG row filters.
out = bytearray()
prev = bytearray(stride)
i = 0
for _ in range(height):
    f = raw[i]; i += 1
    line = bytearray(raw[i:i+stride]); i += stride
    for x in range(stride):
        a = line[x-channels] if x >= channels else 0
        b = prev[x]
        c = prev[x-channels] if x >= channels else 0
        if f == 1: line[x] = (line[x] + a) & 0xFF
        elif f == 2: line[x] = (line[x] + b) & 0xFF
        elif f == 3: line[x] = (line[x] + (a + b) // 2) & 0xFF
        elif f == 4:
            p = a + b - c
            pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
            pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
            line[x] = (line[x] + pr) & 0xFF
    out += line
    prev = line

# Sample a grid of pixels and count distinct coarse colours.
colours = set()
step = max(1, height // 60)
for y in range(0, height, step):
    for x in range(0, width, max(1, width // 60)):
        o = y * stride + x * channels
        colours.add((out[o] // 24, out[o+1] // 24, out[o+2] // 24))

print(f"{width}x{height}, {len(colours)} distinct colours sampled")
if len(colours) < 6:
    print("FAIL: the frame is nearly blank — the renderer drew nothing.")
    sys.exit(1)
print("OK: the renderer produced a real frame.")
PY

echo "== smoke test passed =="
