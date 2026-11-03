"""Rebuild the image sent to a Tapmora frame from an adb logcat capture of Einky.

Usage: python3 -I decode_capture.py <capture.log> <out_prefix>
"""
import re
import sys

from PIL import Image

# Nibble value -> RGB, using the standard Spectra 6 controller codes
# (matches the color table the frame reports in its 00D1 response).
PALETTE = {
    0: (0, 0, 0),        # black
    1: (255, 255, 255),  # white
    2: (255, 230, 0),    # yellow
    3: (200, 0, 0),      # red
    5: (0, 60, 200),     # blue
    6: (0, 150, 60),     # green
}


def lzo1x_decompress(src: bytes) -> bytes:
    out = bytearray()
    ip = 0

    def copy_match(m_pos, n):
        if m_pos < 0:
            raise ValueError("match offset before start of output")
        for k in range(n):
            out.append(out[m_pos + k])

    def zero_ext(t):
        nonlocal ip
        while src[ip] == 0:
            t += 255
            ip += 1
        t += src[ip]
        ip += 1
        return t

    t = src[0]
    in_match_loop = False
    if t > 17:
        ip = 1
        t -= 17
        out += src[ip:ip + t]
        ip += t
        if t < 4:
            t = src[ip]
            ip += 1
            in_match_loop = True
        else:
            # Literal run done; next instruction may be the far 3-byte M1.
            t = src[ip]
            ip += 1
            if t < 16:
                m_pos = len(out) - (1 + 0x0800) - (t >> 2) - (src[ip] << 2)
                ip += 1
                copy_match(m_pos, 3)
                t = src[ip - 2] & 3
                if t:
                    out += src[ip:ip + t]
                    ip += t
                    t = src[ip]
                    ip += 1
                    in_match_loop = True
            else:
                in_match_loop = True

    while True:
        if not in_match_loop:
            t = src[ip]
            ip += 1
            if t < 16:
                if t == 0:
                    t = zero_ext(15)
                t += 3
                out += src[ip:ip + t]
                ip += t
                t = src[ip]
                ip += 1
                if t < 16:
                    m_pos = len(out) - (1 + 0x0800) - (t >> 2) - (src[ip] << 2)
                    ip += 1
                    copy_match(m_pos, 3)
                    t = src[ip - 2] & 3
                    if t == 0:
                        continue
                    out += src[ip:ip + t]
                    ip += t
                    t = src[ip]
                    ip += 1
        in_match_loop = False

        # Match loop
        while True:
            if t >= 64:
                m_pos = len(out) - 1 - ((t >> 2) & 7) - (src[ip] << 3)
                ip += 1
                copy_match(m_pos, (t >> 5) + 1)
            elif t >= 32:
                t &= 31
                if t == 0:
                    t = zero_ext(31)
                m_pos = len(out) - 1 - ((src[ip] | (src[ip + 1] << 8)) >> 2)
                ip += 2
                copy_match(m_pos, t + 2)
            elif t >= 16:
                m_pos = len(out) - ((t & 8) << 11)
                t &= 7
                if t == 0:
                    t = zero_ext(7)
                m_pos -= (src[ip] | (src[ip + 1] << 8)) >> 2
                ip += 2
                if m_pos == len(out):
                    return bytes(out)  # end-of-stream marker
                m_pos -= 0x4000
                copy_match(m_pos, t + 2)
            else:
                m_pos = len(out) - 1 - (t >> 2) - (src[ip] << 2)
                ip += 1
                copy_match(m_pos, 2)

            t = src[ip - 2] & 3
            if t == 0:
                break
            out += src[ip:ip + t]
            ip += t
            t = src[ip]
            ip += 1


def parse_sections(log_text: str):
    """Return {section: {chunk: bytearray of compressed data}} from F0D3 APDUs.

    Einky sends every image several times while previewing; keep only the
    last complete upload (the one right before the F0D4 refresh).
    """
    apdus = re.findall(r"发送APDU: ([0-9A-F]+)", log_text)
    last_refresh = max(i for i, a in enumerate(apdus) if a.startswith("F0D4"))
    sections = {}
    for a in apdus[:last_refresh]:
        if not a.startswith("F0D3"):
            continue
        b = bytes.fromhex(a)
        section, _last, lc, chunk, sub = b[2], b[3], b[4], b[5], b[6]
        data = b[7:]
        assert len(data) == lc - 2, (len(data), lc)
        if section == 0 and chunk == 0 and sub == 0:
            sections = {}  # a new upload started
        sections.setdefault(section, {}).setdefault(chunk, bytearray()).extend(data)
    return sections


def main():
    log_path, out_prefix = sys.argv[1], sys.argv[2]
    text = open(log_path, encoding="utf-8", errors="replace").read()
    sections = parse_sections(text)
    raw = bytearray()
    for s in sorted(sections):
        sec = bytearray()
        for c in sorted(sections[s]):
            sec += lzo1x_decompress(bytes(sections[s][c]))
        print(f"section {s}: {len(sections[s])} chunks -> {len(sec)} bytes")
        raw += sec
    open(out_prefix + ".bin", "wb").write(raw)

    vals = []
    for byte in raw:
        vals += [byte >> 4, byte & 0x0F]
    used = sorted(set(vals))
    print(f"total {len(raw)} bytes, {len(vals)} pixels, nibble values used: {used}")

    for w, h in [(400, 304), (304, 400)]:
        img = Image.new("RGB", (w, h))
        img.putdata([PALETTE.get(v, (255, 0, 255)) for v in vals[: w * h]])
        img.save(f"{out_prefix}_{w}x{h}.png")
        print(f"wrote {out_prefix}_{w}x{h}.png")


if __name__ == "__main__":
    main()
