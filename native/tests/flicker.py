"""Diagnostic of the unchanged core's frame-local statistics; not a stability test.
Usage: python3 native/tests/flicker.py ./dstrech
"""
import pathlib
import random
import subprocess
import sys
import tempfile

rng = random.Random(11)
base = []
for _ in range(64 * 48):
    value = rng.randint(95, 135)
    base.append([value + rng.randint(-5, 5), value + rng.randint(-8, 8), value + rng.randint(-5, 5)])
colors = []
with tempfile.TemporaryDirectory() as temp:
    folder = pathlib.Path(temp)
    for frame in range(10):
        pixels = bytearray()
        for i, pixel in enumerate(base):
            pixels.extend([112, 110, 105] if i < 64 * 8 else [c + frame for c in pixel])
        source, output = folder / "input.ppm", folder / "output.ppm"
        source.write_bytes(b"P6\n64 48\n255\n" + pixels)
        subprocess.run([sys.argv[1], str(source), str(output), "1.0"], check=True)
        colors.append(tuple(output.read_bytes().split(b"\n", 3)[3][:3]))
print("Fixed input patch RGB: (112, 110, 105)")
print("Output as the background brightens:", colors)
print("Maximum adjacent channel change:", max(abs(colors[i][c] - colors[i-1][c]) for i in range(1, 10) for c in range(3)))
