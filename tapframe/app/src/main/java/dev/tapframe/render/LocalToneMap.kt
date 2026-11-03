package dev.tapframe.render

import dev.tapframe.protocol.Ink
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tanh

/** A detected face in portrait (304 x 400) coordinates: the point between the eyes and the eye distance. */
data class Face(val x: Float, val y: Float, val eyeDistance: Float)

/**
 * HDR-style local tone mapping for a panel with roughly 3.6 stops of range (black ≈ 8% of white).
 *
 * On OKLab lightness: an edge-preserving guided filter splits the photo into a smooth base layer
 * (large-scale lighting) and a detail layer (texture, edges). The base is mapped into the panel's
 * range and, for stronger presets, compressed further around its mean; the detail is added back
 * with a gain that is
 *  - gated by local texture, so flat areas (sky, walls) are not boosted and dither noise stays low,
 *  - reduced on facial skin but kept across the eyes,
 * which keeps micro contrast while the overall range shrinks. Chroma is kept, then soft-limited.
 */
object LocalToneMap {
    /** Guided filter radius (px) and edge threshold (variance in L). */
    private const val RADIUS = 10
    private const val EPS = 0.01f

    /** Texture gate on the detail layer's local standard deviation. */
    private const val TEXTURE_LO = 0.008f
    private const val TEXTURE_HI = 0.035f

    /** Share of the extra detail gain applied on facial skin outside the eye band. */
    private const val SKIN_SHARE = 0.35f

    private const val CHROMA_LIMIT = 0.13f

    /**
     * @param baseContrast 1 = keep the base layer's large-scale contrast, < 1 compresses it.
     * @param detailGain 1 = keep detail as is, > 1 boosts it (where gated in).
     */
    fun apply(rgb: IntArray, w: Int, h: Int, baseContrast: Float, detailGain: Float, faces: List<Face>): IntArray {
        val n = rgb.size
        val labs = Array(n) { OkLab.fromLinear(Srgb.toLinear(rgb[it])) }
        val l = FloatArray(n) { labs[it][0] }
        val base = guidedFilter(l, w, h)
        val detail = FloatArray(n) { l[it] - base[it] }
        val texture = localStd(detail, w, h, 2)
        val skinShare = faceWeights(faces, w, h)

        // The black ink is slightly violet: bend the neutral axis so the darkest tones aim at its
        // real color. Aiming at a neutral dark gray instead makes the dither pick blue/green dots.
        val blackLab = OkLab.fromLinear(Srgb.toLinear(Ink.BLACK.rgb))
        val black = blackLab[0]
        val mean = base.average().toFloat()
        return IntArray(n) { i ->
            val b = baseCurve(mean + (base[i] - mean) * baseContrast)
            val gate = smoothstep(TEXTURE_LO, TEXTURE_HI, texture[i])
            val gain = 1f + (detailGain - 1f) * gate * skinShare[i]
            val out = black + (1f - black) * b + gain * detail[i]
            val lab = labs[i]
            lab[0] = out.coerceIn(0f, 1f)
            val chroma = hypot(lab[1], lab[2])
            if (chroma > 1e-6f) {
                val k = CHROMA_LIMIT * tanh(chroma / CHROMA_LIMIT) / chroma
                lab[1] *= k; lab[2] *= k
            }
            val shadow = (1f - (lab[0] - black) / (1f - black)).coerceIn(0f, 1f)
            lab[1] += shadow * blackLab[1]; lab[2] += shadow * blackLab[2]
            Srgb.fromLinear(OkLab.toLinear(lab))
        }
    }

    /**
     * Only the (gated, face-aware) detail boost, without range mapping or chroma limits: for VIVID,
     * which keeps Einky's own mapping.
     */
    fun sharpenOnly(rgb: IntArray, w: Int, h: Int, detailGain: Float, faces: List<Face>): IntArray {
        val labs = Array(rgb.size) { OkLab.fromLinear(Srgb.toLinear(rgb[it])) }
        val l = FloatArray(rgb.size) { labs[it][0] }
        val base = guidedFilter(l, w, h)
        val detail = FloatArray(rgb.size) { l[it] - base[it] }
        val texture = localStd(detail, w, h, 2)
        val skinShare = faceWeights(faces, w, h)
        return IntArray(rgb.size) { i ->
            val gate = smoothstep(TEXTURE_LO, TEXTURE_HI, texture[i])
            val lab = labs[i]
            lab[0] = (base[i] + (1f + (detailGain - 1f) * gate * skinShare[i]) * detail[i]).coerceIn(0f, 1f)
            Srgb.fromLinear(OkLab.toLinear(lab))
        }
    }

    /**
     * Base-layer tone curve (input OKLab L -> share of the panel's range), measured from Einky's
     * output on a captured photo (scripts/einky_analysis.py): deep shadows stay at the black ink,
     * mid-tones are lifted. A straight line here lifts deep shadows to a dark gray that only the
     * blue and green inks can build, which turns black objects blue.
     */
    private val CURVE_IN = floatArrayOf(0f, 0.15f, 0.25f, 0.35f, 0.45f, 0.55f, 0.65f, 0.75f, 0.85f, 1f)
    private val CURVE_OUT = floatArrayOf(0f, 0.007f, 0.075f, 0.17f, 0.325f, 0.49f, 0.67f, 0.81f, 0.88f, 1f)

    internal fun baseCurve(l: Float): Float {
        val x = l.coerceIn(0f, 1f)
        val i = CURVE_IN.indexOfFirst { it >= x }.coerceAtLeast(1)
        val t = (x - CURVE_IN[i - 1]) / (CURVE_IN[i] - CURVE_IN[i - 1])
        return CURVE_OUT[i - 1] + t * (CURVE_OUT[i] - CURVE_OUT[i - 1])
    }

    /** Self-guided filter (He, Sun, Tang 2010): edge-preserving smoothing in O(n). */
    internal fun guidedFilter(p: FloatArray, w: Int, h: Int, r: Int = RADIUS, eps: Float = EPS): FloatArray {
        val mean = box(p, w, h, r)
        val sq = box(FloatArray(p.size) { p[it] * p[it] }, w, h, r)
        val a = FloatArray(p.size) { i -> val v = sq[i] - mean[i] * mean[i]; v / (v + eps) }
        val b = FloatArray(p.size) { i -> mean[i] - a[i] * mean[i] }
        val ma = box(a, w, h, r)
        val mb = box(b, w, h, r)
        return FloatArray(p.size) { i -> ma[i] * p[i] + mb[i] }
    }

    private fun localStd(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val m = box(v, w, h, r)
        val s = box(FloatArray(v.size) { v[it] * v[it] }, w, h, r)
        return FloatArray(v.size) { i -> sqrt((s[i] - m[i] * m[i]).coerceAtLeast(0f)) }
    }

    /**
     * Per-pixel share of the extra detail gain: 1 everywhere except facial skin ([SKIN_SHARE]),
     * with the band through the eyes kept at 1. Faces are modelled as ellipses below the eye midpoint.
     */
    private fun faceWeights(faces: List<Face>, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h) { 1f }
        for (f in faces) {
            val cx = f.x; val cy = f.y + 0.5f * f.eyeDistance
            val rx = 1.25f * f.eyeDistance; val ry = 1.7f * f.eyeDistance
            for (y in maxOf(0, (cy - ry).toInt()) until minOf(h, (cy + ry).toInt() + 1)) {
                for (x in maxOf(0, (cx - rx).toInt()) until minOf(w, (cx + rx).toInt() + 1)) {
                    val dx = (x - cx) / rx; val dy = (y - cy) / ry
                    val inside = 1f - smoothstep(0.8f, 1f, sqrt(dx * dx + dy * dy))
                    val eyeBand = 1f - smoothstep(0.25f, 0.45f, kotlin.math.abs(y - f.y) / f.eyeDistance)
                    val share = 1f - inside * (1f - SKIN_SHARE) * (1f - eyeBand)
                    val i = y * w + x
                    out[i] = minOf(out[i], share)
                }
            }
        }
        return out
    }

    /** Mean over a (2r+1)^2 window with edge clamping, via summed-area table. */
    private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val sat = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
            }
        }
        return FloatArray(w * h) { i ->
            val x = i % w; val y = i / w
            val x0 = maxOf(0, x - r); val x1 = minOf(w, x + r + 1)
            val y0 = maxOf(0, y - r); val y1 = minOf(h, y + r + 1)
            val s = sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
            (s / ((x1 - x0) * (y1 - y0))).toFloat()
        }
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
