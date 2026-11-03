package dev.tapframe.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DctNetConverterTest {
    @Test
    fun protoRoundTrip() {
        val bytes = ProtoWriter().int(1, 300).string(2, "hi").float(3, 1.5f).message(4) { int(1, 7) }.toByteArray()
        val f = ProtoReader(bytes).fields().toList()
        assertEquals(300L, f[0].varint)
        assertEquals("hi", f[1].string())
        assertEquals(1.5f, f[2].float())
        assertEquals(7L, f[3].reader().next().varint)
    }

    /**
     * Optional: with TAPFRAME_DCT_DIR pointing at ModelScope .pb files, converts each to
     * build/dct/<name>.onnx (compare with scripts/dct_to_onnx.py output in ONNX Runtime).
     */
    @Test
    fun convertsPublishedModels() {
        val dir = System.getenv("TAPFRAME_DCT_DIR") ?: return
        val out = File("build/dct").apply { mkdirs() }
        val pbs = File(dir).listFiles { f -> f.name.endsWith(".pb") }.orEmpty()
        assertTrue(pbs.isNotEmpty())
        for (pb in pbs) {
            val onnx = DctNetConverter.convert(pb.readBytes())
            File(out, pb.nameWithoutExtension + ".onnx").writeBytes(onnx)
        }
    }
}
