"""
Minimal PNG reader/writer, used by the smoke test.

CI runners cannot hand a screenshot back to a reviewer directly when artifact
storage is unreachable, so this also downscales a screenshot and prints it as
base64 that can be decoded from the job log.
"""
import base64
import struct
import sys
import zlib


def read_png(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', f"{path} is not a PNG"

    pos, idat, width, height, depth, colortype = 8, b'', 0, 0, 0, 0
    while pos < len(data):
        length = struct.unpack('>I', data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if tag == b'IHDR':
            width, height, depth, colortype = struct.unpack('>IIBB', chunk[:10])
        elif tag == b'IDAT':
            idat += chunk
        pos += 12 + length

    channels = {0: 1, 2: 3, 4: 2, 6: 4}[colortype]
    assert depth == 8, f"unexpected bit depth {depth}"
    raw = zlib.decompress(idat)
    stride = width * channels

    # Undo the per-row filters.
    out = bytearray()
    prev = bytearray(stride)
    i = 0
    for _ in range(height):
        f = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        if f:
            for x in range(stride):
                a = line[x - channels] if x >= channels else 0
                b = prev[x]
                c = prev[x - channels] if x >= channels else 0
                if f == 1:
                    line[x] = (line[x] + a) & 0xFF
                elif f == 2:
                    line[x] = (line[x] + b) & 0xFF
                elif f == 3:
                    line[x] = (line[x] + (a + b) // 2) & 0xFF
                elif f == 4:
                    p = a + b - c
                    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                    pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                    line[x] = (line[x] + pr) & 0xFF
        out += line
        prev = line
    return width, height, channels, out


def write_png(path_or_none, width, height, rgb):
    """rgb is a flat bytearray of length width*height*3."""
    raw = b''.join(b'\x00' + bytes(rgb[y * width * 3:(y + 1) * width * 3]) for y in range(height))

    def chunk(tag, payload):
        return (struct.pack('>I', len(payload)) + tag + payload
                + struct.pack('>I', zlib.crc32(tag + payload) & 0xFFFFFFFF))

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    if path_or_none:
        open(path_or_none, 'wb').write(png)
    return png


def downscale(path, target_width):
    """Box-filter a screenshot down to target_width, preserving aspect."""
    width, height, channels, pixels = read_png(path)
    scale = max(1, width // target_width)
    ow, oh = width // scale, height // scale
    out = bytearray(ow * oh * 3)
    for oy in range(oh):
        for ox in range(ow):
            r = g = b = n = 0
            for dy in range(scale):
                row = (oy * scale + dy) * width * channels
                for dx in range(scale):
                    o = row + (ox * scale + dx) * channels
                    r += pixels[o]; g += pixels[o + 1]; b += pixels[o + 2]; n += 1
            o = (oy * ow + ox) * 3
            out[o] = r // n; out[o + 1] = g // n; out[o + 2] = b // n
    return ow, oh, out


if __name__ == '__main__':
    # Usage: png.py <screenshot.png> <target-width>
    path = sys.argv[1]
    target = int(sys.argv[2]) if len(sys.argv) > 2 else 200
    w, h, rgb = downscale(path, target)
    encoded = base64.b64encode(write_png(None, w, h, rgb)).decode()
    # Emit the whole image on one line. Log viewers and log APIs window by
    # line count, so wrapping this would push the start of the image out of
    # view exactly when the picture is worth looking at.
    print(f"PREVIEW {path} {w}x{h} {encoded}")
