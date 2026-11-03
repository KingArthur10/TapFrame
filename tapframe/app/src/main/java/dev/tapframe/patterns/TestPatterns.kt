package dev.tapframe.patterns

import dev.tapframe.protocol.Frame
import dev.tapframe.protocol.Ink
import dev.tapframe.render.Canvas
import dev.tapframe.render.Dither
import kotlin.math.roundToInt

/** Panel characterization patterns; see TESTPLAN.md for what each one measures. */
object TestPatterns {

    class Pattern(val id: String, val title: String, val render: () -> ByteArray)

    /** Ink order used in all labels and matrices. */
    private val INKS = listOf(Ink.BLACK, Ink.WHITE, Ink.YELLOW, Ink.RED, Ink.BLUE, Ink.GREEN)
    private val Ink.letter get() = if (this == Ink.BLACK) "K" else name.take(1)

    val all: List<Pattern> = listOf(
        Pattern("T0-bars", "T0 Orientation bars") { Frame.testPattern() },
        Pattern("T1-palette", "T1 Palette swatches") { palette() },
        Pattern("T2-geometry", "T2 Geometry and edges") { geometry() },
        Pattern("T3-detail", "T3 Fine detail") { detail() },
        Pattern("T4a-mix-checker", "T4a Pair mixes: checkers") { pairMatrix(stripes = false) },
        Pattern("T4b-mix-stripes", "T4b Pair mixes: stripes") { pairMatrix(stripes = true) },
        Pattern("T5-ramps", "T5 Dither ramps") { ramps() },
    ) + INKS.map { ink ->
        Pattern("T6-solid-${ink.letter}", "T6 Solid ${ink.name.lowercase()}") { Canvas(background = ink.code).codes }
    } + listOf(
        Pattern("T6-checker", "T6 Big checker") { bigChecker(inverted = false) },
        Pattern("T6-checker-inv", "T6 Big checker inverted") { bigChecker(inverted = true) },
        Pattern("T7-codes", "T7 All 16 pixel codes") { allCodes() },
        Pattern("T8-black-shapes", "T8 Black dot shapes on colors") { blackShapes() },
    )

    fun byId(id: String): Pattern = all.first { it.id == id }

    /** Six large swatches separated by black gutters, labelled in a contrasting ink. */
    private fun palette(): ByteArray {
        val c = Canvas(background = Ink.BLACK.code)
        val w = 128; val h = 146; val gap = 4
        INKS.forEachIndexed { i, ink ->
            val x = gap + (i % 3) * (w + gap)
            val y = gap + (i / 3) * (h + gap)
            c.fill(x, y, w, h, ink.code)
            val label = if (ink == Ink.BLACK || ink == Ink.BLUE) Ink.WHITE else Ink.BLACK
            c.text(x + 4, y + 4, ink.letter, label.code)
        }
        return c.codes
    }

    /** Edge rings, 10 px ticks, coloured corner markers, diagonals and a circle to check aspect. */
    private fun geometry(): ByteArray {
        val c = Canvas()
        val w = c.width; val h = c.height
        c.outline(0, 0, w, h, Ink.BLACK.code)
        c.outline(2, 2, w - 4, h - 4, Ink.RED.code)
        for (x in 0 until w step 10) {
            val len = if (x % 100 == 0) 14 else if (x % 50 == 0) 10 else 6
            c.fill(x, 4, 1, len, Ink.BLACK.code); c.fill(x, h - 4 - len, 1, len, Ink.BLACK.code)
        }
        for (y in 0 until h step 10) {
            val len = if (y % 100 == 0) 14 else if (y % 50 == 0) 10 else 6
            c.fill(4, y, len, 1, Ink.BLACK.code); c.fill(w - 4 - len, y, len, 1, Ink.BLACK.code)
        }
        val m = 24
        c.fill(20, 20, m, m, Ink.BLACK.code); c.text(20 + m + 4, 20, "TL", Ink.BLACK.code)
        c.fill(w - 20 - m, 20, m, m, Ink.RED.code); c.text(w - 20 - m - 4 - 22, 20, "TR", Ink.RED.code)
        c.fill(20, h - 20 - m, m, m, Ink.BLUE.code); c.text(20 + m + 4, h - 20 - 10, "BL", Ink.BLUE.code)
        c.fill(w - 20 - m, h - 20 - m, m, m, Ink.GREEN.code); c.text(w - 20 - m - 4 - 22, h - 20 - 10, "BR", Ink.GREEN.code)
        c.line(0, 0, w - 1, h - 1, Ink.BLUE.code)
        c.line(w - 1, 0, 0, h - 1, Ink.BLUE.code)
        c.circle(w / 2, h / 2, 100, Ink.BLACK.code)
        c.fill(w / 2 - 15, h / 2, 31, 1, Ink.RED.code); c.fill(w / 2, h / 2 - 15, 1, 31, Ink.RED.code)
        val label = "400X304"
        c.text(w / 2 - 27, h / 2 + 24, label, Ink.BLACK.code)
        return c.codes
    }

    /**
     * Ten 30 px bands: each ink on white, then each ink on black. Columns: isolated pixels, 1 px
     * horizontal lines, 1 px vertical lines, isolated 2x2 dots.
     */
    private fun detail(): ByteArray {
        val c = Canvas()
        val bands = INKS.filter { it != Ink.WHITE }.map { it to Ink.WHITE } +
            INKS.filter { it != Ink.BLACK }.map { it to Ink.BLACK }
        bands.forEachIndexed { b, (ink, bg) ->
            val y0 = b * 30
            c.fill(0, y0, c.width, 30, bg.code)
            c.text(2, y0 + 10, ink.letter, ink.code)
            c.fill(16, y0 + 3, 96, 24) { x, y -> if (x % 4 == 0 && (y - y0) % 4 == 3) ink.code else bg.code }
            c.fill(112, y0 + 3, 96, 24) { _, y -> if ((y - y0) % 3 == 0) ink.code else bg.code }
            c.fill(208, y0 + 3, 96, 24) { x, _ -> if (x % 3 == 0) ink.code else bg.code }
            c.fill(304, y0 + 3, 96, 24) { x, y -> if ((x - 304) % 6 < 2 && (y - y0 - 3) % 6 < 2) ink.code else bg.code }
        }
        return c.codes
    }

    /**
     * 6x6 matrix of 64x48 tiles: diagonal = solid ink; above the diagonal = 1 px checker
     * (or horizontal stripes); below = 2x2 checker (or vertical stripes).
     */
    private fun pairMatrix(stripes: Boolean): ByteArray {
        val c = Canvas()
        val tw = 64; val th = 48; val m = 16
        INKS.forEachIndexed { i, ink ->
            c.text(m + i * tw + 29, 3, ink.letter, Ink.BLACK.code)
            c.text(5, m + i * th + 19, ink.letter, Ink.BLACK.code)
        }
        for (i in INKS.indices) for (j in INKS.indices) {
            val a = INKS[i].code; val b = INKS[j].code
            c.fill(m + j * tw, m + i * th, tw, th) { x, y ->
                val first = when {
                    i == j -> true
                    i < j -> if (stripes) y % 2 == 0 else (x + y) % 2 == 0
                    else -> if (stripes) x % 2 == 0 else (x / 2 + y / 2) % 2 == 0
                }
                if (first) a else b
            }
        }
        return c.codes
    }

    /** Gray and hue ramps, each rendered with several dither methods, labelled on the left. */
    private fun ramps(): ByteArray {
        val c = Canvas()
        val x0 = 16; val w = c.width - x0; val bandH = 43
        fun gray(x: Int) = (x * 255f / (w - 1)).roundToInt().let { (it shl 16) or (it shl 8) or it }
        fun hue(x: Int): Int {
            // Fully saturated hue wheel, red -> yellow -> green -> cyan -> blue -> magenta.
            val h = x * 6f / w
            val f = h - h.toInt()
            val (r, g, b) = when (h.toInt()) {
                0 -> Triple(1f, f, 0f)
                1 -> Triple(1 - f, 1f, 0f)
                2 -> Triple(0f, 1f, f)
                3 -> Triple(0f, 1 - f, 1f)
                4 -> Triple(f, 0f, 1f)
                else -> Triple(1f, 0f, 1 - f)
            }
            return ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
        }
        val bands = listOf(
            Triple("N", ::gray, Dither.Method.NONE),
            Triple("FS", ::gray, Dither.Method.FLOYD_STEINBERG),
            Triple("AT", ::gray, Dither.Method.ATKINSON),
            Triple("BA", ::gray, Dither.Method.BAYER),
            Triple("FS", ::hue, Dither.Method.FLOYD_STEINBERG),
            Triple("AT", ::hue, Dither.Method.ATKINSON),
            Triple("BA", ::hue, Dither.Method.BAYER),
        )
        bands.forEachIndexed { b, (label, color, method) ->
            val y0 = b * bandH
            val rgb = IntArray(w * bandH) { color(it % w) }
            val band = Canvas(w, bandH)
            Dither.dither(rgb, w, bandH, method).copyInto(band.codes)
            c.blit(x0, y0, band)
            c.text(1, y0 + 19, label, Ink.BLACK.code, scale = 1)
        }
        return c.codes
    }

    /** 50 px black/white checkerboard, for ghosting checks when alternated with its inverse. */
    private fun bigChecker(inverted: Boolean): ByteArray {
        val c = Canvas()
        c.fill(0, 0, c.width, c.height) { x, y ->
            if (((x / 50 + y / 50) % 2 == 0) != inverted) Ink.BLACK.code else Ink.WHITE.code
        }
        return c.codes
    }

    /**
     * Black dots at 25% coverage in different shapes on each non-black ink, to see which shapes
     * render as the predicted mix. Rows: W, Y, R, B, G backgrounds. Columns: S = solid background,
     * 1 = 1x1 dots, H = 2x1 (two pixels along buffer x), V = 1x2 (along buffer y), 2 = 2x2 dots,
     * K = solid black.
     */
    private fun blackShapes(): ByteArray {
        val c = Canvas()
        val tw = 64; val th = 57; val m = 16
        val k = Ink.BLACK.code
        val shapes: List<(Int, Int) -> Boolean> = listOf(
            { _, _ -> false },
            { x, y -> x % 2 == 0 && y % 2 == 0 },
            { x, y -> x % 4 < 2 && y % 2 == 0 },
            { x, y -> x % 2 == 0 && y % 4 < 2 },
            { x, y -> x % 4 < 2 && y % 4 < 2 },
            { _, _ -> true },
        )
        "S1HV2K".forEachIndexed { j, label -> c.text(m + j * tw + 29, 3, label.toString(), k) }
        INKS.filter { it != Ink.BLACK }.forEachIndexed { i, bg ->
            val y0 = m + i * th
            c.text(5, y0 + 23, bg.letter, k)
            shapes.forEachIndexed { j, black ->
                val x0 = m + j * tw
                c.fill(x0, y0, tw, th) { x, y -> if (black(x - x0, y - y0)) k else bg.code }
            }
        }
        return c.codes
    }

    /** 4x4 grid of tiles filled with raw codes 0..15, each outlined and labelled with its hex digit. */
    private fun allCodes(): ByteArray {
        val c = Canvas()
        val tw = 100; val th = 76
        for (code in 0 until 16) {
            val x = (code % 4) * tw; val y = (code / 4) * th
            c.outline(x, y, tw, th, Ink.BLACK.code)
            c.fill(x + 3, y + 3, tw - 6, th - 6, code)
            c.fill(x + 3, y + 3, 16, 16, Ink.WHITE.code)
            c.outline(x + 3, y + 3, 16, 16, Ink.BLACK.code)
            c.text(x + 8, y + 6, "%X".format(code), Ink.BLACK.code)
        }
        return c.codes
    }
}
