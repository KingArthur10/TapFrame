package dev.tapframe.render

import dev.tapframe.protocol.Frame
import dev.tapframe.protocol.Ink

/**
 * Photo -> pixel codes. The card is viewed in portrait (304 x 400) while the panel buffer is
 * 400 x 304 and shown transposed, so: fit the photo to portrait, apply the user's [Settings] and tone
 * mapping, transpose into buffer order, then dither.
 */
object PhotoPipeline {
    const val PORTRAIT_W = Frame.HEIGHT // 304
    const val PORTRAIT_H = Frame.WIDTH  // 400

    /** A crop rectangle in source-image pixels; its aspect should be PORTRAIT_W : PORTRAIT_H. */
    data class Crop(val left: Float, val top: Float, val width: Float, val height: Float) {
        /** Maps a source-image point into the fitted portrait. */
        fun toPortrait(x: Float, y: Float): Pair<Float, Float> =
            (x - left) * PORTRAIT_W / width to (y - top) * PORTRAIT_H / height

        /** Portrait-coordinate size of a source-image length. */
        fun toPortraitLength(len: Float): Float = len * PORTRAIT_W / width
    }

    /** The largest centered crop of a w x h image with the portrait aspect. */
    fun centerCrop(w: Int, h: Int): Crop {
        val scale = minOf(w.toFloat() / PORTRAIT_W, h.toFloat() / PORTRAIT_H)
        return Crop((w - PORTRAIT_W * scale) / 2, (h - PORTRAIT_H * scale) / 2, PORTRAIT_W * scale, PORTRAIT_H * scale)
    }

    /**
     * Scales the [crop] of [argb] (w x h) to 304 x 400: box filter in linear light, or nearest
     * neighbour ([nearest]) to keep pixel art crisp. Areas outside the image are white.
     */
    fun fitPortrait(argb: IntArray, w: Int, h: Int, nearest: Boolean = false, crop: Crop = centerCrop(w, h)): IntArray =
        fit(argb, w, h, crop, PORTRAIT_W, PORTRAIT_H, nearest)

    /** As [fitPortrait] but to any output size (the crop is stretched if the aspects differ). */
    fun fit(argb: IntArray, w: Int, h: Int, crop: Crop, outW: Int, outH: Int, nearest: Boolean = false): IntArray {
        require(argb.size == w * h)
        val sx = crop.width / outW
        val sy = crop.height / outH
        return IntArray(outW * outH) { i ->
            val ox = i % outW; val oy = i / outW
            if (nearest) {
                val x = (crop.left + (ox + 0.5f) * sx).toInt()
                val y = (crop.top + (oy + 0.5f) * sy).toInt()
                return@IntArray if (x in 0 until w && y in 0 until h) argb[y * w + x] and 0xFFFFFF else 0xFFFFFF
            }
            val fx0 = crop.left + ox * sx; val fy0 = crop.top + oy * sy
            val x0 = fx0.toInt().coerceIn(0, w); val x1 = maxOf(x0 + 1, (fx0 + sx).toInt()).coerceIn(0, w)
            val y0 = fy0.toInt().coerceIn(0, h); val y1 = maxOf(y0 + 1, (fy0 + sy).toInt()).coerceIn(0, h)
            if (x1 <= x0 || y1 <= y0) return@IntArray 0xFFFFFF
            val sum = FloatArray(3)
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = Srgb.toLinear(argb[y * w + x])
                for (k in 0..2) sum[k] += c[k]
            }
            val n = ((x1 - x0) * (y1 - y0)).toFloat()
            Srgb.fromLinear(FloatArray(3) { sum[it] / n })
        }
    }

    /**
     * Cartoon: stylize (classic smoothing, or the AI model's output in [aiCartoon]), map onto the
     * measured inks (flat with Method.NONE, otherwise dithered), then stamp outlines in black ink.
     */
    private fun renderCartoon(fitted: IntArray, settings: Settings, aiCartoon: IntArray?): ByteArray {
        val ai = settings.effect == Effect.AI_CARTOON
        val input = if (ai) requireNotNull(aiCartoon) { "AI cartoon image missing" } else fitted
        val styled = Cartoon.stylize(Adjust.apply(input, settings), PORTRAIT_W, PORTRAIT_H, smooth = !ai, outline = settings.detail)
        val mapped = LocalToneMap.apply(styled.rgb, PORTRAIT_W, PORTRAIT_H, 1f, 1f, emptyList())
            .let { if (settings.keepHue) GamutMap.MEASURED.apply(it) else it }
        val diffusion = settings.method in setOf(Dither.Method.FLOYD_STEINBERG, Dither.Method.STUCKI, Dither.Method.ATKINSON)
        val codes = Dither.dither(toBuffer(mapped), Frame.WIDTH, Frame.HEIGHT, settings.method, Palette.MEASURED, serpentine = diffusion)
        val outline = toBuffer(IntArray(styled.outline.size) { if (styled.outline[it]) 1 else 0 })
        for (i in codes.indices) if (outline[i] == 1) codes[i] = Ink.BLACK.code.toByte()
        return codes
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
    fun render(fitted: IntArray, settings: Settings, faces: List<Face> = emptyList(), aiCartoon: IntArray? = null): ByteArray {
        if (settings.effect != Effect.NONE) return renderCartoon(fitted, settings, aiCartoon)
        val adjusted = Adjust.apply(fitted, settings)
        val tone = settings.tone
        // The Detail setting scales how far the preset's gain goes beyond "keep detail as is".
        val gain = 1f + (tone.detailGain * settings.detail - 1f).coerceAtLeast(-1f)
        val (prepared, palette) = when (tone) {
            ToneMap.VIVID -> {
                // Einky-style: optional detail boost, lift mid-tones, dither against idealized inks.
                val detailed = if (gain == 1f) adjusted else LocalToneMap.sharpenOnly(adjusted, PORTRAIT_W, PORTRAIT_H, gain, faces)
                // No gamut mapping: Vivid relies on believing the inks are more vivid than they are.
                Adjust.apply(detailed, VIVID_CURVE) to Palette.IDEAL
            }
            else -> LocalToneMap.apply(adjusted, PORTRAIT_W, PORTRAIT_H, tone.baseContrast, gain, faces)
                .let { if (settings.keepHue) GamutMap.MEASURED.apply(it) else it } to Palette.MEASURED
        }
        val diffusion = settings.method in setOf(Dither.Method.FLOYD_STEINBERG, Dither.Method.STUCKI, Dither.Method.ATKINSON)
        return Dither.dither(toBuffer(prepared), Frame.WIDTH, Frame.HEIGHT, settings.method, palette, serpentine = diffusion)
    }

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
