package dev.tapframe.ai

/**
 * CLIP's byte-level BPE tokenizer (as used by Stable Diffusion 2 / SD-Turbo), from the model's own
 * vocab.json and merges.txt. Pads with token 0 ("!"), as SD-Turbo's tokenizer does.
 */
class ClipTokenizer(vocabJson: String, merges: String) {
    private val vocab: Map<String, Int> = parseFlatJson(vocabJson)
    private val ranks: Map<Pair<String, String>, Int> = merges.lineSequence()
        .drop(1) // "#version" header
        .filter { it.isNotBlank() }
        .mapIndexedNotNull { i, line -> line.split(' ').takeIf { it.size == 2 }?.let { (it[0] to it[1]) to i } }
        .toMap()
    private val byteToChar: CharArray = bytesToUnicode()
    private val cache = HashMap<String, List<String>>()

    /** [text] as [LENGTH] token ids: start, tokens (truncated), end, then padding. */
    fun encode(text: String): LongArray {
        val ids = mutableListOf(START)
        val clean = text.lowercase().replace(Regex("\\s+"), " ").trim()
        for (m in PATTERN.findAll(clean)) {
            val word = m.value.toByteArray(Charsets.UTF_8).map { byteToChar[it.toInt() and 0xFF] }.joinToString("")
            for (piece in bpe(word)) ids += vocab[piece] ?: continue
        }
        val body = ids.take(LENGTH - 1) + END
        return LongArray(LENGTH) { body.getOrNull(it)?.toLong() ?: PAD.toLong() }
    }

    private fun bpe(token: String): List<String> = cache.getOrPut(token) {
        var word = token.dropLast(1).map { it.toString() } + (token.last() + "</w>")
        while (word.size > 1) {
            val best = word.zipWithNext().minByOrNull { ranks[it] ?: Int.MAX_VALUE } ?: break
            if (best !in ranks) break
            val merged = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == best.first && word[i + 1] == best.second) { merged += best.first + best.second; i += 2 }
                else { merged += word[i]; i++ }
            }
            word = merged
        }
        word
    }

    companion object {
        const val LENGTH = 77
        const val START = 49406
        const val END = 49407
        const val PAD = 0

        private val PATTERN = Regex("""<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""")

        /** A flat JSON object of string keys and integer values (vocab.json). */
        internal fun parseFlatJson(json: String): Map<String, Int> {
            val out = HashMap<String, Int>(65536)
            var i = json.indexOf('{') + 1
            val key = StringBuilder()
            while (true) {
                i = json.indexOf('"', i)
                if (i < 0) break
                key.setLength(0); i++
                while (json[i] != '"') {
                    if (json[i] == '\\') {
                        i++
                        when (val e = json[i]) {
                            'n' -> key.append('\n'); 't' -> key.append('\t'); 'r' -> key.append('\r')
                            'b' -> key.append('\b'); 'f' -> key.append('\u000C')
                            'u' -> { key.append(json.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                            else -> key.append(e)
                        }
                    } else key.append(json[i])
                    i++
                }
                i = json.indexOf(':', i) + 1
                while (json[i].isWhitespace()) i++
                val start = i
                while (i < json.length && (json[i].isDigit() || json[i] == '-')) i++
                out[key.toString()] = json.substring(start, i).toInt()
            }
            return out
        }

        /** GPT-2's reversible byte -> printable character map. */
        private fun bytesToUnicode(): CharArray {
            val printable = (33..126) + (161..172) + (174..255)
            val out = CharArray(256)
            var extra = 0
            for (b in 0 until 256) out[b] = if (b in printable) b.toChar() else (256 + extra++).toChar()
            return out
        }
    }
}
