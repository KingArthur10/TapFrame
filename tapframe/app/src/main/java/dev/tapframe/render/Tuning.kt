package dev.tapframe.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * How the photo's tones are compressed into the panel's ~3.6 stops before dithering. NATURAL,
 * BALANCED and STRONG use [LocalToneMap] (base layer into the panel's range, detail added back);
 * VIVID is the Einky-style approach.
 */
enum class ToneMap(val label: String, val baseContrast: Float, val detailGain: Float) {
    /** Global mapping only: base into the panel's range, detail kept as is. */
    NATURAL("Natural", 1.0f, 1.0f),
    /** Large-scale contrast compressed a little, local detail boosted. */
    BALANCED("Balanced", 0.85f, 1.8f),
    /** Large-scale contrast compressed more, local detail boosted strongly. */
    STRONG("Strong local", 0.7f, 2.6f),
    /** Einky-style: dither against idealized pure inks in plain RGB, mid-tones lifted. */
    VIVID("Vivid", 1.0f, 1.0f),
}

/** Optional stylization before dithering. */
enum class Effect { NONE, CARTOON, AI_CARTOON }

/** Everything the user can tune for one photo. */
data class Settings(
    val method: Dither.Method,
    val tone: ToneMap = ToneMap.NATURAL,
    /** Lifts (+) or darkens (−) mid-tones; ends stay put. Range [BRIGHTNESS]. */
    val brightness: Float = 0f,
    /** S-curve strength around mid-gray; 0 = unchanged, negative flattens. Range [CONTRAST]. */
    val contrast: Float = 0f,
    /** Chroma multiplier. Range [SATURATION]. */
    val saturation: Float = 1f,
    /** Multiplies the tone map's local detail boost; 1 = the preset as designed. Range [DETAIL]. */
    val detail: Float = 1f,
    /** Scale with nearest neighbour (crisp pixel art) instead of averaging. */
    val nearest: Boolean = false,
    /** Cartoon stylization; with an effect, [detail] sets the outline strength and [tone] is unused. */
    val effect: Effect = Effect.NONE,
    /**
     * Keep hues the inks can't reach at full strength (magenta, deep skin, pink) by lowering their
     * chroma instead of letting the dither drift to a neighbouring hue (see [GamutMap]). Not used
     * by [ToneMap.VIVID], which deliberately dithers against idealized inks.
     */
    val keepHue: Boolean = true,
    /** Lifts (+) or recovers (−) the bright tones only. Range [HIGHLIGHTS]. */
    val highlights: Float = 0f,
    /** Lifts (+) or deepens (−) the dark tones only. Range [SHADOWS]. */
    val shadows: Float = 0f,
    /** Rotates every hue by this many degrees. Range [HUE]. */
    val hue: Float = 0f,
    /** With [Effect.AI_CARTOON]: which model, [WHITEBOX] (bundled) or a downloaded style's id. */
    val aiStyle: String = WHITEBOX,
) {
    companion object {
        /** The bundled White-box Cartoonization model. */
        const val WHITEBOX = "whitebox"
        val BRIGHTNESS = -0.3f..0.3f
        val CONTRAST = -0.5f..1.5f
        val SATURATION = 0.6f..2.0f
        val DETAIL = 0f..3f
        val HIGHLIGHTS = -1f..1f
        val SHADOWS = -1f..1f
        val HUE = -180f..180f
    }
}

/** What kind of picture this is; decides the first algorithms to show and the starting settings. */
enum class ContentType(val label: String, val candidates: List<Dither.Method>, val defaults: Settings) {
    PEOPLE(
        "People",
        listOf(Dither.Method.FLOYD_STEINBERG, Dither.Method.STUCKI, Dither.Method.BLUE_NOISE, Dither.Method.ATKINSON),
        Settings(Dither.Method.FLOYD_STEINBERG, ToneMap.VIVID),
    ),
    LANDSCAPE(
        "Landscape",
        listOf(Dither.Method.BLUE_NOISE, Dither.Method.STUCKI, Dither.Method.FLOYD_STEINBERG, Dither.Method.ATKINSON),
        Settings(Dither.Method.BLUE_NOISE, ToneMap.BALANCED),
    ),
    GRAPHIC(
        "Illustration / graphic",
        listOf(Dither.Method.NONE, Dither.Method.BAYER, Dither.Method.BLUE_NOISE, Dither.Method.FLOYD_STEINBERG),
        Settings(Dither.Method.NONE, keepHue = false),
    ),
    PIXEL_ART(
        "Pixel art",
        listOf(Dither.Method.NONE, Dither.Method.BAYER, Dither.Method.FLOYD_STEINBERG, Dither.Method.ATKINSON),
        Settings(Dither.Method.NONE, nearest = true, keepHue = false),
    ),
    CARTOON(
        "Cartoon",
        listOf(Dither.Method.NONE, Dither.Method.BLUE_NOISE, Dither.Method.BAYER, Dither.Method.FLOYD_STEINBERG),
        Settings(Dither.Method.NONE, effect = Effect.CARTOON, keepHue = false),
    ),
    CARTOON_AI(
        "Cartoon (AI)",
        listOf(Dither.Method.NONE, Dither.Method.BLUE_NOISE, Dither.Method.BAYER, Dither.Method.FLOYD_STEINBERG),
        Settings(Dither.Method.NONE, effect = Effect.AI_CARTOON, keepHue = false),
    );

    companion object {
        /** The most likely type; see [rank]. */
        fun guess(argb: IntArray, width: Int, height: Int, faces: Int): ContentType = rank(argb, width, height, faces).first()

        /**
         * All types, most likely first. Few distinct colors means drawn content (pixel art if the
         * image is also small, otherwise a graphic); photos are people if faces were found,
         * otherwise landscape.
         */
        fun rank(argb: IntArray, width: Int, height: Int, faces: Int): List<ContentType> {
            val colors = colorsCovering95(argb, width, height)
            val small = maxOf(width, height) <= 512
            val score = mapOf(
                PIXEL_ART to (if (colors <= 64) (if (small) 4f else 1.5f) else 0f),
                GRAPHIC to (if (colors <= 48) 3f else if (colors <= 256) 1.5f else 0.2f),
                PEOPLE to (if (faces > 0) 3.5f else 0.8f) + (if (colors > 256) 0.5f else 0f),
                LANDSCAPE to (if (colors > 256) 2f else 0.5f),
                // Creative styles: always offered, after the content-based guesses.
                CARTOON to 0.1f,
                CARTOON_AI to 0.05f,
            )
            return entries.sortedByDescending { score[it] }
        }

        /** Number of 12-bit colors needed to cover 95% of the (subsampled) pixels. */
        private fun colorsCovering95(argb: IntArray, width: Int, height: Int): Int {
            val step = maxOf(1, maxOf(width, height) / 256)
            val counts = HashMap<Int, Int>()
            var n = 0
            for (y in 0 until height step step) for (x in 0 until width step step) {
                val c = argb[y * width + x]
                val key = ((c shr 20 and 0xF) shl 8) or ((c shr 12 and 0xF) shl 4) or (c shr 4 and 0xF)
                counts[key] = (counts[key] ?: 0) + 1
                n++
            }
            var covered = 0
            var needed = 0
            for (count in counts.values.sortedDescending()) {
                if (covered >= n * 0.95) break
                covered += count
                needed++
            }
            return needed
        }
    }
}

/** Tone and color adjustments applied to the fitted photo before panel mapping and dithering. */
object Adjust {
    /** Peak lightness change of the highlights and shadows controls (small enough to stay monotonic). */
    private const val TONE_AMOUNT = 0.14f

    fun apply(rgb: IntArray, s: Settings): IntArray {
        if (s.brightness == 0f && s.contrast == 0f && s.saturation == 1f && s.highlights == 0f && s.shadows == 0f && s.hue == 0f) return rgb
        val cosH = cos(Math.toRadians(s.hue.toDouble())).toFloat(); val sinH = sin(Math.toRadians(s.hue.toDouble())).toFloat()
        val g = 6f * s.contrast
        fun sig(v: Float) = 1f / (1f + exp(-g * (v - 0.5f)))
        val lo = sig(0f); val hi = sig(1f)
        return IntArray(rgb.size) { i ->
            val lab = OkLab.fromLinear(Srgb.toLinear(rgb[i]))
            var l = lab[0]
            l = (l + s.brightness * 4f * l * (1f - l)).coerceIn(0f, 1f)
            l = when {
                s.contrast > 0.001f -> (sig(l) - lo) / (hi - lo)
                s.contrast < -0.001f -> 0.5f + (l - 0.5f) * (1f + s.contrast)
                else -> l
            }
            // Highlights act on the upper half of the range, shadows on the lower, each as a bump
            // that is zero at mid-gray and at the ends.
            l += if (l > 0.5f) s.highlights * TONE_AMOUNT * sin(PI.toFloat() * (l - 0.5f) * 2f)
            else s.shadows * TONE_AMOUNT * sin(PI.toFloat() * l * 2f)
            lab[0] = l.coerceIn(0f, 1f)
            val a = lab[1] * s.saturation; val b = lab[2] * s.saturation
            lab[1] = a * cosH - b * sinH; lab[2] = a * sinH + b * cosH
            Srgb.fromLinear(OkLab.toLinear(lab))
        }
    }
}

/**
 * Predicts how the frame will look, fitted to RAW photos of the panel (scripts/preview_model.py):
 * where neighbouring pixels have different inks, the panel pulls lightness toward a mid tone and
 * loses some chroma, and the eye blends adjacent dots.
 */
object FrameSim {
    private const val A = 0.55f
    private const val B = 0.2f
    private const val C = 0.2f

    /** [codes] in buffer order (400 x 304) -> simulated appearance, same order, 0xRRGGBB. */
    fun simulate(codes: ByteArray, width: Int, height: Int): IntArray {
        val lin = Array(codes.size) { Srgb.toLinear(Preview.rgbOf(codes[it].toInt())) }
        val k = floatArrayOf(0.25f, 0.5f, 0.25f)
        return IntArray(codes.size) { i ->
            val x = i % width; val y = i / width
            val blur = FloatArray(3)
            var differ = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = (x + dx).coerceIn(0, width - 1); val ny = (y + dy).coerceIn(0, height - 1)
                val j = ny * width + nx
                val w = k[dx + 1] * k[dy + 1]
                for (c in 0..2) blur[c] += lin[j][c] * w
                if ((dx != 0 || dy != 0) && codes[j] != codes[i]) differ++
            }
            val m = differ / 8f
            val lab = OkLab.fromLinear(blur)
            lab[0] += m * (A + B * lab[0] - lab[0])
            lab[1] *= 1 - C * m; lab[2] *= 1 - C * m
            Srgb.fromLinear(OkLab.toLinear(lab))
        }
    }
}
