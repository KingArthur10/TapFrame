"""Refine panel corners by matching the rectified photo to the expected image, then warp.

Usage: python3 -I register.py <photo.jpg> <pattern.codes> <out_prefix> TL TR BR BL [scale]

Starting from rough corners (e.g. from find_panel.py), Nelder-Mead adjusts the 8 corner
coordinates to maximize the correlation between the rectified photo's lightness and the expected
image's (plain preview, lightly blurred), then writes <out_prefix>.npy/.png like rectify.py.
"""
import sys

import numpy as np
from scipy import ndimage, optimize

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import panel_model as pm  # noqa: E402
import rectify  # noqa: E402


def expected_lightness(codes):
    lin = np.zeros((pm.H, pm.W, 3))
    for c, v in pm.INK_LIN.items():
        lin[codes == c] = v
    return ndimage.gaussian_filter(lin.mean(-1) ** (1 / 3), 0.7)


def score(corners, lin, target):
    got = rectify.warp(lin, corners.reshape(4, 2), 1).mean(-1) ** (1 / 3)
    g = got[4:-4, 4:-4]; t = target[4:-4, 4:-4]
    g = g - g.mean(); t = t - t.mean()
    return -(g * t).sum() / np.sqrt((g * g).sum() * (t * t).sum())


def main():
    photo, codes_path, out = sys.argv[1:4]
    corners = np.array([[float(c) for c in p.split(",")] for p in sys.argv[4:8]])
    scale = int(sys.argv[8]) if len(sys.argv) > 8 else 4
    lin = rectify.load_linear(photo)
    target = expected_lightness(pm.load_codes(codes_path))
    before = score(corners.ravel(), lin, target)
    res = optimize.minimize(score, corners.ravel(), args=(lin, target), method="Nelder-Mead",
                            options={"xatol": 0.05, "fatol": 1e-6, "maxiter": 3000})
    print(f"{photo}: correlation {-before:.3f} -> {-res.fun:.3f}; corners",
          " ".join(f"{x:.1f},{y:.1f}" for x, y in res.x.reshape(4, 2)))
    rect = rectify.warp(lin, res.x.reshape(4, 2), scale)
    np.save(out + ".npy", rect)
    rectify.viewable(rect).save(out + ".png")


if __name__ == "__main__":
    main()
