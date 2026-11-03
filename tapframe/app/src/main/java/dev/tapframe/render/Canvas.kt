package dev.tapframe.render

import dev.tapframe.protocol.Frame
import dev.tapframe.protocol.Ink

/** A WIDTH x HEIGHT buffer of raw pixel codes (0..15) with simple drawing helpers. */
class Canvas(val width: Int = Frame.WIDTH, val height: Int = Frame.HEIGHT, background: Int = Ink.WHITE.code) {
    val codes = ByteArray(width * height) { background.toByte() }

    operator fun set(x: Int, y: Int, code: Int) {
        if (x in 0 until width && y in 0 until height) codes[y * width + x] = code.toByte()
    }

    operator fun get(x: Int, y: Int): Int = codes[y * width + x].toInt()

    fun fill(x: Int, y: Int, w: Int, h: Int, code: Int) {
        for (yy in y until y + h) for (xx in x until x + w) this[xx, yy] = code
    }

    /** Fills a rectangle with code(x, y) evaluated at absolute coordinates. */
    fun fill(x: Int, y: Int, w: Int, h: Int, code: (Int, Int) -> Int) {
        for (yy in y until y + h) for (xx in x until x + w) this[xx, yy] = code(xx, yy)
    }

    fun outline(x: Int, y: Int, w: Int, h: Int, code: Int) {
        fill(x, y, w, 1, code); fill(x, y + h - 1, w, 1, code)
        fill(x, y, 1, h, code); fill(x + w - 1, y, 1, h, code)
    }

    /** Bresenham line. */
    fun line(x0: Int, y0: Int, x1: Int, y1: Int, code: Int) {
        var x = x0; var y = y0
        val dx = kotlin.math.abs(x1 - x0); val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            this[x, y] = code
            if (x == x1 && y == y1) return
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** Midpoint circle outline. */
    fun circle(cx: Int, cy: Int, r: Int, code: Int) {
        var x = r; var y = 0; var err = 1 - r
        while (x >= y) {
            for ((px, py) in listOf(x to y, y to x, -y to x, -x to y, -x to -y, -y to -x, y to -x, x to -y)) {
                this[cx + px, cy + py] = code
            }
            y++
            if (err < 0) err += 2 * y + 1 else { x--; err += 2 * (y - x) + 1 }
        }
    }

    /** Draws [text] in the 3x5 font; each glyph cell is 4*scale wide. Returns the drawn width. */
    fun text(x: Int, y: Int, text: String, code: Int, scale: Int = 2): Int {
        text.uppercase().forEachIndexed { i, ch ->
            val glyph = Font.glyph(ch)
            for (row in 0 until 5) for (col in 0 until 3) {
                if (glyph[row][col] == '1') fill(x + (i * 4 + col) * scale, y + row * scale, scale, scale, code)
            }
        }
        return Font.width(text, scale)
    }

    /** Copies [src] codes into this canvas at (x, y). */
    fun blit(x: Int, y: Int, src: Canvas) {
        for (yy in 0 until src.height) for (xx in 0 until src.width) this[x + xx, y + yy] = src[xx, yy]
    }
}

/** Minimal 3x5 bitmap font: digits, the letters used in pattern labels, space, '-' and 'X'. */
object Font {
    private val glyphs = mapOf(
        '0' to "111 101 101 101 111", '1' to "010 110 010 010 111", '2' to "111 001 111 100 111",
        '3' to "111 001 111 001 111", '4' to "101 101 111 001 001", '5' to "111 100 111 001 111",
        '6' to "111 100 111 101 111", '7' to "111 001 001 001 001", '8' to "111 101 111 101 111",
        '9' to "111 101 111 001 111", 'A' to "010 101 111 101 101", 'B' to "110 101 110 101 110",
        'C' to "011 100 100 100 011", 'D' to "110 101 101 101 110", 'E' to "111 100 110 100 111",
        'F' to "111 100 110 100 100", 'G' to "011 100 101 101 011", 'H' to "101 101 111 101 101",
        'I' to "111 010 010 010 111", 'K' to "101 101 110 101 101", 'L' to "100 100 100 100 111",
        'M' to "101 111 111 101 101", 'N' to "110 101 101 101 101", 'O' to "010 101 101 101 010",
        'P' to "110 101 110 100 100", 'R' to "110 101 110 101 101", 'S' to "011 100 010 001 110",
        'T' to "111 010 010 010 010", 'U' to "101 101 101 101 111", 'V' to "101 101 101 101 010",
        'W' to "101 101 111 111 101", 'X' to "101 101 010 101 101", 'Y' to "101 101 010 010 010",
        ' ' to "000 000 000 000 000", '-' to "000 000 111 000 000",
    ).mapValues { (_, v) -> v.split(' ') }

    fun glyph(ch: Char): List<String> = glyphs[ch] ?: error("no glyph for '$ch'")

    fun width(text: String, scale: Int = 2): Int = if (text.isEmpty()) 0 else (text.length * 4 - 1) * scale
}
