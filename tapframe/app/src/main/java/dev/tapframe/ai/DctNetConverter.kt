package dev.tapframe.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a DCT-Net model as published on ModelScope (a frozen TensorFlow graph, cartoon_*.pb) to
 * ONNX on the phone, so the user can import the official file. The generator's weights are read by
 * name and laid into the same architecture (scripts/dct_to_onnx.py is the reference, checked against
 * all five published styles): a stride-2 encoder, four residual blocks, two bilinear upsampling
 * stages with skips, then the graph's guided filter (radius 1).
 *
 * The ONNX input "input" and output "output" are NCHW float BGR in 0..255; height and width must be
 * multiples of 4.
 */
object DctNetConverter {
    private val CONVS = listOf("Conv", "Conv_1", "Conv_2", "Conv_3", "Conv_4") +
        (0 until 4).flatMap { i -> listOf("block_$i/conv1", "block_$i/conv2") } +
        listOf("Conv_5", "Conv_6", "Conv_7", "Conv_8", "Conv_9")
    private val STRIDE_2 = setOf("Conv_1", "Conv_3")

    class Tensor(val dims: IntArray, val data: FloatArray)

    /** Float constants of a frozen GraphDef, by node name. */
    fun constants(pb: ByteArray): Map<String, Tensor> {
        val out = HashMap<String, Tensor>()
        for (node in ProtoReader(pb).fields().filter { it.number == 1 }) {
            var name = ""; var op = ""; var value: ProtoReader.Field? = null
            for (f in node.reader().fields()) when (f.number) {
                1 -> name = f.string()
                2 -> op = f.string()
                5 -> {
                    var key = ""; var v: ProtoReader.Field? = null
                    for (e in f.reader().fields()) if (e.number == 1) key = e.string() else if (e.number == 2) v = e
                    if (key == "value") value = v
                }
            }
            if (op != "Const" || value == null) continue
            val tensor = value.reader().fields().firstOrNull { it.number == 8 } ?: continue
            parseTensor(tensor)?.let { out[name] = it }
        }
        return out
    }

    private fun parseTensor(t: ProtoReader.Field): Tensor? {
        var dtype = 1L; val dims = mutableListOf<Int>(); var content: ByteArray? = null; val floats = mutableListOf<Float>()
        for (f in t.reader().fields()) when (f.number) {
            1 -> dtype = f.varint
            2 -> for (d in f.reader().fields()) if (d.number == 2) dims += (d.reader().fields().firstOrNull { it.number == 1 }?.varint ?: 0L).toInt()
            4 -> content = f.bytes
            5 -> if (f.wire == 2) { val r = f.bytes; val fb = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); repeat(fb.remaining()) { floats += fb.get() } } else floats += f.float()
        }
        if (dtype != 1L) return null
        val data = content?.let { c -> FloatArray(c.size / 4).also { ByteBuffer.wrap(c).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) } }
            ?: floats.toFloatArray()
        return Tensor(dims.toIntArray(), data)
    }

    /** True if [pb] looks like a DCT-Net generator this converter understands. */
    fun isDctNet(c: Map<String, Tensor>) = CONVS.all { "generator/$it/weights" in c && "generator/$it/biases" in c }

    fun convert(pb: ByteArray): ByteArray {
        val c = constants(pb)
        require(isDctNet(c)) { "not a DCT-Net model" }
        val g = OnnxBuilder()
        fun conv(x: String, key: String): String {
            val w = c.getValue("generator/$key/weights") // HWIO
            val (kh, kw, ci, co) = w.dims.toList()
            val oihw = FloatArray(w.data.size)
            for (h in 0 until kh) for (x2 in 0 until kw) for (i in 0 until ci) for (o in 0 until co)
                oihw[((o * ci + i) * kh + h) * kw + x2] = w.data[((h * kw + x2) * ci + i) * co + o]
            val wn = g.const("$key/w", oihw, longArrayOf(co.toLong(), ci.toLong(), kh.toLong(), kw.toLong()))
            val bn = g.const("$key/b", c.getValue("generator/$key/biases").data)
            val s = if (key in STRIDE_2) 2 else 1
            return g.node("Conv", listOf(x, wn, bn), "$key/out", "kernel_shape" to intArrayOf(kh, kw), "strides" to intArrayOf(s, s), "auto_pad" to "SAME_UPPER")
        }
        fun lrelu(x: String, name: String) = g.node("LeakyRelu", listOf(x), name, "alpha" to 0.2f)
        fun up(x: String, name: String) = g.node(
            "Resize", listOf(x, "", g.const("$name/scales", floatArrayOf(1f, 1f, 2f, 2f))), name,
            "mode" to "linear", "coordinate_transformation_mode" to "asymmetric",
        )

        val k127 = g.const("k127", floatArrayOf(127.5f)); val one = g.const("one", floatArrayOf(1f))
        val x = g.node("Sub", listOf(g.node("Div", listOf("input", k127), "div"), one), "x")
        val l0 = lrelu(conv(x, "Conv"), "l0")
        val l1 = lrelu(conv(l0, "Conv_1"), "l1")
        val l2 = lrelu(conv(l1, "Conv_2"), "l2")
        val l3 = lrelu(conv(l2, "Conv_3"), "l3")
        var h = lrelu(conv(l3, "Conv_4"), "l4")
        for (i in 0 until 4) {
            val r = lrelu(conv(h, "block_$i/conv1"), "block_$i/l")
            h = g.node("Add", listOf(conv(r, "block_$i/conv2"), h), "block_$i/add")
        }
        h = lrelu(conv(h, "Conv_5"), "l5")
        h = g.node("Add", listOf(up(h, "up1"), l2), "skip1")
        h = lrelu(conv(h, "Conv_6"), "l6")
        h = lrelu(conv(h, "Conv_7"), "l7")
        h = g.node("Add", listOf(up(h, "up2"), l0), "skip2")
        h = lrelu(conv(h, "Conv_8"), "l8")
        val y = conv(h, "Conv_9")

        // Guided filter, radius 1: box means normalized by the box of ones (zero-padded borders).
        val boxW = g.const("box", FloatArray(27) { 1f / 9 }, longArrayOf(3, 1, 3, 3))
        fun box(t: String, name: String) = g.node("Conv", listOf(t, boxW), name, "kernel_shape" to intArrayOf(3, 3), "pads" to intArrayOf(1, 1, 1, 1), "group" to 3)
        val ones = g.node("Add", listOf(g.node("Mul", listOf(x, g.const("zero", floatArrayOf(0f))), "x0"), one), "ones")
        val n = box(ones, "N")
        fun mean(t: String, name: String) = g.node("Div", listOf(box(t, "$name/box"), n), name)
        val mx = mean(x, "mean_x"); val my = mean(y, "mean_y")
        val cov = g.node("Sub", listOf(mean(g.node("Mul", listOf(x, y), "xy"), "mean_xy"), g.node("Mul", listOf(mx, my), "mxmy")), "cov")
        val variance = g.node("Sub", listOf(mean(g.node("Mul", listOf(x, x), "xx"), "mean_xx"), g.node("Mul", listOf(mx, mx), "mxmx")), "var")
        val eps = c["add/y"]?.data?.firstOrNull() ?: 0.005f
        val a = g.node("Div", listOf(cov, g.node("Add", listOf(variance, g.const("eps", floatArrayOf(eps))), "var_eps")), "A")
        val b = g.node("Sub", listOf(my, g.node("Mul", listOf(a, mx), "Amx")), "B")
        var out = g.node("Add", listOf(g.node("Mul", listOf(mean(a, "mean_A"), x), "Ax"), mean(b, "mean_B")), "gf")
        val lo = c["clip_by_value/y"]?.data?.firstOrNull() ?: -0.999999f
        val hi = c["clip_by_value/Minimum/y"]?.data?.firstOrNull() ?: 0.999999f
        out = g.node("Clip", listOf(out, g.scalar("lo", lo), g.scalar("hi", hi)), "clip")
        g.node("Mul", listOf(g.node("Add", listOf(out, one), "plus1"), k127), "output")
        return g.build("input", "output")
    }
}
