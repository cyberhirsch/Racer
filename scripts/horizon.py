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


def sky_column_heights(width, height, channels, pixels):
    """
    How much sky each column contains.

    Counting sky pixels per column, rather than hunting for the first one, is
    what makes this survive a real frame: barriers, the start gantry and poles
    break the skyline, and fog blends distant ground to exactly the sky colour.
    A single boundary pixel per column jumps hundreds of pixels when a post gets
    in the way; a count only loses that post's area.

    The sky sits at the top of the frame, so the count is the height of the
    boundary, and its slope across the frame is the tilt.
    """
    points = []
    step = max(1, width // 200)
    for x in range(0, width, step):
        count = 0
        for y in range(height):
            o = (y * width + x) * channels
            if is_sky(pixels[o], pixels[o + 1], pixels[o + 2]):
                count += 1
        # Columns that are entirely sky or entirely ground say nothing about
        # where the boundary is; they only say it is off the top or bottom.
        if 0 < count < height - 1:
            points.append((x, count))
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
    Least-squares slope, refined by throwing out points far from the line.

    The rejection has to be measured against the fitted line, not against the
    median height: a horizon rolled 25 degrees crosses a phone screen top to
    bottom, so "far from the average height" describes almost all of it.
    """
    import math

    kept = list(points)
    slope = intercept = 0.0
    for _ in range(4):
        n = len(kept)
        if n < 15:
            raise SystemExit("FAIL: too few horizon points survived the fit")
        mx = sum(p[0] for p in kept) / n
        my = sum(p[1] for p in kept) / n
        num = sum((p[0] - mx) * (p[1] - my) for p in kept)
        den = sum((p[0] - mx) ** 2 for p in kept)
        if den == 0:
            raise SystemExit("FAIL: degenerate horizon fit")
        slope = num / den
        intercept = my - slope * mx
        residuals = sorted(abs(p[1] - (slope * p[0] + intercept)) for p in kept)
        # Keep everything within a few times the typical error, so scenery
        # poking through the skyline is dropped but the line itself is not.
        limit = max(6.0, residuals[len(residuals) // 2] * 4)
        trimmed = [p for p in kept if abs(p[1] - (slope * p[0] + intercept)) <= limit]
        if len(trimmed) == len(kept):
            break
        kept = trimmed

    return math.degrees(math.atan(slope)), len(kept), int(intercept)


def samples(points, count=8):
    """A few boundary points across the frame, for when a fit looks wrong."""
    if not points:
        return ""
    step = max(1, len(points) // count)
    return " ".join(f"({p[0]},{p[1]})" for p in points[::step][:count])


if __name__ == "__main__":
    width, height, channels, pixels = read_png(sys.argv[1])
    points = sky_column_heights(width, height, channels, pixels)
    if len(points) < 20:
        print(f"HORIZON none ({len(points)} sky points in {width}x{height}); "
              f"samples: {describe(width, height, channels, pixels)}")
        raise SystemExit(1)
    angle, used, _ = fit_angle(points)
    print(f"HORIZON {angle:+.1f} deg (from {used}/{len(points)} points in {width}x{height}; "
          f"boundary {samples(points)})")
