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
