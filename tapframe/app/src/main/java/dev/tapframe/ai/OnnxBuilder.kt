package dev.tapframe.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a small float32 ONNX model (opset 13) in memory: nodes, float initializers, and one NCHW
 * float input and output with symbolic height and width.
 */
internal class OnnxBuilder {
    private class Node(val op: String, val inputs: List<String>, val output: String, val attrs: List<Pair<String, Any>>)
    private class Init(val name: String, val dims: LongArray, val data: FloatArray)

    private val nodes = mutableListOf<Node>()
    private val inits = mutableListOf<Init>()

    fun const(name: String, data: FloatArray, dims: LongArray = longArrayOf(data.size.toLong())): String {
        inits += Init(name, dims, data); return name
    }

    /** A scalar constant (rank 0), as Clip's bounds require. */
    fun scalar(name: String, v: Float): String = const(name, floatArrayOf(v), LongArray(0))

    /** Attributes: Int/Long -> INT, Float -> FLOAT, String -> STRING, IntArray/LongArray -> INTS. */
    fun node(op: String, inputs: List<String>, output: String, vararg attrs: Pair<String, Any>): String {
        nodes += Node(op, inputs, output, attrs.toList()); return output
    }

    fun build(input: String, output: String, channels: Int = 3): ByteArray = ProtoWriter().apply {
        int(1, 7) // ir_version
        message(8) { string(1, ""); int(2, 13) } // opset_import
        string(2, "tapframe")
        message(7) {
            for (n in nodes) message(1) {
                n.inputs.forEach { string(1, it) }
                string(2, n.output)
                string(3, n.output)
                string(4, n.op)
                for ((name, v) in n.attrs) message(5) {
                    string(1, name)
                    when (v) {
                        is Int, is Long -> { int(3, (v as Number).toLong()); int(20, 2) }
                        is Float -> { float(2, v); int(20, 1) }
                        is String -> { string(4, v); int(20, 3) }
                        is IntArray -> { v.forEach { int(8, it.toLong()) }; int(20, 7) }
                        is LongArray -> { v.forEach { int(8, it) }; int(20, 7) }
                        else -> error("unsupported attribute ${v::class}")
                    }
                }
            }
            string(2, "graph")
            for (t in inits) message(5) {
                t.dims.forEach { int(1, it) }
                int(2, 1) // FLOAT
                string(8, t.name)
                val buf = ByteBuffer.allocate(t.data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                buf.asFloatBuffer().put(t.data)
                bytes(9, buf.array())
            }
            message(11) { valueInfo(input, channels) }
            message(12) { valueInfo(output, channels) }
        }
    }.toByteArray()

    private fun ProtoWriter.valueInfo(name: String, channels: Int) {
        string(1, name)
        message(2) { // TypeProto
            message(1) { // tensor_type
                int(1, 1) // FLOAT
                message(2) { // shape
                    message(1) { int(1, 1) }
                    message(1) { int(1, channels.toLong()) }
                    message(1) { string(2, "H") }
                    message(1) { string(2, "W") }
                }
            }
        }
    }
}
