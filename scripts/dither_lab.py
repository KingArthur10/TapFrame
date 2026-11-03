"""Try dithering variants on one photo crop and score them against the original and Einky's output.

Usage: python3 -I dither_lab.py <repo_root>

Uses captures/einky2/original_crop.npy (the crop Einky used, 304x400 sRGB) and Einky's decoded
output. Each variant: panel mapping (black point compensation, chroma limit), optional unsharp mask,
Floyd–Steinberg (serpentine) with a lightness weight in the ink-matching distance. Scores: detail
correlation with the original (band-pass of perceived lightness), tone at mid gray, ink usage.
Writes a sheet of simulated-frame previews to captures/einky2/lab.png.
"""
import sys

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import einky_analysis as ea  # noqa: E402

CODES = list(ea.INKS)
PAL_LIN = np.array([ea.lin([(h >> 16) & 255, (h >> 8) & 255, h & 255]) for h in ea.INKS.values()])
PAL_LAB = ea.oklab(PAL_LIN)
Y = np.array([0.2126, 0.7152, 0.0722])


def linear_to_lab_img(a):
    return ea.oklab(a.reshape(-1, 3)).reshape(a.shape)


def lab_to_linear(lab):
    m = np.array([[1, 0.3963377774, 0.2158037573], [1, -0.1055613458, -0.0638541728], [1, -0.0894841775, -1.2914855480]])
    m2 = np.array([[4.0767416621, -3.3077115913, 0.2309699292], [-1.2684380046, 2.6097574011, -0.3413193965],
                   [-0.0041960863, -0.7034186147, 1.7076147010]])
    return ((lab @ m.T) ** 3) @ m2.T


def prepare(srgb, contrast=0.0, sat=1.0, bright=0.0, sharpen=0.0, chroma_limit=0.12, bpc=True):
    lin = ea.lin(srgb)
    lab = linear_to_lab_img(lin)
    L = lab[..., 0]
    if sharpen:
        L = L + sharpen * (L - gaussian_filter(L, 1.0))
    L = np.clip(L + bright * 4 * L * (1 - L), 0, 1)
    if contrast > 0:
        g = 6 * contrast
        sig = lambda v: 1 / (1 + np.exp(-g * (v - 0.5)))
        L = (sig(L) - sig(0)) / (sig(1) - sig(0))
    lab[..., 0] = np.clip(L, 0, 1)
    lab[..., 1:] *= sat
    lin = np.clip(lab_to_linear(lab), 0, 1)
    if bpc:
        lin = PAL_LIN[0] + (1 - PAL_LIN[0]) * lin
    lab = linear_to_lab_img(lin)
    c = np.hypot(lab[..., 1], lab[..., 2])
    k = np.where(c > 1e-6, chroma_limit * np.tanh(c / chroma_limit) / np.maximum(c, 1e-6), 1)
    lab[..., 1:] *= k[..., None]
    return np.clip(lab_to_linear(lab), 0, 1)


def fs(lin, w_l=1.0):
    """Floyd–Steinberg, serpentine, errors in linear light, nearest ink in OKLab with lightness weight w_l."""
    h, w, _ = lin.shape
    px = lin.copy()
    out = np.zeros((h, w), int)
    wts = np.array([w_l, 1, 1])
    for y in range(h):
        xs = range(w) if y % 2 == 0 else range(w - 1, -1, -1)
        s = 1 if y % 2 == 0 else -1
        for x in xs:
            c = np.clip(px[y, x], 0, 1)
            lab = ea.oklab(c[None])[0]
            i = int(np.argmin((((PAL_LAB - lab) * wts) ** 2).sum(1)))
            out[y, x] = i
            e = c - PAL_LIN[i]
            if 0 <= x + s < w: px[y, x + s] += e * 7 / 16
            if y + 1 < h:
                if 0 <= x - s < w: px[y + 1, x - s] += e * 3 / 16
                px[y + 1, x] += e * 5 / 16
                if 0 <= x + s < w: px[y + 1, x + s] += e * 1 / 16
    return out


def band(lum):
    return gaussian_filter(lum, 1.0) - gaussian_filter(lum, 3.0)


def simulate(idx):
    """FrameSim (see Tuning.kt) on palette indices, returns sRGB uint8 image."""
    codes = np.array(CODES)[idx]
    lin = PAL_LIN[idx]
    k = np.array([0.25, 0.5, 0.25])
    pad = np.pad(lin, ((1, 1), (1, 1), (0, 0)), mode="edge")
    bl = sum(k[i] * k[j] * pad[i:i + lin.shape[0], j:j + lin.shape[1]] for i in range(3) for j in range(3))
    cp = np.pad(codes, 1, mode="edge")
    m = sum((cp[1 + dy:1 + dy + codes.shape[0], 1 + dx:1 + dx + codes.shape[1]] != codes) for dy in (-1, 0, 1) for dx in (-1, 0, 1) if dy or dx) / 8
    lab = linear_to_lab_img(bl)
    lab[..., 0] += m * (0.55 + 0.2 * lab[..., 0] - lab[..., 0])
    lab[..., 1:] *= (1 - 0.2 * m)[..., None]
    v = np.clip(lab_to_linear(lab), 0, 1)
    return (255 * np.where(v <= 0.0031308, 12.92 * v, 1.055 * v ** (1 / 2.4) - 0.055)).astype(np.uint8)


def main():
    root = sys.argv[1]
    src = np.load(f"{root}/captures/einky2/original_crop.npy")
    ref = np.cbrt(ea.lin(src) @ Y)
    einky_codes = ea.einky_codes(f"{root}/captures/einky2/einky_400x304.png")
    einky = np.searchsorted(CODES, einky_codes)

    def score(name, idx):
        l = np.cbrt(PAL_LIN[idx] @ Y)
        corr = np.corrcoef(band(l).ravel(), band(ref).ravel())[0, 1]
        usage = " ".join(f"{k}{100 * (idx == i).mean():.0f}" for i, k in enumerate("KWYRBG"))
        print(f"  {name:38} detail {corr:.3f}  mean L {np.cbrt(PAL_LIN[idx] @ Y).mean():.3f}  inks {usage}")

    print("Variants (detail = correlation of fine detail with the original):")
    score("Einky", einky)
    variants = [
        ("ours: c1.2 s1.2", dict(contrast=1.2, sat=1.2), 1.0),
        ("ours + lightness weight 2", dict(contrast=1.2, sat=1.2), 2.0),
        ("ours + lightness weight 3", dict(contrast=1.2, sat=1.2), 3.0),
        ("c0.6 s1.2 + L3 + sharpen 1.0", dict(contrast=0.6, sat=1.2, sharpen=1.0), 3.0),
        ("c0.6 s1.3 + L3 + sharpen 1.5 bright.1", dict(contrast=0.6, sat=1.3, sharpen=1.5, bright=0.1), 3.0),
        ("no BPC c0.6 s1.2 + L3 + sharpen 1.0", dict(contrast=0.6, sat=1.2, sharpen=1.0, bpc=False), 3.0),
    ]
    results = [("Einky", einky)]
    for name, prep, wl in variants:
        idx = fs(prepare(src, **prep), w_l=wl)
        score(name, idx)
        results.append((name, idx))
    sheet = np.concatenate([simulate(idx) for _, idx in results], axis=1)
    Image.fromarray(sheet).save(f"{root}/captures/einky2/lab.png")


if __name__ == "__main__":
    main()
