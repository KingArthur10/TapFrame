package dev.tapframe.protocol

/** Command APDUs for the frame (see PROTOCOL.md). */
object Apdu {
    private const val CHUNK_BYTES = 2000
    private const val BLOCK_BYTES = 250

    val READ_INFO: ByteArray get() = fromHex("00D1000000")
    val READ_D8: ByteArray get() = fromHex("F0D8000005000000000E")
    val POLL: ByteArray get() = fromHex("F0DE000001")

    /** Battery-free refresh of [section]. */
    fun refresh(section: Int = 0): ByteArray =
        byteArrayOf(0xF0.toByte(), 0xD4.toByte(), 0x85.toByte(), (section or 0x80).toByte(), 0)

    /**
     * Splits a packed image into F0D3 upload APDUs: 4 sections, 2000-byte chunks each compressed
     * with [compress] (called in upload order), sent in blocks of at most 250 bytes.
     */
    fun buildUpload(image: ByteArray, compress: (ByteArray) -> ByteArray = Lzo::encodeLiteralOnly): List<ByteArray> {
        require(image.size == Frame.IMAGE_BYTES) { "expected ${Frame.IMAGE_BYTES} bytes, got ${image.size}" }
        val apdus = mutableListOf<ByteArray>()
        for (section in 0 until Frame.SECTIONS) {
            val sectionStart = section * Frame.SECTION_BYTES
            for ((chunk, chunkStart) in (0 until Frame.SECTION_BYTES step CHUNK_BYTES).withIndex()) {
                val end = minOf(chunkStart + CHUNK_BYTES, Frame.SECTION_BYTES)
                val compressed = compress(image.copyOfRange(sectionStart + chunkStart, sectionStart + end))
                val blocks = compressed.toList().chunked(BLOCK_BYTES)
                blocks.forEachIndexed { b, block ->
                    val last = if (b == blocks.lastIndex) 1 else 0
                    val header = byteArrayOf(
                        0xF0.toByte(), 0xD3.toByte(), section.toByte(), last.toByte(),
                        (block.size + 2).toByte(), chunk.toByte(), b.toByte(),
                    )
                    apdus += header + block.toByteArray()
                }
            }
        }
        return apdus
    }

    /** True when the response ends with status word 90 00. */
    fun isOk(response: ByteArray): Boolean =
        response.size >= 2 && response[response.size - 2] == 0x90.toByte() && response[response.size - 1] == 0.toByte()

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "odd-length hex" }
        return ByteArray(clean.length / 2) { i ->
            val hi = Character.digit(clean[2 * i], 16)
            val lo = Character.digit(clean[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "bad hex at ${2 * i}" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
