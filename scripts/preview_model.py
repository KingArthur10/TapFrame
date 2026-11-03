"""Fit and validate the frame-simulation preview against RAW photos of known images.

Usage: python3 -I preview_model.py <repo_root>

Preview model (per pixel, then compared on 16x16 block means like panel_model.py):
  1. Ink map in measured ink colors, blurred with a 3x3 [1 2 1] kernel (viewing-distance blending).
  2. m = fraction of the 8 neighbours whose ink differs from the centre pixel (0 = solid area).
  3. In OKLab: L' = L + m * (a + b*L - L), chroma' = chroma * (1 - c*m).
(a, b, c) are found by grid search, leave-one-out over the photos, and compared with the plain preview.
"""
import itertools
import sys

import numpy as np

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import panel_model as pm  # noqa: E402


def oklab_to_linear(lab):
    m = np.array([[1, 0.3963377774, 0.2158037573], [1, -0.1055613458, -0.0638541728], [1, -0.0894841775, -1.2914855480]])
    lms = (lab @ m.T) ** 3
    m2 = np.array([[4.0767416621, -3.3077115913, 0.2309699292],
                   [-1.2684380046, 2.6097574011, -0.3413193965],
                   [-0.0041960863, -0.7034186147, 1.7076147010]])
    return lms @ m2.T


def prepare(codes):
    lin = np.zeros((pm.H, pm.W, 3))
    for c, v in pm.INK_LIN.items():
        lin[codes == c] = v
    pad = np.pad(lin, ((1, 1), (1, 1), (0, 0)), mode="edge")
    k = np.array([1, 2, 1]) / 4
    blur = sum(k[i] * k[j] * pad[i:i + pm.H, j:j + pm.W] for i in range(3) for j in range(3))
    cp = np.pad(codes, 1, mode="edge")
    diff = sum((cp[1 + dy:1 + dy + pm.H, 1 + dx:1 + dx + pm.W] != codes).astype(float)
               for dy in (-1, 0, 1) for dx in (-1, 0, 1) if dy or dx) / 8
    return pm.oklab(blur), diff


def simulate(lab, m, a, b, c):
    lab = lab.copy()
    lab[..., 0] += m * (a + b * lab[..., 0] - lab[..., 0])
    lab[..., 1:] *= (1 - c * m)[..., None]
    return pm.blocks(oklab_to_linear(lab))


def error(d, params):
    pred = simulate(d["lab"], d["m"], *params) if params else pm.blocks(oklab_to_linear(d["lab0"]))
    cal = pm.calibrate(d["meas"], pred)
    return pm.de(cal, pred).mean()


def main():
    root = sys.argv[1]
    s3 = f"{root}/captures/photos_session3"
    data = []
    for m in ("standard", "half_res", "panel_aware"):
        codes = pm.load_codes_from_png(f"{s3}/app/P180715-{m}.png")
        rect = np.load(f"{s3}/rect/{m}.npy")
        lab, mix = prepare(codes)
        lin = np.zeros((pm.H, pm.W, 3))
        for c, v in pm.INK_LIN.items():
            lin[codes == c] = v
        data.append(dict(name=m, meas=pm.blocks(rect.reshape(pm.H, 2, pm.W, 2, 3).mean(axis=(1, 3))),
                         lab=lab, m=mix, lab0=pm.oklab(lin)))
    grid = list(itertools.product(np.arange(0.2, 0.85, 0.05), np.arange(0.0, 0.75, 0.1), np.arange(0.2, 0.95, 0.1)))

    def best(ds):
        return min(grid, key=lambda p: sum(error(d, p) for d in ds))

    print("Block OKLab error (mean), plain preview vs simulated preview (params fitted without that photo):")
    for i, d in enumerate(data):
        p = best([x for j, x in enumerate(data) if j != i])
        print(f"  {d['name']:12} plain {error(d, None):.3f}   simulated {error(d, p):.3f}   (a={p[0]:.2f} b={p[1]:.1f} c={p[2]:.1f})")
    p = best(data)
    print(f"Fitted on all three: a={p[0]:.2f} b={p[1]:.1f} c={p[2]:.1f}")


if __name__ == "__main__":
    main()
