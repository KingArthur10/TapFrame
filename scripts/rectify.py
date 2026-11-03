"""Warp a photo of the Tapmora panel back onto the 400x304 panel buffer grid.

Usage:
  rectify.py preview <photo.(jpg|dng)> <out.png> [cw|ccw]
      Writes a viewable copy (longest side 1000 px) for picking corners.
  rectify.py warp <photo.(jpg|dng)> <out_prefix> TLx,TLy TRx,TRy BRx,BRy BLx,BLy [scale] [cw|ccw]
      Writes <out_prefix>.npy (linear-light float32 RGB, buffer orientation) and
      <out_prefix>.png (viewable preview).

Corners are the panel's active-area corners as seen in the preview (card upright, logo at the
bottom); pass cw/ccw to rotate a sideways photo upright first. Because the panel shows the buffer transposed, photo-left/right is buffer y and
photo-top/bottom is buffer x. Output is 400 wide x 304 tall, times [scale] (default 2).

DNG input needs rawpy (use tools/pyenv/bin/python -I). DNGs are decoded to linear sRGB with the
camera's white balance and no tone curve or auto-brightening.
"""
import sys

import numpy as np
from PIL import Image, ImageOps

W, H = 400, 304


def srgb_to_linear(a):
    return np.where(a <= 0.04045, a / 12.92, ((a + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(a):
    a = np.clip(a, 0, 1)
    return np.where(a <= 0.0031308, 12.92 * a, 1.055 * a ** (1 / 2.4) - 0.055)


def load_linear(path):
    """Photo as an HxWx3 float array of linear-light sRGB, upright."""
    if path.lower().endswith(".dng"):
        import rawpy

        with rawpy.imread(path) as raw:
            rgb = raw.postprocess(gamma=(1, 1), no_auto_bright=True, output_bps=16,
                                  use_camera_wb=True, output_color=rawpy.ColorSpace.sRGB)
        return rgb.astype(np.float32) / 65535
    im = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    return srgb_to_linear(np.asarray(im, np.float32) / 255)


def viewable(lin):
    """8-bit sRGB preview, exposed so the 99.5th percentile maps to white."""
    return Image.fromarray((linear_to_srgb(lin / max(np.percentile(lin, 99.5), 1e-6)) * 255).astype(np.uint8))


def homography(src, dst):
    """3x3 matrix mapping src points to dst points (4 pairs)."""
    a = []
    for (x, y), (u, v) in zip(src, dst):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y, -u])
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y, -v])
    _, _, vt = np.linalg.svd(np.array(a, dtype=float))
    return vt[-1].reshape(3, 3) / vt[-1][-1]


def warp(lin, corners, scale):
    k = max(lin.shape[:2]) / 1000
    tl, tr, br, bl = [(x * k, y * k) for x, y in corners]
    w, h = W * scale, H * scale
    # photo TL = buffer (0,0); TR = buffer (0, H); BR = (W, H); BL = (W, 0)
    m = homography([(0, 0), (0, h), (w, h), (w, 0)], [tl, tr, br, bl])
    u, v = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    p = m @ np.stack([u.ravel(), v.ravel(), np.ones(u.size)])
    sx, sy = p[0] / p[2] - 0.5, p[1] / p[2] - 0.5
    x0 = np.clip(np.floor(sx).astype(int), 0, lin.shape[1] - 2)
    y0 = np.clip(np.floor(sy).astype(int), 0, lin.shape[0] - 2)
    fx, fy = (sx - x0)[:, None], (sy - y0)[:, None]
    out = (lin[y0, x0] * (1 - fx) * (1 - fy) + lin[y0, x0 + 1] * fx * (1 - fy)
           + lin[y0 + 1, x0] * (1 - fx) * fy + lin[y0 + 1, x0 + 1] * fx * fy)
    return out.reshape(h, w, 3).astype(np.float32)


def main():
    args = sys.argv[1:]
    turns = {"cw": -1, "ccw": 1}
    rot = next((turns[a] for a in args if a in turns), 0)
    args = [a for a in args if a not in turns]
    mode, photo, out = args[0], args[1], args[2]
    lin = np.rot90(load_linear(photo), rot)
    if mode == "preview":
        im = viewable(lin)
        im.thumbnail((1000, 1000))
        im.save(out)
        return
    corners = [tuple(float(c) for c in p.split(",")) for p in args[3:7]]
    scale = int(args[7]) if len(args) > 7 else 2
    rect = warp(lin, corners, scale)
    np.save(out + ".npy", rect)
    viewable(rect).save(out + ".png")


if __name__ == "__main__":
    main()
