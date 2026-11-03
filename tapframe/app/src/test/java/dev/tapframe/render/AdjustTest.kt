package dev.tapframe.render

import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustTest {
    private val base = Settings(Dither.Method.NONE)
    private fun l(rgb: Int) = OkLab.fromLinear(Srgb.toLinear(rgb))[0]
    private fun gray(v: Int) = (v shl 16) or (v shl 8) or v

    @Test
    fun highlightsAndShadowsActOnTheirHalfOnly() {
        val dark = gray(60); val light = gray(210)
        val hi = Adjust.apply(intArrayOf(dark, light), base.copy(highlights = -1f))
        assertEquals(dark, hi[0])
        assertTrue(l(hi[1]) < l(light) - 0.05f)
        val sh = Adjust.apply(intArrayOf(dark, light), base.copy(shadows = 1f))
        assertTrue(l(sh[0]) > l(dark) + 0.05f)
        assertEquals(light, sh[1])
    }

    @Test
    fun toneControlsStayMonotonic() {
        for (s in listOf(base.copy(highlights = 1f, shadows = 1f), base.copy(highlights = -1f, shadows = -1f))) {
            val out = Adjust.apply(IntArray(256) { gray(it) }, s).map(::l)
            assertTrue(out.zipWithNext().all { (a, b) -> b >= a - 1e-3f })
        }
    }

    @Test
    fun hueRotates() {
        val red = 0xC04040
        val out = Adjust.apply(intArrayOf(red), base.copy(hue = 90f))[0]
        fun h(c: Int) = OkLab.fromLinear(Srgb.toLinear(c)).let { Math.toDegrees(atan2(it[2], it[1]).toDouble()) }
        val d = ((h(out) - h(red) + 540) % 360) - 180
        assertEquals(90.0, d, 6.0)
    }
}
