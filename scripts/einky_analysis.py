"""Reverse-engineer Einky's image processing from one capture.

Usage: python3 -I einky_analysis.py <original.jpg> <einky_400x304.png> <out_dir>

1. Finds the crop of the original that Einky used (search over scale and position, comparing a
   blurred view of Einky's dither with the original in grayscale).
2. Compares local averages of Einky's output (in the inks' measured colors) with the cropped
   original: tone curve (lightness in -> lightness out) and chroma gain.
3. Measures how much fine detail Einky keeps vs. the original (sharpening check).
"""
import sys

import numpy as np
from PIL import Image, ImageOps

INKS = {0: 0x504557, 1: 0xFFFFFF, 2: 0xFFCD68, 3: 0xAF4341, 5: 0x0F5890, 6: 0x434F4B}
PW, PH = 304, 400


def lin(a):
    a = np.asarray(a, float) / 255
    return np.where(a <= 0.04045, a / 12.92, ((a + 0.055) / 1.055) ** 2.4)


def oklab(v):
    m1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
                   [0.0883024619, 0.2817188376, 0.6299787005]])
    m2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
                   [0.0259040371, 0.7827717662, -0.8086757660]])
    return np.cbrt(np.clip(v, 0, None) @ m1.T) @ m2.T


def einky_codes(png):
    """Decoded capture (400x304 buffer render) -> portrait codes (400 rows x 304 cols)."""
    a = np.asarray(Image.open(png).convert("RGB"), int)
    # decode_capture.py renders codes with its own palette; map back by nearest.
    pal = {0: (0, 0, 0), 1: (255, 255, 255), 2: (255, 230, 0), 3: (200, 0, 0), 5: (0, 60, 200), 6: (0, 150, 60)}
    keys = list(pal)
    arr = np.array([pal[k] for k in keys])
    idx = np.argmin(((a[:, :, None, :] - arr[None, None]) ** 2).sum(-1), -1)
    return np.array(keys)[idx].T  # buffer (304 rows y, 400 cols x) -> portrait is its transpose


def blur(img, r):
    from scipy.ndimage import gaussian_filter
    return gaussian_filter(np.asarray(img, float), r, mode="nearest")


def main():
    orig_path, einky_png, out = sys.argv[1:4]
    codes = einky_codes(einky_png)
    ink_lin = np.zeros(codes.shape + (3,))
    for c, h in INKS.items():
        ink_lin[codes == c] = lin([(h >> 16) & 255, (h >> 8) & 255, h & 255])
    einky_gray = blur(ink_lin @ np.array([0.2126, 0.7152, 0.0722]), 2)

    im = ImageOps.exif_transpose(Image.open(orig_path)).convert("RGB")
    small = im.resize((im.width // 4, im.height // 4), Image.Resampling.BOX)
    sg = lin(np.asarray(small)) @ np.array([0.2126, 0.7152, 0.0722])

    def score(x, y, h):
        w = h * PW / PH
        crop = Image.fromarray(sg[int(y):int(y + h), int(x):int(x + w)].astype(np.float32)).resize((PW, PH), Image.Resampling.BILINEAR)
        a = blur(np.asarray(crop), 2).ravel(); b = einky_gray.ravel()
        return np.corrcoef(a, b)[0, 1]

    best = (-1, 0, 0, 0)
    H, W = sg.shape
    for h in np.linspace(H * 0.3, H, 15):
        w = h * PW / PH
        for y in np.linspace(0, H - h, 9):
            for x in np.linspace(0, W - w, 13):
                s = score(x, y, h)
                if s > best[0]:
                    best = (s, x, y, h)
    s, x, y, h = best
    for step in (8, 4, 2, 1):  # local refinement
        improved = True
        while improved:
            improved = False
            for dx, dy, dh in [(step, 0, 0), (-step, 0, 0), (0, step, 0), (0, -step, 0), (0, 0, step), (0, 0, -step)]:
                nx, ny, nh = x + dx, y + dy, h + dh
                if nx < 0 or ny < 0 or ny + nh > H or nx + nh * PW / PH > W or nh < 50:
                    continue
                ns = score(nx, ny, nh)
                if ns > s:
                    s, x, y, h, improved = ns, nx, ny, nh, True
    print(f"crop (in 1/4-scale px): x={x:.0f} y={y:.0f} h={h:.0f}  correlation {s:.3f}")
    full = im.crop((int(x * 4), int(y * 4), int((x + h * PW / PH) * 4), int((y + h) * 4))).resize((PW, PH), Image.Resampling.BOX)
    full.save(f"{out}/original_crop.png")
    np.save(f"{out}/original_crop.npy", np.asarray(full))

    # Tone and chroma: compare 8x8-block means (Einky inks in measured colors vs original).
    o_lin = lin(np.asarray(full))
    def blocks(a, b=8):
        hh, ww = a.shape[0] // b, a.shape[1] // b
        return a[:hh * b, :ww * b].reshape(hh, b, ww, b, -1).mean(axis=(1, 3)).reshape(-1, a.shape[-1])
    ob, eb = oklab(blocks(o_lin)), oklab(blocks(ink_lin))
    print("\nTone curve (original OKLab L -> Einky output L, 8x8 blocks):")
    for lo in np.arange(0.0, 1.0, 0.1):
        sel = (ob[:, 0] >= lo) & (ob[:, 0] < lo + 0.1)
        if sel.sum() >= 5:
            print(f"  L {lo:.1f}-{lo + 0.1:.1f}: n={sel.sum():4d}  out L {eb[sel, 0].mean():.3f}")
    oc, ec = np.hypot(ob[:, 1], ob[:, 2]), np.hypot(eb[:, 1], eb[:, 2])
    print(f"Chroma: original mean {oc.mean():.3f}, Einky mean {ec.mean():.3f} (ratio {ec.mean() / oc.mean():.2f})")
    print("Ink usage (% of pixels):", {k: round(100 * float((codes == c).mean()), 1) for k, c in zip("KWYRBG", [0, 1, 2, 3, 5, 6])})


if __name__ == "__main__":
    main()
