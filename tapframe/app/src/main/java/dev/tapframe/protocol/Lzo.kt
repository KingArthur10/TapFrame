package dev.tapframe.protocol

/** The LZO1X subset the frame understands. */
object Lzo {
    private val END_MARKER = byteArrayOf(0x11, 0x00, 0x00)

    /** Valid LZO1X stream containing [data] as literals only, followed by the end marker 11 00 00. */
    fun encodeLiteralOnly(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "nothing to encode" }
        val header = if (data.size <= 238) {
            byteArrayOf((data.size + 17).toByte())
        } else {
            // Instruction 0: length = 15 + 255 * (extra zero bytes) + (final byte) + 3.
            val zeros = (data.size - 18 - 1) / 255
            val last = data.size - 18 - zeros * 255
            ByteArray(1 + zeros) + byteArrayOf(last.toByte())
        }
        return header + data + END_MARKER
    }

    /**
     * LZO1X compressor using literal runs, "M2" matches (length 3–8, distance ≤ 2048, 2 bytes) and
     * "M3" matches (length ≥ 4, distance ≤ 16384), which every LZO1X decoder supports. Every match
     * saves at least one byte, so the output is never more than a few bytes larger than the input
     * and always fits the frame's chunk buffer.
     *
     * Stream rules mirrored from the decoder: after a match, 1–3 following literals go into the
     * low two bits of the match instruction (M2: its first byte, M3: its first offset byte) and must
     * be followed by another match; 4+ literals need a literal-run instruction, which must also be
     * followed by a match. The stream ends with the marker 11 00 00 (a match with offset 0).
     */
    fun compress(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "nothing to encode" }
        val out = Output(data.size + data.size / 16 + 16)
        val head = IntArray(1 shl HASH_BITS) { -1 }
        val prev = IntArray(data.size) { -1 }
        var afterMatch = false
        var stateIndex = -1 // byte of the previous match that holds the following literal count
        var literalStart = 0

        fun insert(i: Int) {
            if (i + 3 > data.size) return
            val h = hash(data, i)
            prev[i] = head[h]
            head[h] = i
        }

        fun literals(end: Int) {
            val n = end - literalStart
            if (n == 0) return
            when {
                !afterMatch && n <= 238 -> out.byte(n + 17)
                afterMatch && n <= 3 -> out.or(stateIndex, n)
                n <= 18 -> out.byte(n - 3)
                else -> { out.byte(0); out.extended(n - 18) }
            }
            out.bytes(data, literalStart, end)
        }

        var i = 0
        while (i + 3 <= data.size) {
            // Longest match among the most recent candidates with the same 3-byte hash.
            var bestLen = 0
            var bestDist = 0
            var c = head[hash(data, i)]
            var tries = MAX_TRIES
            while (c >= 0 && i - c <= MAX_DISTANCE && tries-- > 0) {
                var len = 0
                while (i + len < data.size && data[c + len] == data[i + len]) len++
                val dist = i - c
                val usable = len >= 4 || (len == 3 && dist <= M2_MAX_DISTANCE)
                if (usable && (len > bestLen || (len == bestLen && dist < bestDist))) {
                    bestLen = len; bestDist = dist
                }
                c = prev[c]
            }
            if (bestLen == 0) {
                insert(i)
                i++
                continue
            }
            literals(i)
            if (bestLen <= 8 && bestDist <= M2_MAX_DISTANCE) {
                val d = bestDist - 1
                stateIndex = out.size
                out.byte(((bestLen - 1) shl 5) or ((d and 7) shl 2))
                out.byte(d ushr 3)
            } else {
                if (bestLen - 2 <= 31) out.byte(32 or (bestLen - 2)) else { out.byte(32); out.extended(bestLen - 2 - 31) }
                val offset = (bestDist - 1) shl 2
                stateIndex = out.size
                out.byte(offset and 0xFF)
                out.byte(offset ushr 8)
            }
            afterMatch = true
            for (k in i until i + bestLen) insert(k)
            i += bestLen
            literalStart = i
        }
        literals(data.size)
        END_MARKER.forEach { out.byte(it.toInt()) }
        return out.toByteArray()
    }

    private const val MAX_DISTANCE = 16384
    private const val M2_MAX_DISTANCE = 2048
    private const val MAX_TRIES = 16
    private const val HASH_BITS = 13

    private fun hash(d: ByteArray, i: Int): Int {
        val v = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8) or ((d[i + 2].toInt() and 0xFF) shl 16)
        return (v * -1640531535) ushr (32 - HASH_BITS)
    }

    /** Growable byte buffer with the LZO zero-extension length encoding. */
    private class Output(capacity: Int) {
        private var buf = ByteArray(capacity)
        var size = 0
            private set

        fun byte(v: Int) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = v.toByte()
        }

        fun bytes(src: ByteArray, from: Int, to: Int) {
            for (k in from until to) byte(src[k].toInt())
        }

        fun or(index: Int, bits: Int) {
            buf[index] = (buf[index].toInt() or bits).toByte()
        }

        /** v >= 1: a zero byte per 255, then the remainder (1..255). */
        fun extended(v: Int) {
            var r = v
            while (r > 255) { byte(0); r -= 255 }
            byte(r)
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    /** LZO1X decompressor (port of the verified decoder in scripts/decode_capture.py). */
    fun decompress(src: ByteArray): ByteArray = Decoder(src).run()

    private class Decoder(private val src: ByteArray) {
        private var ip = 0
        private var out = ByteArray(maxOf(64, src.size * 2))
        private var op = 0

        private fun u8(): Int {
            if (ip >= src.size) throw IllegalArgumentException("truncated LZO input")
            return src[ip++].toInt() and 0xFF
        }

        private fun le16(): Int = u8() or (u8() shl 8)

        private fun ensure(extra: Int) {
            if (op + extra > out.size) out = out.copyOf(maxOf(out.size * 2, op + extra))
        }

        private fun literals(n: Int) {
            if (ip + n > src.size) throw IllegalArgumentException("truncated LZO literal run")
            ensure(n)
            src.copyInto(out, op, ip, ip + n)
            ip += n
            op += n
        }

        private fun match(pos: Int, n: Int) {
            if (pos < 0) throw IllegalArgumentException("LZO match before start of output")
            ensure(n)
            // Byte by byte: source and destination may overlap.
            for (k in 0 until n) out[op + k] = out[pos + k]
            op += n
        }

        private fun zeroExt(base: Int): Int {
            var t = base
            while (true) {
                val b = u8()
                if (b != 0) return t + b
                t += 255
            }
        }

        /** Low two bits of the instruction's last offset byte = trailing literal count. */
        private fun trailing(): Int = src[ip - 2].toInt() and 3

        fun run(): ByteArray {
            var t = 0
            var inMatch = false
            if (src.isNotEmpty() && (src[0].toInt() and 0xFF) > 17) {
                val run = u8() - 17
                literals(run)
                t = u8()
                if (run < 4 || t >= 16) {
                    inMatch = true
                } else {
                    match(op - (1 + 0x0800) - (t shr 2) - (u8() shl 2), 3)
                    t = trailing()
                    if (t != 0) {
                        literals(t)
                        t = u8()
                        inMatch = true
                    }
                }
            }
            while (true) {
                if (!inMatch) {
                    t = u8()
                    if (t < 16) {
                        if (t == 0) t = zeroExt(15)
                        literals(t + 3)
                        t = u8()
                        if (t < 16) {
                            match(op - (1 + 0x0800) - (t shr 2) - (u8() shl 2), 3)
                            t = trailing()
                            if (t == 0) continue
                            literals(t)
                            t = u8()
                        }
                    }
                }
                inMatch = false

                while (true) {
                    when {
                        t >= 64 -> match(op - 1 - ((t shr 2) and 7) - (u8() shl 3), (t shr 5) + 1)
                        t >= 32 -> {
                            var len = t and 31
                            if (len == 0) len = zeroExt(31)
                            match(op - 1 - (le16() shr 2), len + 2)
                        }
                        t >= 16 -> {
                            var pos = op - ((t and 8) shl 11)
                            var len = t and 7
                            if (len == 0) len = zeroExt(7)
                            pos -= le16() shr 2
                            if (pos == op) return out.copyOf(op) // end-of-stream marker
                            match(pos - 0x4000, len + 2)
                        }
                        else -> match(op - 1 - (t shr 2) - (u8() shl 2), 2)
                    }
                    t = trailing()
                    if (t == 0) break
                    literals(t)
                    t = u8()
                }
            }
        }
    }
}
