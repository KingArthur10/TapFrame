package dev.tapframe.ai

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ClipTokenizerTest {
    /** Optional: with TAPFRAME_TOKENIZER_DIR holding SD-Turbo's vocab.json and merges.txt. */
    @Test
    fun matchesKnownIds() {
        val dir = System.getenv("TAPFRAME_TOKENIZER_DIR") ?: return
        val t = ClipTokenizer(File(dir, "vocab.json").readText(), File(dir, "merges.txt").readText())
        val ids = t.encode("A photo of a cat")
        assertArrayEquals(longArrayOf(49406, 320, 1125, 539, 320, 2368, 49407, 0), ids.copyOf(8))
        val long = t.encode("comic book illustration, bold black ink outlines")
        println(long.take(12))
    }
}
