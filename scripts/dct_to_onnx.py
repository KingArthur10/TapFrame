"""Convert a DCT-Net frozen TensorFlow graph (ModelScope cartoon_*_h.pb / cartoon_*_bg.pb) to ONNX.

Usage: python3 dct_to_onnx.py <in.pb> <out.onnx>

Reference implementation for the app's on-phone converter (DctNetConverter.kt): the generator's
weights are read by name and laid into the same architecture (encoder, 4 residual blocks, two
upsampling stages with skips), then the graph's guided filter (r = 1, eps from the graph). ONNX
input "input": NCHW float, BGR, 0..255; output "output": NCHW float, BGR, 0..255. H and W must be
multiples of 4.
"""
import struct
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import pb_ops  # noqa: E402


def constants(path):
    out = {}
    for n in pb_ops.nodes(path):
        if n["op"] != "Const":
            continue
        tp = next(w for f, w in pb_ops.fields(n["attr"]["value"]) if f == 8)
        dtype, dims, content, vals = 1, [], b"", []
        for f, w in pb_ops.fields(tp):
            if f == 1: dtype = w
            elif f == 2: dims = [dict(pb_ops.fields(x)).get(1, 0) for g, x in pb_ops.fields(w) if g == 2]
            elif f == 4: content = w
            elif f == 5: vals += list(struct.unpack("<%df" % (len(w) // 4), w)) if isinstance(w, bytes) else [struct.unpack("<f", struct.pack("<I", w))[0]]
        if dtype == 1:
            a = np.frombuffer(content, np.float32).copy() if content else np.array(vals, np.float32)
            out[n["name"]] = a.reshape(dims) if dims and a.size == np.prod(dims) else a
    return out


CONVS = ["Conv", "Conv_1", "Conv_2", "Conv_3", "Conv_4"] + [f"block_{i}/conv{j}" for i in range(4) for j in (1, 2)] + \
    ["Conv_5", "Conv_6", "Conv_7", "Conv_8", "Conv_9"]
STRIDE2 = {"Conv_1", "Conv_3"}


def build(c):
    nodes, inits = [], []
    def const(name, arr):
        inits.append(numpy_helper.from_array(np.asarray(arr, np.float32), name)); return name
    def node(op, ins, out, **attrs):
        nodes.append(helper.make_node(op, ins, [out], name=out, **attrs)); return out
    def conv(x, key):
        w = c[f"generator/{key}/weights"]
        k = w.shape[0]
        wn = const(f"{key}/w", w.transpose(3, 2, 0, 1)); bn = const(f"{key}/b", c[f"generator/{key}/biases"])
        s = 2 if key in STRIDE2 else 1
        return node("Conv", [x, wn, bn], f"{key}/out", kernel_shape=[k, k], strides=[s, s], auto_pad="SAME_UPPER")
    def lrelu(x, name): return node("LeakyRelu", [x], name, alpha=0.2)
    def up(x, name):
        scales = const(name + "/scales", [1, 1, 2, 2])
        return node("Resize", [x, "", scales], name, mode="linear", coordinate_transformation_mode="asymmetric")

    # Normalize: x / 127.5 - 1
    x = node("Sub", [node("Div", ["input", const("k127", [127.5])], "div"), const("one", [1.0])], "x")
    l0 = lrelu(conv(x, "Conv"), "l0")
    l1 = lrelu(conv(l0, "Conv_1"), "l1")
    l2 = lrelu(conv(l1, "Conv_2"), "l2")
    l3 = lrelu(conv(l2, "Conv_3"), "l3")
    h = lrelu(conv(l3, "Conv_4"), "l4")
    for i in range(4):
        r = lrelu(conv(h, f"block_{i}/conv1"), f"block_{i}/l")
        h = node("Add", [conv(r, f"block_{i}/conv2"), h], f"block_{i}/add")
    h = lrelu(conv(h, "Conv_5"), "l5")
    h = node("Add", [up(h, "up1"), l2], "skip1")
    h = lrelu(conv(h, "Conv_6"), "l6")
    h = lrelu(conv(h, "Conv_7"), "l7")
    h = node("Add", [up(h, "up2"), l0], "skip2")
    h = lrelu(conv(h, "Conv_8"), "l8")
    y = conv(h, "Conv_9")

    # Guided filter (r = 1): box means normalized by the box of ones (zero padding at borders).
    box_w = const("box", np.full((3, 1, 3, 3), 1 / 9, np.float32))
    def box(t, name): return node("Conv", [t, box_w], name, kernel_shape=[3, 3], pads=[1, 1, 1, 1], group=3)
    ones = node("Add", [node("Mul", [x, const("zero", [0.0])], "x0"), "one"], "ones")
    n = box(ones, "N")
    def mean(t, name): return node("Div", [box(t, name + "/box"), n], name)
    mx = mean(x, "mean_x"); my = mean(y, "mean_y")
    cov = node("Sub", [mean(node("Mul", [x, y], "xy"), "mean_xy"), node("Mul", [mx, my], "mxmy")], "cov")
    var = node("Sub", [mean(node("Mul", [x, x], "xx"), "mean_xx"), node("Mul", [mx, mx], "mxmx")], "var")
    a = node("Div", [cov, node("Add", [var, const("eps", [float(c["add/y"].ravel()[0])])], "var_eps")], "A")
    b = node("Sub", [my, node("Mul", [a, mx], "Amx")], "B")
    out = node("Add", [node("Mul", [mean(a, "mean_A"), x], "Ax"), mean(b, "mean_B")], "gf")
    lo = float(c["clip_by_value/y"].ravel()[0]); hi = float(c["clip_by_value/Minimum/y"].ravel()[0])
    out = node("Clip", [out, const("lo", [lo]), const("hi", [hi])], "clip")
    out = node("Mul", [node("Add", [out, "one"], "plus1"), "k127"], "output")
    # Clip wants scalar bounds.
    for t in inits:
        if t.name in ("lo", "hi"): t.dims[:] = []
    g = helper.make_graph(nodes, "dctnet",
                          [helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 3, "H", "W"])],
                          [helper.make_tensor_value_info("output", TensorProto.FLOAT, [1, 3, "H", "W"])], inits)
    m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 13)])
    m.ir_version = 7
    return m


if __name__ == "__main__":
    m = build(constants(sys.argv[1]))
    onnx.checker.check_model(m)
    onnx.save(m, sys.argv[2])
    print("ok", len(m.graph.node), "nodes")
