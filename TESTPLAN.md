# Tapmora panel characterization — test plan

Goal: measure what the panel actually shows, so the photo app can dither against real colors
and avoid patterns the panel renders badly. Each test sends a pattern from the **TapFrame Test**
app, you photograph the result, and Claude compares the photo with the expected image in
`testplan/expected/<pattern-id>.png` (`@2x` versions are easier to inspect).

Photos are matched to patterns by time: the app logs `SEND <id>` / `DONE <id>`, and phone photos
are pulled with adb afterwards. **Take exactly one photo per send, right after "Done" appears.**
If a shot is bad, just send the pattern again and re-shoot.

## Photo setup (keep identical for every shot)

1. **Light:** flash forced **on**; dim the room lights so the flash dominates. Same room, same time.
2. **Reference:** a sheet of plain white printer paper beside the frame, in the same plane, in
   every shot. It lets Claude correct exposure and white balance between photos.
3. **Position:** frame flat on a table (or fixed to the wall). Phone about **35–40 cm** away,
   tilted **10–15°** off square so the flash's reflection lands outside the panel. Mark the spot
   (e.g. a stack of books) so every shot is taken from the same place.
4. **Camera:** 1× lens, no zoom, no filters, Night Sight off. If available, turn on
   **RAW + JPEG** (Camera settings → Advanced): the DNG is unprocessed and best for color work.
5. Frame should fill about half the photo, with the paper fully visible.

Before you start: open TapFrame Test, keep the phone unlocked, and keep USB connected so the log is
captured.

## Session A — palette, uniformity, orientation (≈ 10 sends)

| Step | Pattern | Purpose | Look for in person |
|---|---|---|---|
| A1 | T6 Solid white | Baseline white; panel uniformity | Even? Darker corners or blotches? |
| A2 | T6 Solid black | Darkest black | Any gray haze or speckles? |
| A3 | T6 Solid white | **Ghosting** black→white | Any faint trace of the black screen? |
| A4 | T6 Solid yellow | Yellow ink color | Even? |
| A5 | T6 Solid red | Red ink color | Even? |
| A6 | T6 Solid blue | Blue ink color | Even? Distinct from black? |
| A7 | T6 Solid green | Green ink color | Even? |
| A8 | T1 Palette swatches | All six inks side by side | Any pair hard to tell apart? |
| A9 | T2 Geometry and edges | Orientation, cropping, pixel aspect | Which physical corner shows **TL**? Is the outer black border visible on all 4 sides? Is the circle round? |

## Session B — fine detail and ink mixing (3 sends)

| Step | Pattern | Purpose | Look for in person |
|---|---|---|---|
| B1 | T3 Fine detail | Single pixels / 1 px lines per ink on white and on black | Which inks lose isolated pixels or thin lines? Do lines bleed or look thicker in one direction? |
| B2 | T4a Pair mixes: checkers | 15 ink pairs, 1 px checker (above diagonal) vs 2×2 checker (below) | At arm's length, which mixes look like smooth colors and which look muddy or grainy? Do the 1 px and 2×2 versions of a pair look the same color? |
| B3 | T4b Pair mixes: stripes | Same pairs as horizontal (above) vs vertical (below) 1 px stripes | Do horizontal and vertical stripes of the same pair look different (direction-dependent bleed)? |

## Session C — dithering (1 send)

| Step | Pattern | Purpose | Look for in person |
|---|---|---|---|
| C1 | T5 Dither ramps | Gray and hue ramps: N = no dither, FS = Floyd–Steinberg, AT = Atkinson, BA = Bayer | Which rows look smoothest and least noisy from 1 m away? Any visible worm or grid artifacts? |

These ramps use the guessed palette. They are re-run after Session A gives measured colors.

## Session D — ghosting (2 sends)

| Step | Pattern | Purpose | Look for in person |
|---|---|---|---|
| D1 | T6 Big checker | 50 px black/white checker | Clean edges? |
| D2 | T6 Big checker inverted | Inverse of D1 | Any faint grid left over from D1? |

## Session E — undocumented pixel codes (1 send)

| Step | Pattern | Purpose | Look for in person |
|---|---|---|---|
| E1 | T7 All 16 pixel codes | What codes 4 and 7–F render as (tiles labelled 0–F) | Describe each of tiles 4, 7, 8, 9, A, B, C, D, E, F. Any new colors, or repeats of known inks? |

## Results

Fill in what you see in person; Claude adds measurements from the photos.

| Step | In-person notes | Photo measurements (Claude) |
|---|---|---|
| A1 | | |
| A2 | | Black is a dark indigo, not neutral (white-normalized #413856–#5A4960 in JPEGs). Uniform apart from a few specks. |
| A3 | | |
| A4 | | |
| A5 | | |
| A6 | | |
| A7 | | |
| A8 | | 2026-11-02 photos (bezel-referenced, preliminary): K #211E2D–#494655, W #E0F2FA–#FBFFFF, Y #F1C53B–#FACF60, R #9A1C24–#AC3138, B #005291–#025AAD, G #3E504D–#495C59. Green is dark and nearly neutral. Needs paper-referenced repeat. |
| A9 | | Panel shows the buffer **transposed** (text mirrored): buffer x runs along the card's long side, buffer y across. Upright portrait image: `panel(x, y) = image(col = y, row = x)`, image 304×400. All four edges visible; circle round, so pixels are square. |
| B1 | | Isolated pixels and 1 px lines render for every ink on white and on black. Low contrast: yellow on white (faint) and green on black (barely visible). No obvious bleed at JPEG resolution. |
| B2 | | Mixes among Y/R/B/G and K+W match the linear average (dE ≤ 0.08). **Black next to a color at 1 px is far too light** (K+R 1 px checker reads pale pink, dE 0.28; K+B 0.18, K+G 0.17, K+Y 0.13), while the 2×2 versions are slightly darker than predicted. W+color 2×2 also darker than predicted (dE ≤ 0.14). |
| B3 | | Direction matters: horizontal 1 px stripes (alternating buffer rows) of black + color are much too light (K+R dE 0.29, K+B 0.25, K+G 0.22); vertical stripes (alternating buffer columns) are slightly too dark. Same pattern as B2. Needs RAW confirmation. |
| C1 | | Gray ramps come out purple/pink-tinted (the guessed palette assumes neutral black; real black is indigo). Hue ramps show large solid regions where the guessed palette is far from the real inks. Re-run with the measured palette. |
| D1 | | |
| D2 | | |
| E1 | | Only 6 inks: the controller uses the low 3 bits; 4≡5 (blue), 7≡6 (green), and 8–F repeat 0–7. Brightness differences between 0–7 and 8–F follow the lighting falloff across the photo. |

## What the results feed into

- **A1–A8** → measured palette (sRGB of each ink relative to the paper white) → `Palette` in `render/Dither.kt`.
- **A9** → photo rotation and any edge margin to avoid.
- **B1–B3** → whether to allow single-pixel dithering for every ink pair, or restrict/penalize pairs that bleed.
- **B2** measured mixes vs. predicted averages → whether to diffuse error in linear light or with a measured mixing model.
- **C1** → default dithering method.
- **A3, D2** → whether to send a clearing image before each photo.
- **E1** → any extra inks or modes to exploit.

## Notes from session 1 (2026-11-02)

- JPEG photos are not reliable for absolute color: the same ink, normalized to the panel white in
  the same photo, varies a lot between shots (green #2F4342–#578373) because HDR+ tone-maps each
  photo differently. Hues and relative comparisons within one photo are usable. Use RAW (DNG).
- Skipped: A1, A3–A7 (solids), D1–D2 (ghosting). A2 done.
- Measurement tools: `scripts/rectify.py` (perspective-correct a photo onto the buffer grid) and
  `scripts/measure.py` (ink colors and pair mixes, normalized to panel white).

## Session 2 — RAW palette and black-dot shapes (4 sends)

Camera: **RAW + JPEG on** (Pixel Camera → Settings → Advanced → RAW/JPEG control, then pick
RAW+JPEG in the camera's Pro controls). Flash forced on, same position as before.

| Step | Pattern | Purpose | Look for in person |
|---|---|---|---|
| S2-1 | T1 Palette swatches | Absolute ink colors from RAW | Does black look neutral to the eye? |
| S2-2 | T4a Pair mixes: checkers | Confirm black-next-to-color effect without HDR+ | At arm's length, do the fine black+red checker tiles look pink rather than dark red? |
| S2-3 | T4b Pair mixes: stripes | Confirm direction dependence | Do horizontal and vertical K+color stripes look different? |
| S2-4 | T8 Black dot shapes on colors | Which black dot shapes (1×1, 2×1, 1×2, 2×2 at 25%) render as the predicted mix | Which columns look closest to a darker version of the background? |

| Step | In-person notes | Photo measurements (Claude) |
|---|---|---|
| S2-1 | | RAW, white-normalized: K #4A4052, W #FFFFFF, Y #FAC35E, R #923736, B #0C538B, G #45504C (red swatch darker than in T4a/T4b, likely uneven flash). Mean across T1/T4a/T4b now in `Ink`: K #504557, Y #FFCD68, R #AF4341, B #0F5890, G #434F4B. Black is ~8% of white with a faint violet tint; green is a dark near-neutral. |
| S2-2 | | Confirmed in RAW. 1 px checker: K+color too light (K+R dE 0.20, K+B 0.16, K+G 0.15, K+Y 0.12); W+color and color+color accurate. 2×2 checker: K+color accurate (≤ 0.07) but W+color too dark (W+G 0.22, W+B 0.18, W+R 0.14) and K+W too dark (0.16). |
| S2-3 | | Same as S2-2: horizontal stripes (alternating rows) behave like the 1 px checker, vertical stripes (alternating columns) like the 2×2 checker. Dark pixels isolated along buffer y render undersized; dark pixels touching along y spread. |
| S2-4 | | 25% black dots: 2×2 matches the linear prediction best on every background (dE 0.007–0.03), 1×1 next (0.015–0.034); 2×1 and 1×2 too light on W (0.09/0.07) and Y (0.06/0.05). |

## Session 3 — photo dithering comparison

In TapFrame Test tap **Pick photo…** and choose a photo. The app fits it to the card (portrait,
center crop), maps it into the panel's color range, and adds three targets:

1. **Standard**: Floyd–Steinberg (serpentine) against the six measured inks.
2. **Half-res 2×2**: dithered at 200×152 and drawn as 2×2 blocks (the most predictable dot shape in T8).
3. **Panel-aware**: Floyd–Steinberg against the six inks plus the 15 measured 1 px two-ink checkerboards,
   so the dither accounts for how this panel actually blends neighbouring pixels. The on-phone preview
   cannot show this blending; judge it on the frame.

Send each method and take one RAW+JPEG photo after each, same setup as session 2. Then rank the three
by eye at normal viewing distance (about 40 cm) and note anything that bothers you (noise, color
casts, banding, skin tones). Two photos are ideal: one with faces/skin, one with strong colors or sky.
The app saves the fitted source, the mapped source and each method's ink map as PNGs in
`/sdcard/Android/data/dev.tapframe.test/files/photos/` for comparison.

| Photo | Method | Your ranking / notes | Measurements (Claude) |
|---|---|---|---|
| 1 | Standard | Best of the three (user) | Faces and features intact on the panel, but flat: low contrast, blacks read gray-violet, colors muted. |
| 1 | Half-res 2×2 | Horrible with people (user) | 200×152 effective resolution leaves faces ~15 blocks wide; features dissolve. Dropped. |
| 1 | Panel-aware | Horrible with people (user) | Blotchy, brown-yellow structured noise on the panel. Measured checker mixes only hold when neighbours pick the same checker, which photos never do. Dropped. |
| 2 | Standard | | |
| 2 | Half-res 2×2 | | |
| 2 | Panel-aware | | |

Session 3 bug: a 2.5 s pause before the first refresh poll let Android's background presence check
run mid-refresh; the busy panel failed it, Android re-discovered the tag, and the app's handle
became invalid ("Tag … is out of date", three times). Reverted to Einky's 100 ms polling from the
start, and reader mode now uses a 1 s presence-check delay.

## Preview accuracy analysis (session-3 RAW photos, 2026-11-02)

Method: `scripts/panel_model.py` compares 16×16-pixel block means of each rectified RAW photo with the
ink map that was sent, after dividing out a smooth per-channel lighting field (quadratic in log
space, fitted on bright blocks).

- Plain preview (each pixel shown in its measured ink color): mean block error 0.058 (standard),
  0.057 (half-res), 0.096 (panel-aware) in OKLab. Solid swatches (T1) show 0.077, so part of this is
  measurement noise (flash falloff, glare).
- A 30-parameter boundary-bleed model gave little or no improvement under leave-one-out (best: 0.058 →
  0.048 on the standard photo). Not worth the complexity.
- The remaining error is systematic. Panel minus preview, by preview lightness: L 0.4–0.5 +0.119,
  0.5–0.6 +0.061, 0.6–0.7 +0.019, 0.7–0.8 +0.003, 0.8–0.9 −0.035, 0.9–1.0 −0.025. Chroma is about 18%
  lower on the panel (0.028 vs 0.034). In other words, dithered dark areas come out much lighter
  (light dots spread inside dark regions) and the panel compresses contrast and saturation.
- Consequences: (1) the preview can apply this measured lightness/chroma compression (plus a slight
  blur for viewing distance); (2) the pipeline can pre-compensate by aiming darker and more saturated.

### Frame-simulation preview (`scripts/preview_model.py`)

Per pixel: blur the ink map with a 3×3 [1 2 1] kernel; let m = fraction of the 8 neighbours with a
different ink; in OKLab set L' = L + m·(0.55 + 0.2·L − L) and chroma' = chroma·(1 − 0.2·m).
Fitted on all seven RAW photos; leave-one-out block errors (plain → simulated): photos 0.058 → 0.026,
0.057 → 0.028, 0.096 → 0.034; patterns T1 0.077 → 0.075, T4a 0.091 → 0.076, T4b 0.067 → 0.061,
T8 0.064 → 0.052. Fitting on photos alone gave a stronger desaturation (c = 0.8) that made the
regular checker/stripe patterns worse, so all seven are used.

## Einky comparison and local tone mapping (2026-11-02)

Einky's upload for photo PXL_20260927_210136951 was captured and decoded (`captures/einky2/`,
`scripts/einky_analysis.py`, `scripts/dither_lab.py`):
- Crop: plain center crop at full height (same as ours).
- Ink usage K18 W37 Y12 R24 B6 G3 (ours, faithful mapping: K10 W27 Y18 R23 B19 G2). Its usage pattern
  matches Floyd–Steinberg in plain sRGB against idealized pure primaries; the closest pre-dither curve
  found was brightness +0.04, contrast 0.6, saturation ×1.4 (K15 W36 Y12 R21 B11 G4).
- Tone curve (original L → output L in measured inks): 0.15→0.41 (black ink), 0.25→0.45, 0.35→0.51,
  0.45→0.60, 0.55→0.70, 0.65→0.81, 0.75→0.89, 0.85→0.93. Deep shadows stay black, mid-tones lifted.
- Correction: an early "ours keeps far less real detail (0.34 vs 0.82)" result was a 3–4 px crop
  misregistration; on the identical crop ours scores 0.92.

Resulting pipeline (`LocalToneMap.kt`): guided-filter base/detail split on OKLab L; base through
Einky's measured curve into the panel range; detail added back with a gain gated by local texture and
reduced on facial skin (eye band kept); shadows aimed at the black ink's real (violet) color.
Presets: Natural (gain 1), Balanced (base ×0.85, gain 1.8), Strong local (×0.7, 2.6), Vivid (Einky-style).
Open: our simulated preview makes Einky/Vivid look pale; needs a RAW photo of Einky's output on the
frame to check.
