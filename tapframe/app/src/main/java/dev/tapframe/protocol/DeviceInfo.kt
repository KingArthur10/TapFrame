package dev.tapframe.protocol

/** Parsed `00 D1` response: a flat list of [tag][len][value] entries. */
class DeviceInfo(val tags: Map<Int, ByteArray>) {

    /** Panel width from tag A0 (big-endian u16 at value offset 3). */
    val width: Int? get() = tags[0xA0]?.u16(3)

    /** Panel height from tag A0 (big-endian u16 at value offset 5). */
    val height: Int? get() = tags[0xA0]?.u16(5)

    /** Tag UID from tag C1. */
    val uid: String? get() = tags[0xC1]?.let(Apdu::toHex)

    private fun ByteArray.u16(at: Int): Int? =
        if (size >= at + 2) ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF) else null

    companion object {
        /** Parses the response, ignoring a trailing 90 00 status word. */
        fun parse(response: ByteArray): DeviceInfo {
            val body = if (Apdu.isOk(response)) response.copyOf(response.size - 2) else response
            val tags = linkedMapOf<Int, ByteArray>()
            var i = 0
            while (i < body.size) {
                require(i + 2 <= body.size) { "truncated TLV header at $i" }
                val tag = body[i].toInt() and 0xFF
                val len = body[i + 1].toInt() and 0xFF
                require(i + 2 + len <= body.size) { "TLV ${"%02X".format(tag)} runs past the end" }
                tags[tag] = body.copyOfRange(i + 2, i + 2 + len)
                i += 2 + len
            }
            return DeviceInfo(tags)
        }
    }
}
