package dev.tapframe.render

import dev.tapframe.protocol.Frame
import dev.tapframe.protocol.Ink
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPipelineTest {
    private val pw = PhotoPipeline.PORTRAIT_W
    private val ph = PhotoPipeline.PORTRAIT_H
    private val inks = Ink.entries.map { it.code }.toSet()

    private fun channelsClose(a: Int, b: Int, tol: Int) =
        (0..2).all { abs((a shr (8 * it) and 0xFF) - (b shr (8 * it) and 0xFF)) <= tol }

    @Test
    fun fitPortraitCropsCenterAndScales() {
        // 1000 x 400 landscape: left/right thirds are cropped away; the center is green.
        val w = 1000; val h = 400
        val src = IntArray(w * h) { i -> if (i % w in 400 until 600) 0x00FF00 else 0xFF0000 }
        val out = PhotoPipeline.fitPortrait(src, w, h)
        assertEquals(pw * ph, out.size)
        assertTrue(channelsClose(out[ph / 2 * pw + pw / 2], 0x00FF00, 1))
        // Exact 2x downscale of a 608 x 800 image with a red top-left quadrant.
        val src2 = IntArray(608 * 800) { i -> if (i % 608 < 304 && i / 608 < 400) 0xFF0000 else 0x0000FF }
        val out2 = PhotoPipeline.fitPortrait(src2, 608, 800)
        assertEquals(0xFF0000, out2[0])
        assertEquals(0x0000FF, out2[ph * pw - 1])
    }

    @Test
    fun bufferTransposeRoundTrips() {
        val portrait = IntArray(pw * ph) { it }
        val buffer = PhotoPipeline.toBuffer(portrait)
        // portrait (col 10, row 20) -> buffer (x 20, y 10)
        assertEquals(20 * pw + 10, buffer[10 * Frame.WIDTH + 20])
        assertEquals(portrait.toList(), PhotoPipeline.toPortrait(buffer).toList())
    }

    @Test
    fun naturalToneMapUsesPanelRangeAndLimitsChroma() {
        // Four 32x32 blocks (white, black / red, green), large enough for the 10 px guided filter.
        val w = 64; val h = 64
        val img = IntArray(w * h) { i -> intArrayOf(0xFFFFFF, 0x000000, 0xFF0000, 0x00FF00)[(i / w / 32) * 2 + (i % w) / 32] }
        val mapped = LocalToneMap.apply(img, w, h, ToneMap.NATURAL.baseContrast, ToneMap.NATURAL.detailGain, emptyList())
        val l = { c: Int -> OkLab.fromLinear(Srgb.toLinear(c))[0] }
        val center = { bx: Int, by: Int -> mapped[(by * 32 + 16) * w + bx * 32 + 16] }
        assertTrue(l(center(0, 0)) > 0.97f)                               // white stays white
        assertEquals(l(Ink.BLACK.rgb), l(center(1, 0)), 0.03f)            // black -> panel black
        for (c in listOf(center(0, 1), center(1, 1))) {
            val lab = OkLab.fromLinear(Srgb.toLinear(c))
            assertTrue("chroma ${hypot(lab[1], lab[2])}", hypot(lab[1], lab[2]) <= 0.135f)
        }
    }

    @Test
    fun localToneMapBoostsTextureButNotFlatAreas() {
        val w = 64; val h = 64
        // Left half flat gray, right half fine texture.
        val img = IntArray(w * h) { i -> val x = i % w; if (x < 32) 0x808080 else if ((x + i / w) % 2 == 0) 0x9A9A9A else 0x666666 }
        val l = { c: Int -> OkLab.fromLinear(Srgb.toLinear(c))[0] }
        fun spread(out: IntArray, x0: Int) = (16 until 48).flatMap { y -> (x0 + 4 until x0 + 28).map { l(out[y * w + it]) } }.let { it.max() - it.min() }
        val natural = LocalToneMap.apply(img, w, h, 1f, 1f, emptyList())
        val strong = LocalToneMap.apply(img, w, h, 0.7f, 2.6f, emptyList())
        assertTrue(spread(strong, 32) > spread(natural, 32) * 1.5f)        // texture boosted
        assertTrue(spread(strong, 0) < 0.01f)                               // flat stays flat
    }

    @Test
    fun guidedFilterKeepsEdges() {
        val w = 40; val h = 10
        val step = FloatArray(w * h) { if (it % w < 20) 0.2f else 0.8f }
        val out = LocalToneMap.guidedFilter(step, w, h, r = 4, eps = 0.01f)
        assertEquals(0.2f, out[5 * w + 17], 0.02f)
        assertEquals(0.8f, out[5 * w + 22], 0.02f)
    }

    @Test
    fun everyAlgorithmProducesValidCodes() {
        val photo = syntheticPhoto()
        for (m in Dither.Method.entries) {
            val codes = PhotoPipeline.render(photo, ContentType.PEOPLE.defaults.copy(method = m))
            assertEquals(Frame.WIDTH * Frame.HEIGHT, codes.size)
            assertTrue(m.name, codes.all { it.toInt() in inks })
            assertTrue(m.name, codes.toSet().size >= 3)
        }
    }

    @Test
    fun everyToneMapProducesValidCodesAndVividUsesMoreBlackAndWhite() {
        val photo = syntheticPhoto()
        val share = mutableMapOf<ToneMap, Double>()
        for (t in ToneMap.entries) {
            val codes = PhotoPipeline.render(photo, Settings(Dither.Method.FLOYD_STEINBERG, t))
            assertTrue(t.name, codes.all { it.toInt() in inks })
            share[t] = codes.count { it.toInt() == Ink.BLACK.code || it.toInt() == Ink.WHITE.code }.toDouble() / codes.size
        }
        assertTrue("$share", share[ToneMap.VIVID]!! > share[ToneMap.NATURAL]!!)
    }

    @Test
    fun neutralAdjustmentIsIdentityAndContrastSpreadsTones() {
        val rgb = intArrayOf(0x202020, 0x808080, 0xE0E0E0, 0xC06040)
        val neutral = Settings(Dither.Method.NONE)
        assertTrue(Adjust.apply(rgb, neutral).contentEquals(rgb))
        val strong = Adjust.apply(rgb, neutral.copy(contrast = 1f))
        assertTrue((strong[0] and 0xFF) < 0x20 && (strong[2] and 0xFF) > 0xE0)
        val bright = Adjust.apply(rgb, neutral.copy(brightness = 0.2f))
        assertTrue((bright[1] and 0xFF) > 0x80)
    }

    @Test
    fun guessesContentType() {
        val flat = IntArray(300 * 200) { if (it % 300 < 150) 0xFF0000 else 0x0000FF }
        assertEquals(ContentType.PIXEL_ART, ContentType.guess(flat, 300, 200, faces = 0))
        val bigFlat = IntArray(1200 * 900) { if (it % 1200 < 600) 0xFF0000 else 0x0000FF }
        assertEquals(ContentType.GRAPHIC, ContentType.guess(bigFlat, 1200, 900, faces = 0))
        val noisy = IntArray(1200 * 900) { (it * 2654435761L).toInt() and 0xFFFFFF }
        assertEquals(ContentType.LANDSCAPE, ContentType.guess(noisy, 1200, 900, faces = 0))
        assertEquals(ContentType.PEOPLE, ContentType.guess(noisy, 1200, 900, faces = 2))
        val ranked = ContentType.rank(noisy, 1200, 900, faces = 2)
        assertEquals(ContentType.entries.toSet(), ranked.toSet())
        assertEquals(listOf(ContentType.PEOPLE, ContentType.LANDSCAPE), ranked.take(2))
    }

    @Test
    fun frameSimLeavesSolidAreasAndFlattensMixedOnes() {
        val solid = ByteArray(Frame.WIDTH * Frame.HEIGHT) { Ink.BLACK.code.toByte() }
        val sim = FrameSim.simulate(solid, Frame.WIDTH, Frame.HEIGHT)
        assertTrue(channelsClose(sim[5000], Ink.BLACK.rgb, 1))
        // A black/white checker shows lighter than the plain average of its inks.
        val checker = ByteArray(Frame.WIDTH * Frame.HEIGHT) { i ->
            (if ((i % Frame.WIDTH + i / Frame.WIDTH) % 2 == 0) Ink.BLACK.code else Ink.WHITE.code).toByte()
        }
        val l = OkLab.fromLinear(Srgb.toLinear(FrameSim.simulate(checker, Frame.WIDTH, Frame.HEIGHT)[5000]))[0]
        val avg = OkLab.fromLinear(FloatArray(3) { (Srgb.toLinear(Ink.BLACK.rgb)[it] + 1f) / 2 })[0]
        assertTrue("$l vs $avg", l > avg - 0.05f)
    }

    /**
     * Writes the synthetic scene through every tone map (rows) and algorithm (columns) as simulated
     * frame previews to build/photos/algorithms.png.
     */
    @Test
    fun exportAlgorithmSheet() {
        val dir = File("build/photos").apply { mkdirs() }
        val photo = syntheticPhoto()
        val methods = Dither.Method.entries
        val tones = ToneMap.entries
        val sheet = BufferedImage(pw * methods.size, ph * tones.size, BufferedImage.TYPE_INT_RGB)
        tones.forEachIndexed { r, t ->
            methods.forEachIndexed { i, m ->
                val codes = PhotoPipeline.render(photo, Settings(m, t))
                sheet.setRGB(pw * i, ph * r, pw, ph, PhotoPipeline.simulatedPortrait(codes), 0, pw)
            }
        }
        ImageIO.write(sheet, "png", File(dir, "algorithms.png"))
    }

    /**
     * Optional: with TAPFRAME_PHOTO pointing at a 304x400 portrait PNG, writes the four tone presets
     * (simulated frame view, plus ink maps below) to build/photos/real_tones.png. Skipped otherwise,
     * so personal photos never become test fixtures.
     */
    @Test
    fun exportRealPhotoTones() {
        val path = System.getenv("TAPFRAME_PHOTO") ?: return
        val img = ImageIO.read(File(path))
        val photo = IntArray(pw * ph).also { img.getRGB(0, 0, pw, ph, it, 0, pw) }.map { it and 0xFFFFFF }.toIntArray()
        val tones = ToneMap.entries
        val sheet = BufferedImage(pw * tones.size, ph * 2, BufferedImage.TYPE_INT_RGB)
        tones.forEachIndexed { i, t ->
            val codes = PhotoPipeline.render(photo, Settings(Dither.Method.FLOYD_STEINBERG, t))
            sheet.setRGB(pw * i, 0, pw, ph, PhotoPipeline.simulatedPortrait(codes), 0, pw)
            sheet.setRGB(pw * i, ph, pw, ph, Preview.portraitArgb(codes), 0, pw)
        }
        ImageIO.write(sheet, "png", File(File("build/photos").apply { mkdirs() }, "real_tones.png"))
    }

    /**
     * Optional: with TAPFRAME_PHOTO (304x400 PNG), writes Natural and Balanced with hue preservation
     * off and on (simulated frame view above, ink maps below) to build/photos/real_hue.png.
     */
    @Test
    fun exportRealPhotoHue() {
        val path = System.getenv("TAPFRAME_PHOTO") ?: return
        val img = ImageIO.read(File(path))
        val photo = IntArray(pw * ph).also { img.getRGB(0, 0, pw, ph, it, 0, pw) }.map { it and 0xFFFFFF }.toIntArray()
        val variants = listOf(ToneMap.NATURAL, ToneMap.BALANCED).flatMap { t ->
            listOf(false, true).map { Settings(Dither.Method.FLOYD_STEINBERG, t, keepHue = it) }
        }
        val sheet = BufferedImage(pw * variants.size, ph * 2, BufferedImage.TYPE_INT_RGB)
        variants.forEachIndexed { i, s ->
            val codes = PhotoPipeline.render(photo, s)
            sheet.setRGB(pw * i, 0, pw, ph, PhotoPipeline.simulatedPortrait(codes), 0, pw)
            sheet.setRGB(pw * i, ph, pw, ph, Preview.portraitArgb(codes), 0, pw)
        }
        ImageIO.write(sheet, "png", File(File("build/photos").apply { mkdirs() }, "real_hue.png"))
    }

    /**
     * Optional: with TAPFRAME_PHOTO (304x400 PNG) and TAPFRAME_AI (the AI model's 304x400 output),
     * writes cartoon variants (simulated frame view above, ink maps below) to build/photos/real_cartoon.png.
     */
    @Test
    fun exportRealPhotoCartoon() {
        val photoPath = System.getenv("TAPFRAME_PHOTO") ?: return
        fun load(path: String) = ImageIO.read(File(path)).let { img -> IntArray(pw * ph).also { img.getRGB(0, 0, pw, ph, it, 0, pw) }.map { it and 0xFFFFFF }.toIntArray() }
        val photo = load(photoPath)
        val ai = System.getenv("TAPFRAME_AI")?.let(::load)
        val variants = listOf(
            Settings(Dither.Method.NONE, effect = Effect.CARTOON),
            Settings(Dither.Method.BLUE_NOISE, effect = Effect.CARTOON),
        ) + if (ai == null) emptyList() else listOf(
            Settings(Dither.Method.NONE, effect = Effect.AI_CARTOON),
            Settings(Dither.Method.BLUE_NOISE, effect = Effect.AI_CARTOON),
        )
        val sheet = BufferedImage(pw * variants.size, ph * 2, BufferedImage.TYPE_INT_RGB)
        variants.forEachIndexed { i, s ->
            val codes = PhotoPipeline.render(photo, s, aiCartoon = ai)
            sheet.setRGB(pw * i, 0, pw, ph, PhotoPipeline.simulatedPortrait(codes), 0, pw)
            sheet.setRGB(pw * i, ph, pw, ph, Preview.portraitArgb(codes), 0, pw)
        }
        ImageIO.write(sheet, "png", File(File("build/photos").apply { mkdirs() }, "real_cartoon.png"))
    }

    /** Portrait test scene: sky gradient, skin-tone patches, gray ramp, saturated color bars. */
    private fun syntheticPhoto(): IntArray {
        val skins = intArrayOf(0xF1C27D, 0xE0AC69, 0xC68642, 0x8D5524, 0xFFDBAC)
        val bars = intArrayOf(0xFF0000, 0xFF8000, 0xFFFF00, 0x00C000, 0x00A0FF, 0x0000FF, 0x8000FF)
        return IntArray(pw * ph) { i ->
            val x = i % pw; val y = i / pw
            fun lerp(a: Int, b: Int, t: Float) = (0..2).fold(0) { acc, k ->
                val ca = a shr (8 * k) and 0xFF; val cb = b shr (8 * k) and 0xFF
                acc or ((ca + (cb - ca) * t).toInt() shl (8 * k))
            }
            when {
                y < 120 -> lerp(0x1E5AA8, 0xBFE3FF, y / 120f)
                y < 220 -> skins[x * skins.size / pw]
                y < 300 -> (x * 255 / (pw - 1)).let { (it shl 16) or (it shl 8) or it }
                else -> lerp(bars[x * bars.size / pw], 0x000000, ((y - 300) / 100f) * 0.6f)
            }
        }
    }
}
