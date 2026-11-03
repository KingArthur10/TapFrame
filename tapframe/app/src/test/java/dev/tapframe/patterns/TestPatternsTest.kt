package dev.tapframe.patterns

import dev.tapframe.protocol.Apdu
import dev.tapframe.protocol.Frame
import dev.tapframe.protocol.Ink
import dev.tapframe.protocol.Lzo
import dev.tapframe.render.Preview
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestPatternsTest {
    private val documented = Ink.entries.map { it.code }.toSet()

    @Test
    fun patternsAreValidAndUploadable() {
        assertEquals(TestPatterns.all.size, TestPatterns.all.map { it.id }.toSet().size)
        for (p in TestPatterns.all) {
            val codes = p.render()
            assertEquals(p.id, Frame.WIDTH * Frame.HEIGHT, codes.size)
            val used = codes.map { it.toInt() }.toSet()
            assertTrue(p.id, used.all { it in 0..15 })
            if (p.id != "T7-codes") assertTrue("${p.id} uses $used", documented.containsAll(used))
            assertArrayEquals(p.id, codes, p.render())

            // Round-trip through the upload framing.
            val packed = Frame.packPixels(codes)
            val payload = Apdu.buildUpload(packed)
                .groupBy { it[2].toInt() to it[5].toInt() }.values
                .map { blocks -> Lzo.decompress(blocks.map { it.copyOfRange(7, it.size) }.reduce(ByteArray::plus)) }
                .reduce(ByteArray::plus)
            assertArrayEquals(p.id, packed, payload)
        }
        assertEquals((0..15).toSet(), TestPatterns.byId("T7-codes").render().map { it.toInt() }.toSet())
    }

    /** Writes the expected appearance of every pattern to build/patterns/<id>.png (1x and 2x). */
    @Test
    fun exportExpectedPngs() {
        val dir = File("build/patterns").apply { mkdirs() }
        for (p in TestPatterns.all) {
            File(dir, "${p.id}.codes").writeBytes(p.render()) // raw codes, buffer order, for scripts/
            val argb = Preview.argb(p.render())
            val img = BufferedImage(Frame.WIDTH, Frame.HEIGHT, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, Frame.WIDTH, Frame.HEIGHT, argb, 0, Frame.WIDTH)
            ImageIO.write(img, "png", File(dir, "${p.id}.png"))
            val big = BufferedImage(Frame.WIDTH * 2, Frame.HEIGHT * 2, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until big.height) for (x in 0 until big.width) big.setRGB(x, y, img.getRGB(x / 2, y / 2))
            ImageIO.write(big, "png", File(dir, "${p.id}@2x.png"))
        }
    }
}
