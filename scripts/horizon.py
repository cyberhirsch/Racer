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


def sky_boundary(width, height, channels, pixels):
    """For each sampled column, the first row from the top that is not sky."""
    points = []
    step = max(1, width // 160)
    for x in range(int(width * 0.06), int(width * 0.94), step):
        for y in range(0, int(height * 0.85)):
            o = (y * width + x) * channels
            r, g, b = pixels[o], pixels[o + 1], pixels[o + 2]
            # Sky is bright and blue-dominant; road, grass, barriers and HUD
            # are not.
            if not (b > 110 and b > r + 8 and b > g + 4):
                points.append((x, y))
                break
    return points


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
    angle, used, median = fit_angle(sky_boundary(width, height, channels, pixels))
    print(f"HORIZON {angle:+.1f} deg (from {used} points, mean height {median}/{height})")
