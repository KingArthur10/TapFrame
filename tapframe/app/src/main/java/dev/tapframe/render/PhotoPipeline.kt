package dev.tapframe.render

import dev.tapframe.protocol.Frame

/**
 * Photo -> pixel codes. The card is viewed in portrait (304 x 400) while the panel buffer is
 * 400 x 304 and shown transposed, so: fit the photo to portrait, apply the user's [Settings] and tone
 * mapping, transpose into buffer order, then dither.
 */
object PhotoPipeline {
    const val PORTRAIT_W = Frame.HEIGHT // 304
    const val PORTRAIT_H = Frame.WIDTH  // 400

    /**
     * Center-crops [argb] (w x h) to the portrait aspect and scales it to 304 x 400: box filter in
     * linear light, or nearest neighbour ([nearest]) to keep pixel art crisp.
     */
    fun fitPortrait(argb: IntArray, w: Int, h: Int, nearest: Boolean = false): IntArray {
        require(argb.size == w * h)
        val scale = minOf(w.toFloat() / PORTRAIT_W, h.toFloat() / PORTRAIT_H)
        val cropX = (w - PORTRAIT_W * scale) / 2
        val cropY = (h - PORTRAIT_H * scale) / 2
        return IntArray(PORTRAIT_W * PORTRAIT_H) { i ->
            val ox = i % PORTRAIT_W; val oy = i / PORTRAIT_W
            if (nearest) {
                val x = (cropX + (ox + 0.5f) * scale).toInt().coerceIn(0, w - 1)
                val y = (cropY + (oy + 0.5f) * scale).toInt().coerceIn(0, h - 1)
                return@IntArray argb[y * w + x] and 0xFFFFFF
            }
            val x0 = (cropX + ox * scale).toInt(); val x1 = maxOf(x0 + 1, (cropX + (ox + 1) * scale).toInt()).coerceAtMost(w)
            val y0 = (cropY + oy * scale).toInt(); val y1 = maxOf(y0 + 1, (cropY + (oy + 1) * scale).toInt()).coerceAtMost(h)
            val sum = FloatArray(3)
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = Srgb.toLinear(argb[y * w + x])
                for (k in 0..2) sum[k] += c[k]
            }
            val n = ((x1 - x0) * (y1 - y0)).toFloat()
            Srgb.fromLinear(FloatArray(3) { sum[it] / n })
        }
    }

    /** Portrait (304 x 400) -> buffer order (400 x 304): buffer(x, y) = portrait(col = y, row = x). */
    fun toBuffer(portrait: IntArray): IntArray =
        IntArray(Frame.WIDTH * Frame.HEIGHT) { i -> portrait[(i % Frame.WIDTH) * PORTRAIT_W + i / Frame.WIDTH] }

    /** Buffer order (400 x 304) -> portrait (304 x 400); the inverse of [toBuffer]. */
    fun toPortrait(buffer: IntArray): IntArray =
        IntArray(PORTRAIT_W * PORTRAIT_H) { i -> buffer[(i % PORTRAIT_W) * Frame.WIDTH + i / PORTRAIT_W] }

    /**
     * Fitted portrait photo + settings -> pixel codes in buffer order, ready for [Frame.packPixels].
     * [faces] (portrait coordinates) let the local tone map go easy on skin.
     */
    fun render(fitted: IntArray, settings: Settings, faces: List<Face> = emptyList()): ByteArray {
        val adjusted = Adjust.apply(fitted, settings)
        val tone = settings.tone
        // The Detail setting scales how far the preset's gain goes beyond "keep detail as is".
        val gain = 1f + (tone.detailGain * settings.detail - 1f).coerceAtLeast(-1f)
        val (prepared, palette) = when (tone) {
            ToneMap.VIVID -> {
                // Einky-style: optional detail boost, lift mid-tones, dither against idealized inks.
                val detailed = if (gain == 1f) adjusted else LocalToneMap.sharpenOnly(adjusted, PORTRAIT_W, PORTRAIT_H, gain, faces)
                Adjust.apply(detailed, VIVID_CURVE) to Palette.IDEAL
            }
            else -> LocalToneMap.apply(adjusted, PORTRAIT_W, PORTRAIT_H, tone.baseContrast, gain, faces) to Palette.MEASURED
        }
        val diffusion = settings.method in setOf(Dither.Method.FLOYD_STEINBERG, Dither.Method.STUCKI, Dither.Method.ATKINSON)
        return Dither.dither(toBuffer(prepared), Frame.WIDTH, Frame.HEIGHT, settings.method, palette, serpentine = diffusion)
    }

    /** Maps a point of the source image (w x h) into the fitted portrait, as [fitPortrait] crops it. */
    fun toPortraitPoint(x: Float, y: Float, w: Int, h: Int): Pair<Float, Float> {
        val scale = minOf(w.toFloat() / PORTRAIT_W, h.toFloat() / PORTRAIT_H)
        return ((x - (w - PORTRAIT_W * scale) / 2) / scale) to ((y - (h - PORTRAIT_H * scale) / 2) / scale)
    }

    /** Portrait-coordinate size of a source-image length, as [fitPortrait] scales it. */
    fun toPortraitLength(len: Float, w: Int, h: Int): Float = len / minOf(w.toFloat() / PORTRAIT_W, h.toFloat() / PORTRAIT_H)

    /**
     * Einky's pre-dither curve, found by matching its ink usage on a captured photo
     * (scripts/dither_lab.py): small mid-tone lift, contrast S-curve, saturation boost.
     */
    private val VIVID_CURVE = Settings(Dither.Method.NONE, brightness = 0.04f, contrast = 0.6f, saturation = 1.4f)


    /** Predicted appearance on the frame, held upright: portrait 304 x 400, opaque ARGB. */
    fun simulatedPortrait(codes: ByteArray): IntArray =
        toPortrait(FrameSim.simulate(codes, Frame.WIDTH, Frame.HEIGHT)).let { p -> IntArray(p.size) { p[it] or OPAQUE } }

    private const val OPAQUE = 0xFF000000.toInt()
}
