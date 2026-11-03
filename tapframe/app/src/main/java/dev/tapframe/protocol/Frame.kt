package dev.tapframe.protocol

/**
 * Spectra 6 inks, their pixel codes, and how they look on the panel: sRGB measured from RAW
 * photos (session 2, 2026-11-02), normalized so the panel's own white is #FFFFFF.
 *
 * Green is the exception: RAW measured it nearly neutral (#434F4B, OKLab chroma 0.017), but on the
 * second (red) unit it reads clearly green to the eye and in photos (session 4: chroma 0.042 at
 * hue 156° in JPEG, where JPEG boosts the other inks only 1.0–1.4×). Kept at RAW lightness with
 * chroma 0.035 at hue 158°.
 */
enum class Ink(val code: Int, val rgb: Int) {
    BLACK(0, 0x504557),
    WHITE(1, 0xFFFFFF),
    YELLOW(2, 0xFFCD68),
    RED(3, 0xAF4341),
    BLUE(5, 0x0F5890),
    GREEN(6, 0x3D5346),
}

/** Panel geometry and image encoding. */
object Frame {
    const val WIDTH = 400
    const val HEIGHT = 304
    const val SECTIONS = 4
    const val SECTION_BYTES = 15200
    const val IMAGE_BYTES = WIDTH * HEIGHT / 2

    /** Packs WIDTH*HEIGHT pixel codes (row-major) two per byte, first pixel in the high nibble. */
    fun packPixels(codes: ByteArray): ByteArray {
        require(codes.size == WIDTH * HEIGHT) { "expected ${WIDTH * HEIGHT} pixels, got ${codes.size}" }
        return ByteArray(IMAGE_BYTES) { i ->
            (((codes[2 * i].toInt() and 0xF) shl 4) or (codes[2 * i + 1].toInt() and 0xF)).toByte()
        }
    }

    /** Inverse of [packPixels]: IMAGE_BYTES bytes -> WIDTH*HEIGHT pixel codes. */
    fun unpackPixels(packed: ByteArray): ByteArray = ByteArray(packed.size * 2) { i ->
        val b = packed[i / 2].toInt()
        (if (i % 2 == 0) b shr 4 and 0xF else b and 0xF).toByte()
    }

    /**
     * Orientation and color test: a white header strip with a black square top-left and a red
     * square top-right, above six vertical bars (black, white, yellow, red, blue, green).
     */
    fun testPattern(): ByteArray {
        val bars = listOf(Ink.BLACK, Ink.WHITE, Ink.YELLOW, Ink.RED, Ink.BLUE, Ink.GREEN)
        return ByteArray(WIDTH * HEIGHT) { i ->
            val x = i % WIDTH
            val y = i / WIDTH
            val ink = when {
                y >= 60 -> bars[x * 6 / WIDTH]
                x < 60 -> Ink.BLACK
                x >= 370 && y < 30 -> Ink.RED
                else -> Ink.WHITE
            }
            ink.code.toByte()
        }
    }
}
