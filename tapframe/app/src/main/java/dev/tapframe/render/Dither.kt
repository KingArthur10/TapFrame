package dev.tapframe.render

import dev.tapframe.protocol.Ink
import kotlin.math.pow

/**
 * Target colors for dithering. Each swatch is drawn as a 1 px checkerboard of two pixel codes
 * (identical codes for a solid ink) and carries the sRGB color the panel actually shows for it.
 */
class Palette(val swatches: List<Swatch>, val space: Space = Space.PERCEPTUAL) {
    class Swatch(val even: Int, val odd: Int, val rgb: Int)

    /**
     * PERCEPTUAL: errors diffused in linear light, nearest swatch in OKLab (accurate).
     * SRGB: errors and nearest swatch in plain gamma-encoded RGB, the way Einky dithers.
     */
    enum class Space { PERCEPTUAL, SRGB }

    internal val linear = Array(swatches.size) { Srgb.toLinear(swatches[it].rgb) }
    internal val oklab = Array(swatches.size) { OkLab.fromLinear(linear[it]) }
    internal val gamma = Array(swatches.size) { rgb(swatches[it].rgb) }

    companion object {
        /** The six inks as measured on the panel (see [Ink]). */
        val MEASURED = Palette(Ink.entries.map { Swatch(it.code, it.code, it.rgb) })

        /**
         * Idealized pure primaries, matched in plain sRGB. Fingerprinting Einky's output showed it
         * dithers like this; believing the inks are more vivid than they are makes the dither push
         * harder, which offsets the panel's own flattening.
         */
        val IDEAL = Palette(
            listOf(Ink.BLACK to 0x000000, Ink.WHITE to 0xFFFFFF, Ink.YELLOW to 0xFFFF00, Ink.RED to 0xFF0000, Ink.BLUE to 0x0000FF, Ink.GREEN to 0x00FF00)
                .map { (ink, rgb) -> Swatch(ink.code, ink.code, rgb) },
            Space.SRGB,
        )

        private fun rgb(c: Int) = floatArrayOf((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f)
    }
}

/** sRGB <-> linear-light conversions on 0xRRGGBB colors. */
object Srgb {
    fun channelToLinear(c: Int): Float {
        val v = c / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    fun channelFromLinear(v: Float): Int {
        val c = v.coerceIn(0f, 1f)
        val s = if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1 / 2.4f) - 0.055f
        return (s * 255 + 0.5f).toInt().coerceIn(0, 255)
    }

    fun toLinear(rgb: Int) = floatArrayOf(
        channelToLinear(rgb shr 16 and 0xFF), channelToLinear(rgb shr 8 and 0xFF), channelToLinear(rgb and 0xFF),
    )

    fun fromLinear(c: FloatArray): Int =
        (channelFromLinear(c[0]) shl 16) or (channelFromLinear(c[1]) shl 8) or channelFromLinear(c[2])
}

/** OKLab (Björn Ottosson, 2020): a perceptual space where Euclidean distance tracks visible difference. */
object OkLab {
    fun fromLinear(c: FloatArray): FloatArray {
        val l = Math.cbrt((0.4122214708 * c[0] + 0.5363325363 * c[1] + 0.0514459929 * c[2]))
        val m = Math.cbrt((0.2119034982 * c[0] + 0.6806995451 * c[1] + 0.1073969566 * c[2]))
        val s = Math.cbrt((0.0883024619 * c[0] + 0.2817188376 * c[1] + 0.6299787005 * c[2]))
        return floatArrayOf(
            (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s).toFloat(),
            (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s).toFloat(),
            (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s).toFloat(),
        )
    }

    fun toLinear(lab: FloatArray): FloatArray {
        val l = (lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]).pow(3)
        val m = (lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]).pow(3)
        val s = (lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]).pow(3)
        return floatArrayOf(
            4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
            -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
            -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s,
        )
    }
}

/**
 * Maps an RGB image onto palette codes. Errors are diffused in linear light (clamped to the
 * displayable range so out-of-gamut colors cannot run away); the nearest swatch is chosen in OKLab.
 */
object Dither {
    /** The curated algorithms. Error diffusion: FS, Stucki, Atkinson. Ordered: Bayer, blue noise, clustered dot. */
    enum class Method(val label: String) {
        NONE("No dithering"),
        FLOYD_STEINBERG("Floyd–Steinberg"),
        STUCKI("Stucki (smooth)"),
        ATKINSON("Atkinson (crisp)"),
        BAYER("Bayer pattern"),
        BLUE_NOISE("Blue noise"),
        CLUSTERED("Clustered dot"),
    }

    // (dx, dy, weight) diffusion kernels.
    private val FLOYD_STEINBERG = listOf(Triple(1, 0, 7f / 16), Triple(-1, 1, 3f / 16), Triple(0, 1, 5f / 16), Triple(1, 1, 1f / 16))
    private val STUCKI = listOf(
        Triple(1, 0, 8), Triple(2, 0, 4),
        Triple(-2, 1, 2), Triple(-1, 1, 4), Triple(0, 1, 8), Triple(1, 1, 4), Triple(2, 1, 2),
        Triple(-2, 2, 1), Triple(-1, 2, 2), Triple(0, 2, 4), Triple(1, 2, 2), Triple(2, 2, 1),
    ).map { Triple(it.first, it.second, it.third / 42f) }
    // Atkinson diffuses only 6/8 of the error, which gives its crisp, high-contrast look.
    private val ATKINSON = listOf(1 to 0, 2 to 0, -1 to 1, 0 to 1, 1 to 1, 0 to 2).map { Triple(it.first, it.second, 1f / 8) }

    /** Threshold matrices for ordered dithering, as ranks 0 until size*size. */
    private class Thresholds(val size: Int, val ranks: IntArray) {
        fun at(x: Int, y: Int): Float = (ranks[(y % size) * size + x % size] + 0.5f) / (size * size) - 0.5f
    }

    private val BAYER8: Thresholds = run {
        var m = arrayOf(intArrayOf(0, 2), intArrayOf(3, 1))
        while (m.size < 8) {
            val n = m.size
            m = Array(n * 2) { y -> IntArray(n * 2) { x -> 4 * m[y % n][x % n] + intArrayOf(0, 2, 3, 1)[(y / n) * 2 + x / n] } }
        }
        Thresholds(8, m.flatMap { it.toList() }.toIntArray())
    }

    /** Classic 8x8 clustered-dot (45°) halftone screen. */
    private val CLUSTERED8 = Thresholds(8, intArrayOf(
        24, 10, 12, 26, 35, 47, 49, 37,
        8, 0, 2, 14, 45, 59, 61, 51,
        22, 6, 4, 16, 43, 57, 63, 53,
        30, 20, 18, 28, 33, 41, 55, 39,
        34, 46, 48, 36, 25, 11, 13, 27,
        44, 58, 60, 50, 9, 1, 3, 15,
        42, 56, 62, 52, 23, 7, 5, 17,
        32, 40, 54, 38, 31, 21, 19, 29,
    ))

    /** 64x64 void-and-cluster texture generated by scripts/bluenoise.py (little-endian u16 ranks). */
    private val BLUE_NOISE_64: Thresholds by lazy {
        val bytes = Dither::class.java.getResourceAsStream("/bluenoise64.bin")!!.use { it.readBytes() }
        Thresholds(64, IntArray(64 * 64) { (bytes[2 * it].toInt() and 0xFF) or ((bytes[2 * it + 1].toInt() and 0xFF) shl 8) })
    }

    /** Inks per Knoll plan (more = finer tone steps, slower), and how hard each pick corrects. */
    private const val KNOLL_N = 16
    private const val KNOLL_GAIN = 0.75f

    /**
     * [rgb] is width*height 0xRRGGBB pixels; returns width*height pixel codes. With [serpentine],
     * odd rows are scanned right to left (and the kernel mirrored), which breaks up diagonal worms.
     */
    fun dither(
        rgb: IntArray, width: Int, height: Int, method: Method,
        palette: Palette = Palette.MEASURED, serpentine: Boolean = false,
    ): ByteArray {
        require(rgb.size == width * height)
        val srgb = palette.space == Palette.Space.SRGB
        val px = Array(rgb.size) { i ->
            val c = rgb[i]
            if (srgb) floatArrayOf((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f) else Srgb.toLinear(c)
        }
        val values = if (srgb) palette.gamma else palette.linear
        val out = ByteArray(rgb.size)
        val kernel = when (method) {
            Method.FLOYD_STEINBERG -> FLOYD_STEINBERG
            Method.STUCKI -> STUCKI
            Method.ATKINSON -> ATKINSON
            else -> emptyList()
        }
        val thresholds = when (method) {
            Method.BAYER -> BAYER8
            Method.BLUE_NOISE -> BLUE_NOISE_64
            Method.CLUSTERED -> CLUSTERED8
            else -> null
        }
        for (y in 0 until height) {
            val reverse = serpentine && y % 2 == 1
            for (step in 0 until width) {
                val x = if (reverse) width - 1 - step else step
                val i = y * width + x
                val c = px[i]
                for (k in 0..2) c[k] = c[k].coerceIn(0f, 1f)
                if (thresholds != null) {
                    // Ordered (Knoll pattern dithering): build a list of inks whose average is the
                    // target, sort it by lightness, and let the threshold pattern pick one entry.
                    val plan = knollPlan(c, palette)
                    out[i] = palette.swatches[plan[((thresholds.at(x, y) + 0.5f) * KNOLL_N).toInt().coerceIn(0, KNOLL_N - 1)]].even.toByte()
                    continue
                }
                val best = nearest(c, palette)
                val swatch = palette.swatches[best]
                out[i] = (if ((x + y) % 2 == 0) swatch.even else swatch.odd).toByte()
                if (kernel.isEmpty()) continue
                val chosen = values[best]
                val err = FloatArray(3) { c[it] - chosen[it] }
                for ((dx, dy, w) in kernel) {
                    val nx = if (reverse) x - dx else x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny >= height) continue
                    val n = px[ny * width + nx]
                    for (k in 0..2) n[k] += err[k] * w
                }
            }
        }
        return out
    }

    /**
     * Knoll's pattern-dithering plan for [c]: [KNOLL_N] palette indices chosen greedily so their
     * average (in linear light) approaches [c], sorted from dark to light.
     */
    private fun knollPlan(c: FloatArray, palette: Palette): IntArray {
        val plan = IntArray(KNOLL_N)
        val sum = FloatArray(3)
        for (n in 0 until KNOLL_N) {
            // Aim so that the running average lands on c: target = c + (c - average so far) * n.
            val attempt = FloatArray(3) { k -> (c[k] + (c[k] * n - sum[k]) * KNOLL_GAIN).coerceIn(0f, 1f) }
            val pick = nearest(attempt, palette)
            plan[n] = pick
            val v = if (palette.space == Palette.Space.SRGB) palette.gamma[pick] else palette.linear[pick]
            for (k in 0..2) sum[k] += v[k]
        }
        return plan.sortedBy { palette.oklab[it][0] }.toIntArray()
    }

    /**
     * Index of the palette swatch closest to [c], a working-space color: linear light compared in
     * OKLab for PERCEPTUAL palettes, plain RGB distance for SRGB palettes.
     */
    private fun nearest(c: FloatArray, palette: Palette): Int {
        val srgb = palette.space == Palette.Space.SRGB
        val q = if (srgb) c else OkLab.fromLinear(c)
        val refs = if (srgb) palette.gamma else palette.oklab
        var best = 0
        var bestD = Float.MAX_VALUE
        for ((i, p) in refs.withIndex()) {
            val d0 = q[0] - p[0]; val d1 = q[1] - p[1]; val d2 = q[2] - p[2]
            val d = d0 * d0 + d1 * d1 + d2 * d2
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
