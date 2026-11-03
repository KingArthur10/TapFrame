# Tapmora 6-color NFC frame protocol

Reverse engineered from Einky 1.0.27 (`cn.gdsid.inky`, Guangdong SID Technology / Hipoink)
via its own debug logging (`adb logcat -s IsoDepHelper EinkNfcDriver NfcManagerHelper flutter`).
Capture: `captures/capture1.log`. Verified by `scripts/decode_capture.py`, which rebuilds
the sent image exactly from the capture.

## Transport

- ISO 14443-4A (`android.nfc.tech.IsoDep`), reader mode with `FLAG_READER_NFC_A`.
- Einky sets the IsoDep timeout to 50000 ms; the refresh command takes ~2 s to answer.
- Tag also advertises NfcA, MifareClassic and Ndef; only IsoDep is used.
- Responses end in status word `9000` on success.

## Session (battery-free refresh)

| # | APDU (hex)                          | Response / notes |
|---|-------------------------------------|------------------|
| 1 | `00 D1 00 00 00`                    | Device-info TLV (see below) + `9000` |
| 2 | `F0 D8 00 00 05 00 00 00 00 0E`     | 14 bytes (all `00` on our unit) + `9000`. Purpose unknown; read of a 14-byte region. |
| 3 | `F0 D3 …` × N                       | Image upload, 4 sections (see below), each APDU answered `9000` |
| 4 | `F0 D4 85 80 00`                    | Refresh. `80` = `section | 0x80` with section 0. Answers `9000` after ~1.7 s |
| 5 | `F0 DE 00 00 01` (every ~100 ms)    | `01 9000` = still refreshing, `00 9000` = done. Einky gives up after 60 s |

Other refresh responses handled by Einky: `698A` = command error; `6986`/`68C6` = retry with
`F0 D4 85 00 00`; `019000` to the refresh itself = success without polling.

## Device info (`00 D1`) — TLV

Our unit:
```
A0 07 F0 07 60 01 90 01 30      width 0x0190 = 400, height 0x0130 = 304 (other bytes unknown)
A1 07 00 FF FF FF FF FF FF
B1 01 10
B2 01 14
B3 01 00
C0 04 20 1E A0 01
C1 04 DE 7C 10 1E               tag UID
D1 07 01 20 00 00 00 00 00
E0 0E 46 E0 00 80 01 81 02 83 03 82 05 86 07 85   color table, Einky reports writeImageCount=4
```
Einky's log for E0: `colorDic={000:0, 001:1, 010:3, 011:2, 021:6, 023:5}`. It also checks for an
"old four-color mode" marker (absent here).

## Image format

- 400 × 304 pixels, row-major, landscape (portrait photos are rotated 90°).
- 4 bits per pixel, two pixels per byte, high nibble first → 60800 bytes.
- Pixel codes (standard Spectra 6): `0` black, `1` white, `2` yellow, `3` red, `5` blue, `6` green.
- Split into 4 sections of 15200 bytes (76 rows each), section index 0–3.

## Upload framing (`F0 D3`)

Per section:
1. Split the 15200 bytes into 2000-byte chunks (last one 1200 bytes).
2. Compress each chunk independently with LZO1X-1 (miniLZO).
3. Split each compressed chunk into ≤ 250-byte blocks and send:

```
F0 D3 <section> <last> <Lc> <chunk> <block> <data…>
```
- `section`: 0–3
- `last`: `01` on the final block of a chunk, else `00`
- `Lc`: `len(data) + 2` (so `FC` for a full 250-byte block)
- `chunk`: 2000-byte chunk index within the section
- `block`: index of this 250-byte block within the compressed chunk
