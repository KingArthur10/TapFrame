"""Generate a 64x64 tileable blue-noise threshold matrix (Ulichney's void-and-cluster, 1993).

Usage: python3 -I bluenoise.py <out.bin>

Writes 4096 little-endian uint16 ranks (row-major), a permutation of 0..4095: cell r turns on at
threshold level r/4096. Used by the app's blue-noise ordered dithering.
"""
import sys

import numpy as np

N = 64
SIGMA = 1.5


def kernel():
    d = np.minimum(np.arange(N), N - np.arange(N))
    return np.exp(-(d[:, None] ** 2 + d[None, :] ** 2) / (2 * SIGMA ** 2))


class Energy:
    """Toroidal Gaussian energy of a binary pattern, updated incrementally."""

    def __init__(self, pattern, k):
        self.k = k
        self.e = np.real(np.fft.ifft2(np.fft.fft2(pattern.astype(float)) * np.fft.fft2(k)))

    def toggle(self, y, x, sign):
        self.e += sign * np.roll(np.roll(self.k, y, axis=0), x, axis=1)


def tightest_cluster(pattern, energy):
    return np.unravel_index(np.argmax(np.where(pattern, energy.e, -np.inf)), pattern.shape)


def largest_void(pattern, energy):
    return np.unravel_index(np.argmin(np.where(pattern, np.inf, energy.e)), pattern.shape)


def main():
    k = kernel()
    rng = np.random.default_rng(12345)
    pattern = np.zeros((N, N), bool)
    pattern.flat[rng.choice(N * N, size=N * N // 10, replace=False)] = True

    # Initial binary pattern: move the tightest cluster into the largest void until stable.
    energy = Energy(pattern, k)
    while True:
        cy, cx = tightest_cluster(pattern, energy)
        pattern[cy, cx] = False
        energy.toggle(cy, cx, -1)
        vy, vx = largest_void(pattern, energy)
        if (vy, vx) == (cy, cx):
            pattern[cy, cx] = True
            energy.toggle(cy, cx, +1)
            break
        pattern[vy, vx] = True
        energy.toggle(vy, vx, +1)

    ranks = np.zeros((N, N), int)
    initial = pattern.copy()
    ones = int(initial.sum())

    # Phase 1: remove tightest clusters from the initial pattern, ranking downward.
    p, e = initial.copy(), Energy(initial, k)
    for r in range(ones - 1, -1, -1):
        y, x = tightest_cluster(p, e)
        p[y, x] = False
        e.toggle(y, x, -1)
        ranks[y, x] = r

    # Phase 2: fill the largest voids up to half, ranking upward.
    p, e = initial.copy(), Energy(initial, k)
    for r in range(ones, N * N // 2):
        y, x = largest_void(p, e)
        p[y, x] = True
        e.toggle(y, x, +1)
        ranks[y, x] = r

    # Phase 3: the remaining zeros are the minority; rank the tightest clusters of zeros first,
    # using the energy of the inverted pattern.
    inv = Energy(~p, k)
    for r in range(N * N // 2, N * N):
        y, x = tightest_cluster(~p, inv)
        p[y, x] = True
        inv.toggle(y, x, -1)
        ranks[y, x] = r

    assert sorted(ranks.ravel().tolist()) == list(range(N * N))
    with open(sys.argv[1], "wb") as f:
        f.write(ranks.astype("<u2").tobytes())


if __name__ == "__main__":
    main()
