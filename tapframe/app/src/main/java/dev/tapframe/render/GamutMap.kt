package dev.tapframe.render

import dev.tapframe.protocol.Ink
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hue-preserving gamut mapping onto the colors the six inks can mix.
 *
 * Error diffusion can only reproduce colors inside the convex hull of the inks (in linear light).
 * Given a color outside it, the dither drifts to whichever mix is nearest, which shifts hue: on the
 * panel, greens came out khaki, cyan blue, magenta red, and dark skin violet (session 4 charts).
 * This keeps each out-of-gamut pixel's OKLab hue and lowers its chroma just enough to land inside
 * the hull, letting lightness give way a little toward where the inks make that hue best. Chroma is
 * measured from the panel's own gray at that lightness (the black–white ink mix, slightly violet in
 * the shadows), which is always reproducible.
 *
 * The inks have strong chroma only at red–yellow and blue–magenta. The green ink is green but dark
 * and weak, so light or vivid greens and cyans would keep too little color; those are left to the
 * dither (olive, blue).
 */
class GamutMap(inks: List<Int> = Ink.entries.map { it.rgb }) {
    private val points = inks.map { Srgb.toLinear(it) }

    /** Outward hull faces as (normal, offset): inside means normal·p ≤ offset. */
    private val faces: List<Pair<FloatArray, Float>> = buildList {
        val n = points.size
        for (i in 0 until n) for (j in i + 1 until n) for (k in j + 1 until n) {
            val normal = cross(sub(points[j], points[i]), sub(points[k], points[i]))
            if (dot(normal, normal) < 1e-12f) continue
            val d = dot(normal, points[i])
            val sides = points.indices.filter { it != i && it != j && it != k }.map { dot(normal, points[it]) - d }
            when {
                sides.all { it <= 1e-6f } -> add(normal to d)
                sides.all { it >= -1e-6f } -> add(FloatArray(3) { -normal[it] } to -d)
            }
        }
    }

    private val black = points[Ink.entries.indexOf(Ink.BLACK)]
    private val white = points[Ink.entries.indexOf(Ink.WHITE)]
    private val lBlack = OkLab.fromLinear(black)[0]
    private val lWhite = OkLab.fromLinear(white)[0]

    /** The panel's gray (black–white ink mix) at OKLab lightness index 0..[STEPS], in OKLab. */
    private val grays: Array<FloatArray> = Array(STEPS + 1) { s ->
        val target = lBlack + (lWhite - lBlack) * s / STEPS
        var lo = 0f; var hi = 1f
        repeat(20) {
            val mid = (lo + hi) / 2
            if (OkLab.fromLinear(mix(mid))[0] < target) lo = mid else hi = mid
        }
        OkLab.fromLinear(mix((lo + hi) / 2))
    }

    private fun mix(t: Float) = FloatArray(3) { black[it] + t * (white[it] - black[it]) }

    fun inside(linear: FloatArray): Boolean = faces.all { (n, d) -> dot(n, linear) <= d + 1e-4f }

    private fun grayAt(l: Float) = grays[((l - lBlack) / (lWhite - lBlack) * STEPS).toInt().coerceIn(0, STEPS)]

    /**
     * The most colorful reproducible color per hue bin, the hull's "cusp", as (lightness, chroma).
     * Chroma is measured from the panel's gray at each lightness.
     */
    private val cusps: Array<FloatArray> = Array(HUES) { bin ->
        val angle = 2 * PI.toFloat() * bin / HUES
        val dir = floatArrayOf(cos(angle), sin(angle))
        var bestL = (lBlack + lWhite) / 2; var bestC = -1f
        for (s in 0..48) {
            val l = lBlack + (lWhite - lBlack) * s / 48
            val g = grayAt(l)
            var lo = 0f; var hi = 0.4f
            repeat(12) {
                val c = (lo + hi) / 2
                if (inside(OkLab.toLinear(floatArrayOf(l, g[1] + c * dir[0], g[2] + c * dir[1])))) lo = c else hi = c
            }
            if (lo > bestC) { bestC = lo; bestL = l }
        }
        floatArrayOf(bestL, bestC)
    }

    /**
     * Maps one 0xRRGGBB color. Out-of-gamut colors move in a straight line (in OKLab) toward the
     * panel's gray at a lightness halfway to the hue's cusp, so hue is kept and lightness gives way
     * a little toward where the inks make that hue best.
     *
     * Where keeping the hue would cost most of the color (hues the inks barely make, such as
     * yellow-green to cyan, or very saturated yellows and blues), the result fades back to the
     * original color and the dither shows it as the nearest hue it can make (olive, blue). The fade
     * is gradual so smooth gradients don't band.
     */
    fun map(rgb: Int): Int {
        val linear = Srgb.toLinear(rgb)
        if (inside(linear)) return rgb
        val original = OkLab.fromLinear(linear)
        val lab = original.copyOf().also { it[0] = it[0].coerceIn(lBlack, lWhite) }
        val g0 = grayAt(lab[0])
        val bin = ((atan2(lab[2] - g0[2], lab[1] - g0[1]) / (2 * PI.toFloat()) * HUES).roundToInt() + HUES) % HUES
        val (cuspL, cuspC) = cusps[bin]
        val anchor = grayAt((lab[0] + cuspL) / 2)
        // Bisect along the line from the anchor to the color: t is the fraction of chroma kept.
        var lo = 0f; var hi = 1f
        val probe = FloatArray(3)
        fun at(t: Float) { for (k in 0..2) probe[k] = anchor[k] + t * (lab[k] - anchor[k]) }
        repeat(14) {
            val t = (lo + hi) / 2
            at(t)
            if (inside(OkLab.toLinear(probe))) lo = t else hi = t
        }
        at(lo)
        val weight = smoothstep(KEEP_FADE, lo) * smoothstep(CUSP_FADE, cuspC)
        if (weight <= 0f) return rgb
        val out = FloatArray(3) { original[it] + weight * (probe[it] - original[it]) }
        return Srgb.fromLinear(OkLab.toLinear(out))
    }

    fun apply(rgb: IntArray): IntArray = IntArray(rgb.size) { map(rgb[it]) }

    companion object {
        private const val STEPS = 256
        private const val HUES = 72
        /** Fraction of chroma kept: below the range the original is left alone, above it fully mapped. */
        private val KEEP_FADE = 0.25f..0.45f
        /** Most colorful reproducible chroma at the hue: same fade. */
        private val CUSP_FADE = 0.035f..0.06f

        private fun smoothstep(range: ClosedFloatingPointRange<Float>, v: Float): Float {
            val t = ((v - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

        /** The measured inks of the first unit (see [Ink]). */
        val MEASURED by lazy { GamutMap() }

        private fun sub(a: FloatArray, b: FloatArray) = FloatArray(3) { a[it] - b[it] }
        private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        private fun cross(a: FloatArray, b: FloatArray) =
            floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    }
}
