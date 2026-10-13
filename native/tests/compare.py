"""Compare a real desktop CLI invocation with the adapter using identical P6 input."""
import pathlib
import random
import subprocess
import sys
import tempfile

with tempfile.TemporaryDirectory() as temp:
    folder = pathlib.Path(temp)
    rng = random.Random(271828)
    for width, height in [(1, 1), (17, 11), (640, 480)]:
        pixels = rng.randbytes(width * height * 3)
        image = folder / "input.ppm"
        image.write_bytes(f"P6\n{width} {height}\n255\n".encode() + pixels)
        desktop = folder / "desktop.ppm"
        adapter = folder / "adapter.ppm"
        subprocess.run([sys.argv[1], str(image), str(desktop), "1.0"], check=True)
        subprocess.run([sys.argv[2], str(image), str(adapter)], check=True)
        assert desktop.read_bytes() == adapter.read_bytes(), "RGB output mismatch"
        print(f"PASS: desktop vs adapter {width}x{height}: byte-exact (tolerance 0)")
