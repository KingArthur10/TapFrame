package dev.tapframe.render

import dev.tapframe.protocol.Frame
import dev.tapframe.protocol.Ink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLookTest {
    private val px = intArrayOf(0xFFFFFFFF.toInt(), 0xFF504557.toInt(), 0xFFAF4341.toInt(), 0xFF808080.toInt())

    @Test
    fun identityLeavesPixelsAlone() {
        assertSame(px, PreviewLook().apply(px))
    }

    @Test
    fun adjustmentsMoveColorsTheRightWay() {
        fun lab(c: Int) = OkLab.fromLinear(Srgb.toLinear(c and 0xFFFFFF))
        val dim = PreviewLook(brightness = 0.6f).apply(px)
        assertTrue(lab(dim[0])[0] < 0.9f)
        assertTrue(PreviewLook(warmth = 1f).apply(px).let { lab(it[3])[2] > lab(px[3])[2] + 0.01f })
        assertTrue(PreviewLook(tint = -1f).apply(px).let { lab(it[3])[1] < lab(px[3])[1] - 0.01f })
        val muted = PreviewLook(saturation = 0.5f).apply(px)
        assertTrue(lab(muted[2]).let { kotlin.math.hypot(it[1], it[2]) } < lab(px[2]).let { kotlin.math.hypot(it[1], it[2]) })
        PreviewLook(brightness = 0.6f, contrast = 1.3f).apply(px).forEach { assertEquals(0xFF, it ushr 24) }
    }

    @Test
    fun calibrationPatternUsesOnlyInksAndHasSolidSwatches() {
        val codes = PreviewLook.pattern()
        assertEquals(Frame.WIDTH * Frame.HEIGHT, codes.size)
        val inks = Ink.entries.map { it.code.toByte() }.toSet()
        assertTrue(codes.all { it in inks })
        // Solid white swatch: second sixth of buffer y, top band.
        assertTrue((60 until 90).all { y -> codes[y * Frame.WIDTH + 40] == Ink.WHITE.code.toByte() })
    }
}
