package dev.tapframe.ai

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire-format reading and writing (enough for TensorFlow GraphDefs and ONNX). */
internal class ProtoReader(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
    class Field(val number: Int, val wire: Int, val varint: Long, val start: Int, val length: Int, private val src: ByteArray) {
        val bytes: ByteArray get() = src.copyOfRange(start, start + length)
        fun reader() = ProtoReader(src, start, start + length)
        fun string() = String(src, start, length, Charsets.UTF_8)
        /** A fixed32 field as a float. */
        fun float(): Float = Float.fromBits(
            (src[start].toInt() and 0xFF) or ((src[start + 1].toInt() and 0xFF) shl 8) or
                ((src[start + 2].toInt() and 0xFF) shl 16) or ((src[start + 3].toInt() and 0xFF) shl 24),
        )
    }

    fun hasNext() = pos < end

    fun next(): Field {
        val key = varint()
        val number = (key ushr 3).toInt(); val wire = (key and 7).toInt()
        return when (wire) {
            0 -> Field(number, wire, varint(), pos, 0, b)
            1 -> Field(number, wire, 0, pos, 8, b).also { pos += 8 }
            2 -> { val n = varint().toInt(); Field(number, wire, 0, pos, n, b).also { pos += n } }
            5 -> Field(number, wire, 0, pos, 4, b).also { pos += 4 }
            else -> error("unsupported wire type $wire")
        }
    }

    fun fields(): Sequence<Field> = generateSequence { if (hasNext()) next() else null }

    fun varint(): Long {
        var v = 0L; var shift = 0
        while (true) {
            val c = b[pos++].toInt() and 0xFF
            v = v or ((c and 0x7F).toLong() shl shift); shift += 7
            if (c < 0x80) return v
        }
    }
}

internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun rawVarint(v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
        out.write(x.toInt())
    }

    private fun key(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    fun int(field: Int, v: Long) = apply { key(field, 0); rawVarint(v) }
    fun bytes(field: Int, v: ByteArray) = apply { key(field, 2); rawVarint(v.size.toLong()); out.write(v) }
    fun string(field: Int, v: String) = bytes(field, v.toByteArray(Charsets.UTF_8))
    fun message(field: Int, build: ProtoWriter.() -> Unit) = bytes(field, ProtoWriter().apply(build).toByteArray())
    fun float(field: Int, v: Float) = apply {
        key(field, 5)
        val bits = v.toRawBits()
        for (i in 0 until 4) out.write((bits ushr (8 * i)) and 0xFF)
    }
}
