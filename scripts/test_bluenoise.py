"""Test for scripts/bluenoise.py. Usage: tools/pyenv/bin/python -I scripts/test_bluenoise.py"""
import os
import subprocess
import sys
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
N = 64


def low_freq_fraction(mask):
    """Share of spectral power (excluding DC) at radius < 8 cycles for a 0/1 pattern."""
    f = np.abs(np.fft.fft2(mask - mask.mean())) ** 2
    fy, fx = np.meshgrid(np.fft.fftfreq(N) * N, np.fft.fftfreq(N) * N, indexing="ij")
    r = np.hypot(fx, fy)
    return f[(r > 0) & (r < 8)].sum() / f[r > 0].sum()


def main():
    out = os.path.join(tempfile.mkdtemp(), "bn.bin")
    subprocess.run([sys.executable, "-I", os.path.join(HERE, "bluenoise.py"), out], check=True, timeout=600)
    data = open(out, "rb").read()
    assert len(data) == N * N * 2, f"expected {N * N * 2} bytes, got {len(data)}"
    ranks = np.frombuffer(data, "<u2").reshape(N, N)
    assert sorted(ranks.ravel().tolist()) == list(range(N * N)), "ranks must be a permutation of 0..4095"
    rng = np.random.default_rng(0)
    white = rng.permutation(N * N).reshape(N, N)
    for level in (0.1, 0.25, 0.5, 0.75):
        k = int(level * N * N)
        bn, wn = low_freq_fraction((ranks < k).astype(float)), low_freq_fraction((white < k).astype(float))
        print(f"level {level:.2f}: low-frequency share blue {bn:.3f} vs white {wn:.3f}")
        assert bn < 0.3 * wn, f"not blue noise at level {level}"
    print("OK")


if __name__ == "__main__":
    main()
