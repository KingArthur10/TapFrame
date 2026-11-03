"""List the ops in a TensorFlow frozen GraphDef (.pb) without TensorFlow (raw protobuf decoding).

Usage: python3 pb_ops.py <graph.pb>
"""
import collections
import sys


def varint(b, i):
    v = s = 0
    while True:
        c = b[i]; i += 1
        v |= (c & 0x7F) << s; s += 7
        if c < 0x80:
            return v, i


def fields(b):
    i = 0
    while i < len(b):
        key, i = varint(b, i)
        f, t = key >> 3, key & 7
        if t == 0:
            v, i = varint(b, i)
        elif t == 2:
            n, i = varint(b, i); v = b[i:i + n]; i += n
        elif t == 1:
            v = b[i:i + 8]; i += 8
        elif t == 5:
            v = b[i:i + 4]; i += 4
        else:
            raise ValueError(t)
        yield f, v


def nodes(path):
    for f, v in fields(open(path, "rb").read()):
        if f == 1:
            n = {"input": [], "attr": {}}
            for g, w in fields(v):
                if g == 1: n["name"] = w.decode()
                elif g == 2: n["op"] = w.decode()
                elif g == 3: n["input"].append(w.decode())
                elif g == 5:
                    kv = dict(fields(w)); n["attr"][kv[1].decode()] = kv.get(2, b"")
            yield n


if __name__ == "__main__":
    ns = list(nodes(sys.argv[1]))
    print(len(ns), "nodes")
    print(collections.Counter(n["op"] for n in ns).most_common())
    for n in ns:
        if n["op"] in ("Placeholder",) or n["name"].endswith("output_image"):
            print(n["op"], n["name"], n["input"])
