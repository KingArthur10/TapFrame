package dev.tapframe.render

import dev.tapframe.protocol.Frame
import dev.tapframe.protocol.Ink

/**
 * How simulated previews are shown on the phone's screen, matched by eye to the real frame.
 *
 * The frame simulation predicts the panel relative to its own white, but a phone screen glows while
 * the panel reflects room light (its white is roughly 40–45% of paper's), so the same colors read
 * differently side by side and depend on the room. The user holds the phone next to the frame
 * showing [pattern] and adjusts these until both look alike. Only the on-screen preview changes,
 * never what is sent.
 */
data class PreviewLook(
    /** Overall brightness, as a linear-light gain (1 = panel white shown as screen white). */
    val brightness: Float = 1f,
    /** Lightness contrast around mid-gray. */
    val contrast: Float = 1f,
    /** Chroma multiplier. */
    val saturation: Float = 1f,
    /** + warmer (yellow), − cooler (blue). */
    val warmth: Float = 0f,
    /** + magenta, − green. */
    val tint: Float = 0f,
) {
    val isIdentity get() = this == PreviewLook()

    /** Applies the look to opaque ARGB pixels. */
    fun apply(argb: IntArray): IntArray {
        if (isIdentity) return argb
        val cache = HashMap<Int, Int>()
        return IntArray(argb.size) { i ->
            val c = argb[i]
            cache.getOrPut(c) { OPAQUE or map(c and 0xFFFFFF) }
        }
    }

    private fun map(rgb: Int): Int {
        val lab = OkLab.fromLinear(Srgb.toLinear(rgb))
        val l = MID + (lab[0] - MID) * contrast
        lab[0] = l
        lab[1] = lab[1] * saturation + tint * TINT_SCALE * l
        lab[2] = lab[2] * saturation + warmth * TINT_SCALE * l
        val lin = OkLab.toLinear(lab)
        return Srgb.fromLinear(FloatArray(3) { (lin[it] * brightness).coerceIn(0f, 1f) })
    }

    companion object {
        val BRIGHTNESS = 0.4f..1.1f
        val CONTRAST = 0.6f..1.5f
        val SATURATION = 0.4f..2.0f
        val WARMTH = -1f..1f
        val TINT = -1f..1f

        private const val MID = 0.7f
        private const val TINT_SCALE = 0.04f
        private const val OPAQUE = 0xFF000000.toInt()

        /** Colors in the calibration image's dithered rows: skin, sky, foliage, grays, strong hues. */
        private val SAMPLES = intArrayOf(
            0x735244, 0xC29682, 0xEBC8B4, 0xA06E55, 0x5A3C2D, 0xF08C96,
            0x627A9D, 0x96BEE6, 0x576C43, 0x3C8C28, 0xE0A32E, 0xBB5695,
        )

        /**
         * The calibration image in buffer order: solid inks (the most reliable reference), a dithered
         * gray ramp, and two rows of dithered everyday colors. Upright on the card: inks at the top.
         */
        fun pattern(): ByteArray {
            val c = Canvas()
            // Bands run along buffer x, which is top-to-bottom on the upright card.
            val inks = listOf(Ink.BLACK, Ink.WHITE, Ink.YELLOW, Ink.RED, Ink.BLUE, Ink.GREEN)
            val cols = cells(Frame.HEIGHT, 6)
            inks.forEachIndexed { i, ink -> c.fill(0, cols[i].first, 96, cols[i].second, ink.code) }
            // Gray ramp from black to white, left to right on the card.
            val ramp = Canvas(96, Frame.HEIGHT)
            val rampRgb = IntArray(96 * Frame.HEIGHT) { i ->
                val v = (i / 96) * 255 / (Frame.HEIGHT - 1); (v shl 16) or (v shl 8) or v
            }
            Dither.dither(rampRgb, 96, Frame.HEIGHT, Dither.Method.FLOYD_STEINBERG, serpentine = true).copyInto(ramp.codes)
            c.blit(100, 0, ramp)
            SAMPLES.forEachIndexed { i, color ->
                val (y, h) = cols[i % 6]
                val x = if (i < 6) 202 else 302
                val patch = Canvas(96, h)
                Dither.dither(IntArray(96 * h) { color }, 96, h, Dither.Method.FLOYD_STEINBERG, serpentine = true).copyInto(patch.codes)
                c.blit(x, y, patch)
            }
            return c.codes
        }

        /** [n] (start, length) cells covering [size]. */
        private fun cells(size: Int, n: Int) = (0 until n).map { i -> (i * size / n) to ((i + 1) * size / n - i * size / n) }
    }
}
