"""Measure ink colors, pair mixes and black-dot shapes from rectified photos.

Usage: python3 -I measure.py <rect_dir>

Reads whichever of T1, T4a, T4b, T7, T8 exist in <rect_dir> as <name>.npy (linear, from
rectify.py warp, scale 2). All colors are normalized per channel to the panel's own white
measured in the same photo, which cancels exposure and white balance differences.
"""
import os
import sys

import numpy as np

S = 2  # rectify scale
INKS = "KWYRBG"


def linear_to_srgb(v):
    v = np.clip(v, 0, 1)
    return np.round(255 * np.where(v <= 0.0031308, 12.92 * v, 1.055 * v ** (1 / 2.4) - 0.055)).astype(int)


def oklab(v):
    m1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929],
                   [0.2119034982, 0.6806995451, 0.1073969566],
                   [0.0883024619, 0.2817188376, 0.6299787005]])
    m2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468],
                   [1.9779984951, -2.4285922050, 0.4505937099],
                   [0.0259040371, 0.7827717662, -0.8086757660]])
    return m2 @ np.cbrt(m1 @ np.clip(v, 0, None))


def de(a, b):
    return float(np.linalg.norm(oklab(a) - oklab(b)))


def hexc(v):
    r, g, b = linear_to_srgb(v)
    return f"#{r:02X}{g:02X}{b:02X}"


class Rect:
    def __init__(self, path):
        self.a = np.load(path)

    def region(self, x, y, w, h, frac=0.6):
        """Mean linear color of the central `frac` of a buffer-coordinate rectangle."""
        mx, my = w * (1 - frac) / 2, h * (1 - frac) / 2
        x0, x1 = int((x + mx) * S), int((x + w - mx) * S)
        y0, y1 = int((y + my) * S), int((y + h - my) * S)
        return self.a[y0:y1, x0:x1].reshape(-1, 3).mean(axis=0)


def palette_t1(r):
    sw = {k: r.region(4 + (i % 3) * 132, 4 + (i // 3) * 150, 128, 146) for i, k in enumerate(INKS)}
    return {k: v / sw["W"] for k, v in sw.items()}


def codes_t7(r):
    codes = [r.region((c % 4) * 100 + 3, (c // 4) * 76 + 3, 94, 70) for c in range(16)]
    white = codes[1]
    print("T7 codes, normalized to code 1 (white):")
    for c in range(16):
        print(f"  code {c:X}: {hexc(codes[c] / white)}")
    return {k: codes[c] / white for k, c in zip(INKS, [0, 1, 2, 3, 5, 6])}


def mixes(name, r, stripes):
    tile = lambda i, j: r.region(16 + 64 * j, 16 + 48 * i, 64, 48)
    w = tile(1, 1)
    solid = {INKS[i]: tile(i, i) / w for i in range(6)}
    print(f"\n{name} ({'stripes: H above / V below' if stripes else 'checkers: 1px above / 2x2 below'})")
    print("  pair  predicted  upper     lower     dE(up,pred) dE(low,pred) dE(up,low)")
    for i in range(6):
        for j in range(i + 1, 6):
            pred = (solid[INKS[i]] + solid[INKS[j]]) / 2
            up, low = tile(i, j) / w, tile(j, i) / w
            print(f"  {INKS[i]}{INKS[j]}    {hexc(pred)}  {hexc(up)}  {hexc(low)}  "
                  f"{de(up, pred):10.3f} {de(low, pred):11.3f} {de(up, low):10.3f}")
    return solid


def shapes_t8(r):
    tile = lambda i, j: r.region(16 + 64 * j, 16 + 57 * i, 64, 57)
    white = tile(0, 0)
    print("\nT8 black dots at 25% coverage (normalized to W solid). dE vs predicted 0.75*bg + 0.25*K:")
    print("  bg  solid     pred      1x1             2x1 (H)         1x2 (V)         2x2")
    for i, bg in enumerate("WYRBG"):
        solid, black = tile(i, 0) / white, tile(i, 5) / white
        pred = 0.75 * solid + 0.25 * black
        cells = [tile(i, j) / white for j in range(1, 5)]
        print(f"  {bg}   {hexc(solid)}  {hexc(pred)}  " + "  ".join(f"{hexc(c)} {de(c, pred):5.3f}" for c in cells))


def main():
    d = sys.argv[1]
    have = lambda n: os.path.exists(f"{d}/{n}.npy")
    palettes = {}
    if have("T1"):
        palettes["T1"] = palette_t1(Rect(f"{d}/T1.npy"))
    if have("T7"):
        palettes["T7"] = codes_t7(Rect(f"{d}/T7.npy"))
    for name, stripes in [("T4a", False), ("T4b", True)]:
        if have(name):
            palettes[name] = mixes(name, Rect(f"{d}/{name}.npy"), stripes)
    if have("T8"):
        shapes_t8(Rect(f"{d}/T8.npy"))
    if palettes:
        print("\nInk colors (white-normalized, sRGB hex) per photo:")
        print("  ink  " + "  ".join(f"{n:8}" for n in palettes))
        for k in INKS:
            print(f"  {k}    " + "  ".join(f"{hexc(p[k]):8}" for p in palettes.values()))
        print("  mean (linear average):  " + "  ".join(
            f"{k}={hexc(np.mean([p[k] for p in palettes.values()], axis=0))}" for k in INKS))


if __name__ == "__main__":
    main()
