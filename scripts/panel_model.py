"""Fit and validate a model of how the panel renders neighbouring inks ("boundary bleed").

Usage: python3 -I panel_model.py <repo_root>

Model: the panel shows each pixel's ink color, except that wherever two different inks touch, ink a
grows into ink b by a signed fraction s of a pixel. s is fitted separately for neighbours along
buffer x and along buffer y, for each of the 15 ink pairs (30 numbers). This is linear in s, so it
is fitted by least squares on 16x16-pixel block means of RAW photos whose pixel codes are known.

Each photo also gets its own calibration (per-channel gain that varies linearly across the panel,
plus an offset) to absorb exposure, white balance, flash falloff and glare.

Prints block-level OKLab errors of the plain-ink preview vs the bleed model, including
leave-one-out results for the session-3 photos, and writes the fitted values to
scripts/panel_model.json.
"""
import json
import os
import sys

import numpy as np
from PIL import Image

INKS = {0: 0x504557, 1: 0xFFFFFF, 2: 0xFFCD68, 3: 0xAF4341, 5: 0x0F5890, 6: 0x434F4B}
CODES = list(INKS)
NAMES = dict(zip(CODES, "KWYRBG"))
PAIRS = [(a, b) for i, a in enumerate(CODES) for b in CODES[i + 1:]]
W, H, B = 400, 304, 16


def srgb_to_linear(v):
    v = np.asarray(v, float)
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def hex_linear(h):
    return srgb_to_linear(np.array([(h >> 16) & 255, (h >> 8) & 255, h & 255]) / 255)


INK_LIN = {c: hex_linear(h) for c, h in INKS.items()}


def oklab(v):
    m1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929],
                   [0.2119034982, 0.6806995451, 0.1073969566],
                   [0.0883024619, 0.2817188376, 0.6299787005]])
    m2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468],
                   [1.9779984951, -2.4285922050, 0.4505937099],
                   [0.0259040371, 0.7827717662, -0.8086757660]])
    return np.cbrt(np.clip(v, 0, None) @ m1.T) @ m2.T


def load_codes_from_png(path):
    """Portrait ink-map PNG (304x400, measured colors) -> buffer-order codes (304 rows x 400)."""
    a = np.asarray(Image.open(path).convert("RGB"), int)
    pal = np.array([[(h >> 16) & 255, (h >> 8) & 255, h & 255] for h in INKS.values()])
    idx = np.argmin(((a[:, :, None, :] - pal[None, None]) ** 2).sum(-1), axis=-1)
    return np.array(CODES)[idx].T


def load_codes(path):
    return np.frombuffer(open(path, "rb").read(), np.uint8).reshape(H, W) & 7


def blocks(a):
    """Mean over BxB blocks of an (H, W, ...) array."""
    hb, wb = H // B, W // B
    a = a[: hb * B, : wb * B]
    return a.reshape(hb, B, wb, B, *a.shape[2:]).mean(axis=(1, 3))


def features(codes):
    """Plain-ink block means (hb, wb, 3) and bleed features (hb, wb, 3, 30)."""
    lin = np.zeros((H, W, 3))
    for c, v in INK_LIN.items():
        lin[codes == c] = v
    base = blocks(lin)
    feats = []
    for axis in (1, 0):  # neighbours along buffer x, then along buffer y
        a = codes if axis == 1 else codes.T
        left, right = a[:, :-1], a[:, 1:]
        for p, q in PAIRS:
            hit = ((left == p) & (right == q)) | ((left == q) & (right == p))
            m = np.zeros(a.shape)
            m[:, :-1] += hit  # attribute each boundary to its first pixel
            if axis == 0:
                m = m.T
            feats.append(blocks(m)[..., None] * (INK_LIN[p] - INK_LIN[q]))
    return base, np.stack(feats, axis=-1)


def calibrate(meas, pred):
    """Divide out a smooth lighting field (exposure, white balance, flash falloff).

    Per channel, fits log(meas / pred) with a quadratic surface over the panel, using only blocks
    the model predicts to be reasonably bright (dark blocks are dominated by noise and glare).
    """
    hb, wb = pred.shape[:2]
    v, u = np.meshgrid(np.linspace(-1, 1, hb), np.linspace(-1, 1, wb), indexing="ij")
    basis = np.stack([np.ones_like(u), u, v, u * u, v * v, u * v], -1).reshape(-1, 6)
    out = np.empty_like(meas)
    for k in range(3):
        p, m = pred[..., k].ravel(), meas[..., k].ravel()
        ok = (p > 0.15) & (m > 0)
        coef = np.linalg.lstsq(basis[ok] * p[ok, None], np.log(m[ok] / p[ok]) * p[ok], rcond=None)[0]
        out[..., k] = meas[..., k] / np.exp(basis @ coef).reshape(hb, wb)
    return out


def de(a, b):
    return np.linalg.norm(oklab(a) - oklab(b), axis=-1)


def fit(datasets, iters=3, bound=0.5):
    """Bounded least squares for s (|s| <= bound pixel), alternating with per-photo calibration."""
    from scipy.optimize import lsq_linear

    s = np.zeros(len(PAIRS) * 2)
    for _ in range(iters):
        xs, ys = [], []
        for d in datasets:
            pred = d["base"] + d["feats"] @ s
            d["cal"] = calibrate(d["meas"], pred)
            xs.append(d["feats"].reshape(-1, len(s)))
            ys.append((d["cal"] - d["base"]).reshape(-1))
        x, y = np.concatenate(xs), np.concatenate(ys)
        s = lsq_linear(x, y, bounds=(-bound, bound)).x
    return s


def evaluate(d, s):
    plain = calibrate(d["meas"], d["base"])
    model_pred = d["base"] + d["feats"] @ s
    model = calibrate(d["meas"], model_pred)
    e0, e1 = de(plain, d["base"]), de(model, model_pred)
    return e0.mean(), np.percentile(e0, 90), e1.mean(), np.percentile(e1, 90)


def main():
    root = sys.argv[1]
    pat = f"{root}/tapframe/app/build/patterns"
    s2, s3 = f"{root}/captures/photos_session2/rect", f"{root}/captures/photos_session3"
    specs = [
        ("T1", f"{s2}/T1.npy", load_codes(f"{pat}/T1-palette.codes")),
        ("T4a", f"{s2}/T4a.npy", load_codes(f"{pat}/T4a-mix-checker.codes")),
        ("T4b", f"{s2}/T4b.npy", load_codes(f"{pat}/T4b-mix-stripes.codes")),
        ("T8", f"{s2}/T8.npy", load_codes(f"{pat}/T8-black-shapes.codes")),
    ] + [
        (f"photo:{m}", f"{s3}/rect/{m}.npy", load_codes_from_png(f"{s3}/app/P180715-{m}.png"))
        for m in ("standard", "half_res", "panel_aware")
    ]
    datasets = []
    for name, npy, codes in specs:
        rect = np.load(npy)
        meas = blocks(rect.reshape(H, 2, W, 2, 3).mean(axis=(1, 3)))
        base, feats = features(codes)
        datasets.append(dict(name=name, meas=meas, base=base, feats=feats))

    s = fit(datasets)
    print("All data fitted. Block OKLab error, plain-ink preview vs bleed model (mean / 90th pct):")
    for d in datasets:
        e = evaluate(d, s)
        print(f"  {d['name']:18} plain {e[0]:.3f} / {e[1]:.3f}   model {e[2]:.3f} / {e[3]:.3f}")

    print("\nLeave-one-out on session-3 photos (model fitted without that photo):")
    for i, d in enumerate(datasets):
        if not d["name"].startswith("photo"):
            continue
        s_loo = fit([x for j, x in enumerate(datasets) if j != i])
        e = evaluate(d, s_loo)
        print(f"  {d['name']:18} plain {e[0]:.3f} / {e[1]:.3f}   model {e[2]:.3f} / {e[3]:.3f}")

    print("\nFitted bleed s (fraction of a pixel that the first ink grows into the second):")
    print("  pair   along x   along y")
    n = len(PAIRS)
    for i, (p, q) in enumerate(PAIRS):
        print(f"  {NAMES[p]}{NAMES[q]}    {s[i]:+.3f}    {s[n + i]:+.3f}")
    out = {"pairs": [NAMES[p] + NAMES[q] for p, q in PAIRS], "along_x": s[:n].tolist(), "along_y": s[n:].tolist()}
    with open(os.path.join(os.path.dirname(__file__), "panel_model.json"), "w") as f:
        json.dump(out, f, indent=1)


if __name__ == "__main__":
    main()
