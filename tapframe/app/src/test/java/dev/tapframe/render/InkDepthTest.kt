package dev.tapframe.render

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkDepthTest {
    private val pw = PhotoPipeline.PORTRAIT_W
    private val ph = PhotoPipeline.PORTRAIT_H

    /** Mean color of the frame simulation for a flat [rgb] picture, in OKLab. */
    private fun shown(rgb: Int, s: Settings): FloatArray {
        val sim = FrameSim.simulate(PhotoPipeline.render(IntArray(pw * ph) { rgb }, s), 400, 304)
        val acc = FloatArray(3)
        for (c in sim) { val l = Srgb.toLinear(c); for (k in 0..2) acc[k] += l[k] }
        return OkLab.fromLinear(FloatArray(3) { acc[it] / sim.size })
    }

    private fun chroma(lab: FloatArray) = hypot(lab[1], lab[2])

    @Test
    fun skiesReadBluer() {
        for (tone in listOf(ToneMap.VIVID, ToneMap.BALANCED)) for (sky in intArrayOf(0x96BEE6, 0x6FA3DC)) {
            val off = shown(sky, Settings(Dither.Method.FLOYD_STEINBERG, tone))
            val on = shown(sky, Settings(Dither.Method.FLOYD_STEINBERG, tone, blueDepth = 1f))
            println("%s #%06X chroma %.3f -> %.3f, L %.2f -> %.2f".format(tone.label, sky, chroma(off), chroma(on), off[0], on[0]))
            assertTrue(chroma(on) > 1.8f * chroma(off))
        }
    }

    @Test
    fun graysSkinAndGrassUnchanged() {
        for (c in intArrayOf(0xA0A0A0, 0xC29682, 0xEBC8B4, 0xA06E55, 0x5A8C3C)) assertEquals("%06X".format(c), c, GamutMap.MEASURED.enrich(c, 1f))
    }

    @Test
    fun skinIsLeftAloneByEveryDepth() {
        val all = floatArrayOf(1f, 1f, 1f, 1f)
        for (c in intArrayOf(0xC29682, 0xEBC8B4, 0xA06E55, 0x5A3C2D)) assertEquals("%06X".format(c), c, GamutMap.MEASURED.enrich(c, all))
    }

    @Test
    fun eachBandStrengthensItsColors() {
        val s = Settings(Dither.Method.FLOYD_STEINBERG, ToneMap.BALANCED)
        for ((rgb, deep) in listOf(
            0xB0A040 to s.copy(yellowDepth = 1f),   // dull yellow
            0xC86464 to s.copy(redDepth = 1f),      // light red
        )) {
            val off = chroma(shown(rgb, s)); val on = chroma(shown(rgb, deep))
            println("#%06X chroma %.3f -> %.3f".format(rgb, off, on))
            assertTrue("%06X".format(rgb), on > off)
        }
    }

    /** Green depth moves grass onto the (dark) green ink; whether that reads better is for the eye on the frame. */
    @Test
    fun greenDepthUsesMoreGreenInk() {
        val s = Settings(Dither.Method.FLOYD_STEINBERG, ToneMap.BALANCED)
        fun greenShare(settings: Settings) =
            PhotoPipeline.render(IntArray(pw * ph) { 0x5A8C3C }, settings).count { it.toInt() == dev.tapframe.protocol.Ink.GREEN.code }
        assertTrue(greenShare(s.copy(greenDepth = 1f)) > greenShare(s))
    }
}
