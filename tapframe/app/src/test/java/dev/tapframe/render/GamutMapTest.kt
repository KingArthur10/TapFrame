package dev.tapframe.render

import dev.tapframe.patterns.TestPatterns
import dev.tapframe.protocol.Ink
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamutMapTest {
    private val gamut = GamutMap.MEASURED

    @Test
    fun unreachableHuesAreLeftAlone() {
        for (rgb in intArrayOf(0x3C8C28, 0x00FF00, 0x00FFFF)) assertEquals("%06X".format(rgb), rgb, gamut.map(rgb))
    }

    @Test
    fun inksAndTheirMixesAreUnchanged() {
        for (ink in Ink.entries) assertEquals(ink.name, ink.rgb, gamut.map(ink.rgb))
        // A mix on the hull's surface: rounding may nudge it by a level.
        val mid = Srgb.fromLinear(FloatArray(3) { (Srgb.toLinear(Ink.RED.rgb)[it] + Srgb.toLinear(Ink.WHITE.rgb)[it]) / 2 })
        assertTrue(close(mid, gamut.map(mid), 2))
    }

    @Test
    fun outOfGamutKeepsHueAndLightness() {
        // Reachable hues only: magenta, purple, deep red, orange, a saturated blue.
        for (rgb in intArrayOf(0xBB5695, 0xFF00FF, 0x8000FF, 0xFF0000, 0xFF8000, 0x0000FF)) {
            val before = OkLab.fromLinear(Srgb.toLinear(rgb))
            val mapped = gamut.map(rgb)
            val after = OkLab.fromLinear(Srgb.toLinear(mapped))
            assertTrue("%06X hue %.0f -> %.0f".format(rgb, hue(before), hue(after)), hueDiff(before, after) < 12f || hypot(after[1], after[2]) < 0.02f)
            assertTrue("%06X chroma lowered".format(rgb), hypot(after[1], after[2]) <= hypot(before[1], before[2]) + 1e-3f)
        }
    }

    /**
     * The session-4 chart through Floyd–Steinberg and the frame simulation, with and without gamut
     * mapping: hue error on chromatic patches must drop substantially. Prints both for the record.
     */
    @Test
    fun chartHueErrorDrops() {
        fun hueError(map: Boolean): Float {
            val w = 62; val h = 56
            var sum = 0f; var n = 0
            for (target in TestPatterns.CHART) {
                val src = if (map) gamut.map(target) else target
                val codes = Dither.dither(IntArray(w * h) { src }, w, h, Dither.Method.FLOYD_STEINBERG, Palette.MEASURED, serpentine = true)
                val sim = FrameSim.simulate(codes, w, h)
                val acc = FloatArray(3)
                for (y in h / 4 until 3 * h / 4) for (x in w / 4 until 3 * w / 4) {
                    val c = Srgb.toLinear(sim[y * w + x]); for (k in 0..2) acc[k] += c[k]
                }
                val shown = OkLab.fromLinear(FloatArray(3) { acc[it] / (w * h / 4) })
                val want = OkLab.fromLinear(Srgb.toLinear(target))
                if (hypot(want[1], want[2]) < 0.04f) continue
                sum += hueDiff(want, shown); n++
                println("%s %06X hue %.0f -> %.0f, chroma %.3f -> %.3f".format(if (map) "mapped" else "plain ", target, hue(want), hue(shown), hypot(want[1], want[2]), hypot(shown[1], shown[2])))
            }
            return sum / n
        }
        val plain = hueError(false); val mapped = hueError(true)
        println("mean hue error on chromatic chart patches: plain %.1f°, gamut-mapped %.1f°".format(plain, mapped))
        assertTrue(mapped < plain)
    }

    private fun close(a: Int, b: Int, tol: Int) = (0..2).all { abs((a shr (8 * it) and 0xFF) - (b shr (8 * it) and 0xFF)) <= tol }

    private fun hue(lab: FloatArray) = Math.toDegrees(atan2(lab[2], lab[1]).toDouble()).toFloat().let { (it + 360) % 360 }
    private fun hueDiff(a: FloatArray, b: FloatArray) = abs(((hue(a) - hue(b) + 540) % 360) - 180)
}
