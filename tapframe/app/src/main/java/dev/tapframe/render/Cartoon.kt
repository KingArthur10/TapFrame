package dev.tapframe.render

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Cartoon stylization for the e-ink panel: flat color regions (which need little or no dithering)
 * plus inked outlines, which suit a six-color panel far better than photographic gradients.
 *
 * Classic mode: edge-preserving smoothing (iterated guided filter on each OKLab channel, guided by
 * lightness), then lightness posterized into a few cel-shading bands. AI mode receives an already
 * cartoonized image (see the app's CartoonModel) and skips straight to the bands. Outlines come
 * from XDoG (eXtended Difference of Gaussians) on lightness and are drawn in black ink after
 * the colors are chosen.
 */
object Cartoon {
    /** Number of lightness bands for cel shading. */
    private const val BANDS = 5

    class Result(val rgb: IntArray, val outline: BooleanArray)

    /**
     * @param smooth whether to run the edge-preserving smoothing (classic mode).
     * @param outline outline strength: 0 = none, 1 = normal, up to 3 = heavy.
     */
    fun stylize(rgb: IntArray, w: Int, h: Int, smooth: Boolean, outline: Float): Result {
        val labs = Array(rgb.size) { OkLab.fromLinear(Srgb.toLinear(rgb[it])) }
        var l = FloatArray(rgb.size) { labs[it][0] }
        var a = FloatArray(rgb.size) { labs[it][1] }
        var b = FloatArray(rgb.size) { labs[it][2] }
        if (smooth) repeat(2) {
            a = guided(l, a, w, h)
            b = guided(l, b, w, h)
            l = LocalToneMap.guidedFilter(l, w, h, r = 4, eps = 0.003f)
        }
        val edges = if (outline > 0f) xdog(l, w, h, outline) else BooleanArray(rgb.size)
        val out = IntArray(rgb.size) { i ->
            // Cel shading: snap lightness to bands, softened a little so band edges are not jagged.
            val banded = (l[i] * BANDS).roundToInt() / BANDS.toFloat()
            val lab = floatArrayOf(0.7f * banded + 0.3f * l[i], a[i], b[i])
            Srgb.fromLinear(OkLab.toLinear(lab))
        }
        return Result(out, edges)
    }

    /** Guided filter of [p] with guide [g] (He et al.), radius 4. */
    private fun guided(g: FloatArray, p: FloatArray, w: Int, h: Int, r: Int = 4, eps: Float = 0.003f): FloatArray {
        val mg = box(g, w, h, r); val mp = box(p, w, h, r)
        val mgp = box(FloatArray(g.size) { g[it] * p[it] }, w, h, r)
        val mgg = box(FloatArray(g.size) { g[it] * g[it] }, w, h, r)
        val av = FloatArray(g.size) { i -> (mgp[i] - mg[i] * mp[i]) / (mgg[i] - mg[i] * mg[i] + eps) }
        val bv = FloatArray(g.size) { i -> mp[i] - av[i] * mg[i] }
        val ma = box(av, w, h, r); val mb = box(bv, w, h, r)
        return FloatArray(g.size) { i -> ma[i] * g[i] + mb[i] }
    }

    /**
     * XDoG outlines (Winnemöller et al. 2012): difference of two Gaussians on lightness, thresholded
     * where it turns sharply negative (the dark side of an edge). [strength] lowers the threshold.
     */
    private fun xdog(l: FloatArray, w: Int, h: Int, strength: Float): BooleanArray {
        val g1 = gauss(l, w, h, 1.0f)
        val g2 = gauss(l, w, h, 1.6f)
        val threshold = -0.012f / strength.coerceAtLeast(0.1f)
        return BooleanArray(l.size) { i -> (g1[i] - 0.98f * g2[i]) - 0.02f * l[i] < threshold }
    }

    private fun gauss(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val r = (sigma * 3).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }.let { k -> val s = k.sum(); FloatArray(k.size) { k[it] / s } }
        val tmp = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (d in -r..r) s += src[y * w + (x + d).coerceIn(0, w - 1)] * k[d + r]
            tmp[y * w + x] = s
        }
        return FloatArray(src.size) { i ->
            val x = i % w; val y = i / w
            var s = 0f
            for (d in -r..r) s += tmp[(y + d).coerceIn(0, h - 1) * w + x] * k[d + r]
            s
        }
    }

    private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray = LocalToneMap.boxMean(src, w, h, r)
}
