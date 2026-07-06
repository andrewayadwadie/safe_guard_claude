package com.safeguard.parentalcontrol.ml

import java.text.Normalizer

/**
 * BERT WordPiece tokenizer — a faithful port of HuggingFace `BertTokenizerFast`
 * (BertNormalizer → BertPreTokenizer → WordPiece) for the on-device transformer
 * classifiers (toxic-bert EN / MARBERTv2 AR).
 *
 * Both shipped models use the identical standard-BERT pipeline (clean_text,
 * handle_chinese_chars, strip_accents, lowercase — `strip_accents=null` resolves to
 * `true` because `lowercase=true`). They differ only in the vocab and the special-token
 * ids, so this one class is parameterized by both.
 *
 * Parity with the Python tokenizer is locked by `WordPieceTokenizerTest` (exact token-id
 * equality on a fixed corpus incl. adversarial cases). A drift here produces silent garbage
 * logits — no crash — so do NOT "tidy" the normalization without re-running that test.
 *
 * All scanning is by Unicode code point (not UTF-16 `char`) so surrogate-pair emoji and
 * combining marks are handled like the reference tokenizer.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val clsId: Int,
    private val sepId: Int,
    private val padId: Int,
    private val unkId: Int,
    private val maxLen: Int = 128,
    private val maxCharsPerWord: Int = 100,
) {
    /** Fixed-length `[maxLen]` input ids (CLS … SEP, pad-filled) + attention mask. */
    class Encoded(val inputIds: IntArray, val attentionMask: IntArray)

    fun encode(text: String): Encoded {
        val tokens = tokenize(text)
        val maxBody = maxLen - 2  // room for [CLS] and [SEP]
        val body = if (tokens.size > maxBody) tokens.subList(0, maxBody) else tokens

        val ids = IntArray(maxLen) { padId }
        val mask = IntArray(maxLen) { 0 }
        ids[0] = clsId; mask[0] = 1
        var pos = 1
        for (t in body) {
            ids[pos] = vocab[t] ?: unkId
            mask[pos] = 1
            pos++
        }
        ids[pos] = sepId; mask[pos] = 1
        return Encoded(ids, mask)
    }

    /** WordPiece subword tokens after normalization + pre-tokenization (no specials). */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        for (word in basicTokenize(normalize(text))) {
            wordpiece(word, out)
        }
        return out
    }

    // ---- BertNormalizer: clean_text → handle_chinese_chars → strip_accents → lowercase ----
    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0 || cp == 0xFFFD || isControl(cp) -> { /* drop */ }
                isWhitespace(cp) -> sb.append(' ')
                isChinese(cp) -> { sb.append(' '); sb.appendCodePoint(cp); sb.append(' ') }
                else -> sb.appendCodePoint(cp)
            }
        }
        // strip_accents: NFD then drop non-spacing marks (also strips Arabic harakat)
        val nfd = Normalizer.normalize(sb, Normalizer.Form.NFD)
        val stripped = StringBuilder(nfd.length)
        var j = 0
        while (j < nfd.length) {
            val cp = nfd.codePointAt(j)
            j += Character.charCount(cp)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
            stripped.appendCodePoint(cp)
        }
        // locale-independent lowercase (Kotlin's lowercase() uses Locale.ROOT semantics)
        return stripped.toString().lowercase()
    }

    // ---- BertPreTokenizer: split on whitespace, isolate each punctuation char ----
    private fun basicTokenize(text: String): List<String> {
        val tokens = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { tokens.add(cur.toString()); cur.setLength(0) } }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == ' '.code -> flush()
                isPunctuation(cp) -> { flush(); tokens.add(String(Character.toChars(cp))) }
                else -> cur.appendCodePoint(cp)
            }
        }
        flush()
        return tokens
    }

    // ---- WordPiece: greedy longest-match-first, "##" continuation prefix ----
    private fun wordpiece(word: String, out: MutableList<String>) {
        val cps = word.codePoints().toArray()
        if (cps.size > maxCharsPerWord) { out.add(UNK_TOKEN); return }

        val sub = ArrayList<String>()
        var start = 0
        while (start < cps.size) {
            var end = cps.size
            var match: String? = null
            while (start < end) {
                val piece = String(cps, start, end - start)
                val candidate = if (start > 0) "##$piece" else piece
                if (vocab.containsKey(candidate)) { match = candidate; break }
                end--
            }
            if (match == null) { out.add(UNK_TOKEN); return }  // whole word -> [UNK]
            sub.add(match)
            start = end
        }
        out.addAll(sub)
    }

    private fun isWhitespace(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
            Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(),
            Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(),
            Character.UNASSIGNED.toInt() -> true
            else -> false
        }
    }

    private fun isPunctuation(cp: Int): Boolean {
        // ASCII non-alphanumeric ranges are punctuation for BERT even when not category P*
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isChinese(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
            cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF ||
            cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    companion object {
        private const val UNK_TOKEN = "[UNK]"  // present in every BERT vocab -> maps to unkId

        /** Build from an index-ordered `vocab.txt` (one token per line). */
        fun fromVocabLines(
            lines: List<String>, clsId: Int, sepId: Int, padId: Int, unkId: Int,
            maxLen: Int = 128,
        ): WordPieceTokenizer {
            val vocab = HashMap<String, Int>(lines.size * 2)
            for ((i, line) in lines.withIndex()) vocab[line] = i
            return WordPieceTokenizer(vocab, clsId, sepId, padId, unkId, maxLen)
        }
    }
}
