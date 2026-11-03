"""Find the panel's active-area corners in a photo of the red-bezel Tapmora.

Usage: find_panel.py [--strict] <photo.jpg>...   prints "TLx,TLy TRx,TRy BRx,BRy BLx,BLy" per photo, in the
preview coordinates rectify.py uses (longest side 1000 px).
"""
import sys

import numpy as np
from PIL import Image, ImageOps
from scipy import ndimage


def corners(path, ratio=1.6):
    im = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    s = 1000 / max(im.size)
    im = im.resize((round(im.width * s), round(im.height * s)))
    a = np.asarray(im, np.float32)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    red = (r > 120) & (r > ratio * g) & (r > ratio * b)
    red = ndimage.binary_opening(red, iterations=2)
    # The bezel is the largest red blob; the panel is the largest hole inside it.
    lab, n = ndimage.label(red)
    bezel = lab == (np.argmax(np.bincount(lab.ravel())[1:]) + 1)
    holes = ndimage.binary_fill_holes(bezel) & ~bezel
    lab, n = ndimage.label(ndimage.binary_opening(holes, iterations=3))
    panel = lab == (np.argmax(np.bincount(lab.ravel())[1:]) + 1)
    ys, xs = np.nonzero(panel)
    pts = np.stack([xs, ys], 1).astype(float)
    tl = pts[np.argmin(xs + ys)]; br = pts[np.argmax(xs + ys)]
    tr = pts[np.argmax(xs - ys)]; bl = pts[np.argmin(xs - ys)]
    return [tl, tr, br, bl]


if __name__ == "__main__":
    # --strict: for photos where a red ink swatch touches the bezel (e.g. T1), use a stricter red
    # test that separates the more saturated bezel from the red ink.
    ratio = 2.1 if "--strict" in sys.argv else 1.6
    for p in [a for a in sys.argv[1:] if a != "--strict"]:
        print(p, " ".join(f"{x:.0f},{y:.0f}" for x, y in corners(p, ratio)))
