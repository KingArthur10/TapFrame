package dev.tapframe.protocol

import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    /** The 187 F0D3 APDUs Einky sent in capture 1 (working directory is the app module). */
    private val captured: List<String> =
        File("src/main/assets/replay_capture1.txt").readLines().map { it.trim() }.filter { it.startsWith("F0D3") }

    /** Compressed payload per (section, chunk), in upload order. */
    private fun capturedChunks(): List<ByteArray> {
        val chunks = linkedMapOf<Pair<Int, Int>, ByteArray>()
        for (hex in captured) {
            val a = Apdu.fromHex(hex)
            val key = a[2].toInt() to a[5].toInt()
            chunks[key] = (chunks[key] ?: ByteArray(0)) + a.copyOfRange(7, a.size)
        }
        return chunks.values.toList()
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun decompressesCapturedImage() {
        val parts = capturedChunks().map(Lzo::decompress)
        assertEquals(List(4) { List(7) { 2000 } + 1200 }.flatten(), parts.map { it.size })
        val image = parts.reduce(ByteArray::plus)
        assertEquals("e360a63206055648b666952b74761a0f33cb243ba838f50376abc1d327f65bbb", sha256(image))
    }

    @Test
    fun reproducesCapturedUploadExactly() {
        val compressed = capturedChunks()
        val image = compressed.map(Lzo::decompress).reduce(ByteArray::plus)
        val it = compressed.iterator()
        val apdus = Apdu.buildUpload(image) { chunk ->
            it.next().also { payload -> assertArrayEquals(chunk, Lzo.decompress(payload)) }
        }
        assertEquals(captured, apdus.map(Apdu::toHex))
    }

    @Test
    fun literalOnlyRoundTrips() {
        val rnd = Random(42)
        for (n in listOf(1, 2, 3, 4, 17, 18, 19, 238, 239, 240, 255, 273, 274, 275, 528, 529, 1200, 2000)) {
            val data = rnd.nextBytes(n)
            assertArrayEquals("n=$n", data, Lzo.decompress(Lzo.encodeLiteralOnly(data)))
        }
        val big = Lzo.encodeLiteralOnly(ByteArray(2000) { 1 })
        assertEquals(2012, big.size)
        assertTrue(big.copyOfRange(0, 8).all { it == 0.toByte() })
        assertEquals(0xC5.toByte(), big[8])
    }

    @Test
    fun testPatternUploadIsWellFormed() {
        val image = Frame.packPixels(Frame.testPattern())
        val apdus = Apdu.buildUpload(image)
        val groups = linkedMapOf<Pair<Int, Int>, MutableList<ByteArray>>()
        for (a in apdus) {
            assertEquals(0xF0.toByte(), a[0]); assertEquals(0xD3.toByte(), a[1])
            assertEquals(a.size - 5, a[4].toInt() and 0xFF)
            assertTrue(a.size - 7 <= 250)
            groups.getOrPut(a[2].toInt() to a[5].toInt()) { mutableListOf() } += a
        }
        assertEquals(32, groups.size)
        val rebuilt = groups.values.map { blocks ->
            assertEquals(List(blocks.size) { if (it == blocks.lastIndex) 1 else 0 }, blocks.map { it[3].toInt() })
            assertEquals(blocks.indices.toList(), blocks.map { it[6].toInt() })
            Lzo.decompress(blocks.map { it.copyOfRange(7, it.size) }.reduce(ByteArray::plus))
        }.reduce(ByteArray::plus)
        assertArrayEquals(image, rebuilt)
    }

    @Test
    fun framePixels() {
        val codes = ByteArray(Frame.WIDTH * Frame.HEIGHT) { 1 }
        codes[0] = 1; codes[1] = 0; codes[2] = 5; codes[3] = 6
        val packed = Frame.packPixels(codes)
        assertEquals(0x10.toByte(), packed[0]); assertEquals(0x56.toByte(), packed[1])

        val p = Frame.testPattern()
        fun at(x: Int, y: Int) = p[y * Frame.WIDTH + x].toInt()
        assertEquals(Ink.BLACK.code, at(0, 0))
        assertEquals(Ink.RED.code, at(399, 0))
        assertEquals(Ink.WHITE.code, at(200, 10))
        assertEquals(Ink.BLACK.code, at(0, 303))
        assertEquals(Ink.WHITE.code, at(100, 100))
        assertEquals(Ink.GREEN.code, at(399, 303))
        assertEquals(setOf(0, 1, 2, 3, 5, 6), p.map { it.toInt() }.toSet())
    }

    @Test
    fun commandsAndHex() {
        assertEquals("F0D4858000", Apdu.toHex(Apdu.refresh(0)))
        assertEquals("F0D8000005000000000E", Apdu.toHex(Apdu.READ_D8))
        assertEquals("00D1000000", Apdu.toHex(Apdu.READ_INFO))
        assertTrue(Apdu.isOk(Apdu.fromHex("9000")))
        assertTrue(Apdu.isOk(Apdu.fromHex("019000")))
        assertFalse(Apdu.isOk(Apdu.fromHex("698A")))
        assertFalse(Apdu.isOk(ByteArray(0)))
        assertEquals("00FF7F80", Apdu.toHex(Apdu.fromHex("00 ff 7f 80")))
        assertThrows { Apdu.fromHex("ABC") }
        assertThrows { Apdu.fromHex("ZZ") }
    }

    @Test
    fun parsesDeviceInfo() {
        val hex = "A007F0076001900130A10700FFFFFFFFFFFFB10110B20114B30100C004201EA001C104DE7C101E" +
            "D10701200000000000E00E46E00080018102830382058607859000"
        val info = DeviceInfo.parse(Apdu.fromHex(hex))
        assertEquals(400, info.width)
        assertEquals(304, info.height)
        assertEquals("DE7C101E", info.uid)
        assertEquals(14, info.tags[0xE0]?.size)
        assertThrows { DeviceInfo.parse(Apdu.fromHex("A00701")) }
    }

    private fun assertThrows(block: () -> Unit) {
        val threw = try { block(); false } catch (_: IllegalArgumentException) { true }
        assertTrue("expected IllegalArgumentException", threw)
    }
}
