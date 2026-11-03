"""Measure the T9 color charts (session 4) against their targets and the frame-simulation preview.

Usage: python3 -I color_chart.py <rectified.npy> <pattern.codes> [scale]

The photo is normalized to panel white with a smooth lighting field (log-quadratic per channel)
fitted on the centres of the 4 px white gutters, so uneven light cancels out. For each patch the
interior mean is compared with the target color, the plain preview (mean of measured ink colors)
and the frame simulation (scripts/preview_model.py). Errors are OKLab distances.
"""
import sys

import numpy as np

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import panel_model as pm  # noqa: E402
import preview_model as prev  # noqa: E402

CHART = [0x735244, 0xC29682, 0x627A9D, 0x576C43, 0x8580B1, 0x67BDAA,
         0xD67E2C, 0x505BA6, 0xC15A63, 0x5E3C6C, 0x9DBC40, 0xE0A32E,
         0x383D96, 0x469449, 0xAF363C, 0xE7C71F, 0xBB5695, 0x0885A1,
         0xF3F3F2, 0xC8C8C8, 0xA0A0A0, 0x7A7A79, 0x555555, 0x343434,
         0xEBC8B4, 0xA06E55, 0x5A3C2D, 0x96BEE6, 0x3C8C28, 0xF08C96]
NAMES = ["dark skin", "light skin", "blue sky", "foliage", "blue flower", "bluish green",
         "orange", "purplish blue", "moderate red", "purple", "yellow green", "orange yellow",
         "blue", "green", "red", "yellow", "magenta", "cyan",
         "white", "gray 8", "gray 6.5", "gray 5", "gray 3.5", "black",
         "pale skin", "medium skin", "deep skin", "light sky", "grass", "pink"]
PW, PH, GAP = 62, 56, 4


def linear_to_srgb(v):
    return np.where(v <= 0.0031308, 12.92 * v, 1.055 * np.power(v, 1 / 2.4) - 0.055)


def lighting_field(img, mask):
    """Per-channel exp(quadratic(x, y)) fitted to img where mask is set."""
    h, w = mask.shape
    y, x = np.mgrid[0:h, 0:w]
    x = x / w - 0.5; y = y / h - 0.5
    basis = np.stack([np.ones_like(x), x, y, x * x, x * y, y * y], -1)
    field = np.empty_like(img)
    for ch in range(3):
        coef, *_ = np.linalg.lstsq(basis[mask], np.log(np.maximum(img[..., ch][mask], 1e-4)), rcond=None)
        field[..., ch] = np.exp(basis @ coef)
    return field


def patch_slices(i, frac=0.6):
    x0 = GAP + (i % 6) * (PW + GAP); y0 = GAP + (i // 6) * (PH + GAP)
    mx = int(PW * (1 - frac) / 2); my = int(PH * (1 - frac) / 2)
    return slice(y0 + my, y0 + PH - my), slice(x0 + mx, x0 + PW - mx)


def load(npy, codes_path, scale=4):
    raw = np.load(npy)
    img = raw.reshape(pm.H, scale, pm.W, scale, 3).mean(axis=(1, 3))
    codes = pm.load_codes(codes_path)
    y, x = np.mgrid[0:pm.H, 0:pm.W]
    gx = np.isin(x % (PW + GAP), [1, 2]); gy = np.isin(y % (PH + GAP), [1, 2])
    mask = (gx | gy) & (codes == 1) & (x > 2) & (x < pm.W - 3) & (y > 2) & (y < pm.H - 3)
    return img / lighting_field(img, mask), codes


def main():
    npy, codes_path = sys.argv[1], sys.argv[2]
    scale = int(sys.argv[3]) if len(sys.argv) > 3 else 4
    img, codes = load(npy, codes_path, scale)
    lab0, m = prev.prepare(codes)
    plain = prev.oklab_to_linear(lab0)
    lab = lab0.copy()
    lab[..., 0] += m * (0.55 + 0.2 * lab[..., 0] - lab[..., 0])
    lab[..., 1:] *= (1 - 0.2 * m)[..., None]
    sim = prev.oklab_to_linear(lab)
    hexc = lambda v: "#%02X%02X%02X" % tuple(int(round(c)) for c in np.clip(linear_to_srgb(np.clip(v, 0, 1)) * 255, 0, 255))
    rows = []
    print(f"{'patch':14} {'target':8} {'photo':8} {'sim':8} {'plain':8}  dE(photo,target) dE(photo,sim) dL(photo-sim) dC")
    for i, t in enumerate(CHART):
        sl = patch_slices(i)
        meas = img[sl].reshape(-1, 3).mean(0)
        s = sim[sl].reshape(-1, 3).mean(0); p = plain[sl].reshape(-1, 3).mean(0)
        tgt = pm.hex_linear(t)
        lm, ls, lt = pm.oklab(meas), pm.oklab(s), pm.oklab(tgt)
        cm, cs = np.hypot(*lm[1:]), np.hypot(*ls[1:])
        rows.append((np.linalg.norm(lm - lt), np.linalg.norm(lm - ls), lm[0] - ls[0], cm - cs))
        print(f"{NAMES[i]:14} {hexc(tgt)} {hexc(meas)} {hexc(s)} {hexc(p)}  {rows[-1][0]:.3f}  {rows[-1][1]:.3f}  {rows[-1][2]:+.3f}  {rows[-1][3]:+.3f}")
    r = np.array(rows)
    print(f"mean: dE(photo,target) {r[:, 0].mean():.3f}  dE(photo,sim) {r[:, 1].mean():.3f}  dL {r[:, 2].mean():+.3f}  dC {r[:, 3].mean():+.3f}")


if __name__ == "__main__":
    main()
