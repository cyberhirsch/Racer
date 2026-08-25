"""
Measure the angle of the horizon in a screenshot.

The renderer rolls the camera to cancel the phone's rotation, and there is no
way to check that from inside the code — the sign only means anything once it
has been drawn. So: find the sky/ground boundary and fit a line to it.

Prints the angle in degrees, positive when the horizon runs down to the right
(a clockwise tilt in screen terms, y being measured downwards).

Usage: horizon.py <screenshot.png>
"""
import sys

sys.path.insert(0, "scripts")
from png import read_png


# The sky is drawn by glClearColor with GlRenderer's SKY value and nothing
# shades it, so sky pixels come out at exactly this colour. Matching it
# precisely beats "bright and blueish", which also matches parts of the HUD.
SKY = (143, 181, 222)
TOLERANCE = 10


def is_sky(r, g, b):
    return (abs(r - SKY[0]) <= TOLERANCE
            and abs(g - SKY[1]) <= TOLERANCE
            and abs(b - SKY[2]) <= TOLERANCE)


def sky_boundary(width, height, channels, pixels):
    """
    For each sampled column, the row where the sky ends.

    Scans upward from the middle of the frame to the first sky pixel, rather
    than downward from the top: the HUD sits over the sky, and scanning down
    stops at the first chip it meets.
    """
    points = []
    step = max(1, width // 160)
    for x in range(int(width * 0.04), int(width * 0.96), step):
        found = None
        for y in range(int(height * 0.9), -1, -1):
            o = (y * width + x) * channels
            if is_sky(pixels[o], pixels[o + 1], pixels[o + 2]):
                found = y
                break
        if found is not None and found < height * 0.88:
            points.append((x, found))
    return points


def describe(width, height, channels, pixels):
    """What the frame actually looks like, for when the fit finds nothing."""
    out = []
    for fx, fy in ((0.1, 0.05), (0.5, 0.1), (0.5, 0.3), (0.5, 0.5), (0.5, 0.8), (0.9, 0.05)):
        x, y = int(width * fx), int(height * fy)
        o = (y * width + x) * channels
        out.append(f"({fx:.0%},{fy:.0%})=rgb({pixels[o]},{pixels[o+1]},{pixels[o+2]})")
    return " ".join(out)


def fit_angle(points):
    """
    Least-squares slope, after throwing out points far from the median.

    HUD chips and the odd piece of scenery poke into the sky, so a plain fit
    gets dragged around by them.
    """
    import math

    if len(points) < 20:
        raise SystemExit("FAIL: could not find the horizon (too few points)")


    ys = sorted(p[1] for p in points)
    median = ys[len(ys) // 2]
    spread = max(12, int(len(ys) * 0.02) + 12)
    kept = [p for p in points if abs(p[1] - median) < spread * 4]
    if len(kept) < 20:
        raise SystemExit("FAIL: the horizon is too broken up to measure")

    n = len(kept)
    mx = sum(p[0] for p in kept) / n
    my = sum(p[1] for p in kept) / n
    num = sum((p[0] - mx) * (p[1] - my) for p in kept)
    den = sum((p[0] - mx) ** 2 for p in kept)
    if den == 0:
        raise SystemExit("FAIL: degenerate horizon fit")
    slope = num / den
    return math.degrees(math.atan(slope)), n, median


if __name__ == "__main__":
    width, height, channels, pixels = read_png(sys.argv[1])
    points = sky_boundary(width, height, channels, pixels)
    if len(points) < 20:
        print(f"HORIZON none ({len(points)} sky points in {width}x{height}); "
              f"samples: {describe(width, height, channels, pixels)}")
        raise SystemExit(1)
    angle, used, median = fit_angle(points)
    print(f"HORIZON {angle:+.1f} deg (from {used} points, mean height {median}/{height})")
