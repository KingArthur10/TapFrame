package dev.tapframe.render

import dev.tapframe.protocol.Ink

/** Renders pixel codes as the expected on-screen appearance. */
object Preview {
    /** Shown for codes the panel does not document (4, 7..15). */
    const val UNKNOWN_RGB = 0xFF00FF

    private val rgbByCode = IntArray(16) { code -> Ink.entries.firstOrNull { it.code == code }?.rgb ?: UNKNOWN_RGB }

    /** Expected 0xRRGGBB for a pixel code. */
    fun rgbOf(code: Int): Int = rgbByCode[code and 0xF]

    /** Opaque ARGB pixels, one per code, in buffer order (400 x 304). */
    fun argb(codes: ByteArray): IntArray = IntArray(codes.size) { 0xFF000000.toInt() or rgbByCode[codes[it].toInt() and 0xF] }

    /** As [argb] but as the card shows it when held upright: portrait, 304 x 400. */
    fun portraitArgb(codes: ByteArray): IntArray = PhotoPipeline.toPortrait(argb(codes))
}
