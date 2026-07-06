package com.safeguard.parentalcontrol.ml

/**
 * Deterministic 3arabizi (Latin-script Arabic chat) → Arabic-script normalizer.
 *
 * Used as preprocessing on the Arabic path: 3arabizi is written in Latin letters + numerals
 * ("rou7 3aleek" = روح عليك), so it routes to the EN model by script and is invisible to
 * MARBERT. This transliterates it back to Arabic script first. Port of scratchpad/arabizi.py,
 * parity-locked by {@code ArabiziTest} (exact-string equality on captured ground truth).
 *
 * Lossy by nature (Latin→Arabic is many-to-one) — it recovers enough word *shape* for the model,
 * not perfect spelling. {@link #looksLikeArabizi} intentionally fires ONLY on the high-precision
 * signal (an arabizi numeral 2/3/5/6/7/8/9 adjacent to a Latin letter) so plain English is never
 * garbled; digit-less arabizi is not reliably separable from English without a lexicon and stays
 * on the EN path (a known, documented gap — see scope §5.4).
 */
object Arabizi {

    private val DIGRAPHS = mapOf(
        "sh" to "ش", "ch" to "تش", "kh" to "خ", "gh" to "غ", "th" to "ث",
        "dh" to "ذ", "ph" to "ف", "oo" to "و", "ee" to "ي", "aa" to "ا", "ou" to "و",
    )
    private val DIGITS = mapOf(
        '2' to "ء", '3' to "ع", '5' to "خ", '6' to "ط", '7' to "ح", '8' to "غ", '9' to "ص",
    )
    private val SINGLE = mapOf(
        'a' to "ا", 'b' to "ب", 'c' to "ك", 'd' to "د", 'e' to "", 'f' to "ف", 'g' to "ج",
        'h' to "ه", 'i' to "ي", 'j' to "ج", 'k' to "ك", 'l' to "ل", 'm' to "م", 'n' to "ن",
        'o' to "و", 'p' to "ب", 'q' to "ق", 'r' to "ر", 's' to "س", 't' to "ت", 'u' to "و",
        'v' to "ف", 'w' to "و", 'x' to "كس", 'y' to "ي", 'z' to "ز",
    )
    private val ARABIZI_DIGITS = setOf('2', '3', '5', '6', '7', '8', '9')

    /**
     * Transliterate only arabizi-looking tokens (have a Latin letter, no Arabic script);
     * whitespace and already-Arabic / numeric-only tokens pass through unchanged.
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i].isWhitespace()) {
                val start = i
                while (i < n && text[i].isWhitespace()) i++
                sb.append(text, start, i)            // keep whitespace run verbatim
            } else {
                val start = i
                while (i < n && !text[i].isWhitespace()) i++
                val tok = text.substring(start, i)
                sb.append(if (isArabiziToken(tok)) normalizeWord(tok) else tok)
            }
        }
        return sb.toString()
    }

    /** A token is transliterable if it has a Latin letter and no Arabic-script char. */
    private fun isArabiziToken(tok: String): Boolean {
        var hasLatin = false
        for (c in tok) {
            if (c in '؀'..'ۿ') return false   // Arabic block present → leave as-is
            if (c in 'a'..'z' || c in 'A'..'Z') hasLatin = true
        }
        return hasLatin
    }

    /** Greedy 2-char digraph, then digit, then single letter; unknown chars dropped. */
    private fun normalizeWord(word: String): String {
        val w = word.lowercase()
        val out = StringBuilder()
        var i = 0
        while (i < w.length) {
            if (i + 2 <= w.length) {
                val digraph = DIGRAPHS[w.substring(i, i + 2)]
                if (digraph != null) { out.append(digraph); i += 2; continue }
            }
            val c = w[i]
            val digit = DIGITS[c]
            if (digit != null) out.append(digit) else SINGLE[c]?.let { out.append(it) }
            i++
        }
        return out.toString()
    }

    /**
     * High-precision arabizi detector for routing: true iff an arabizi numeral appears adjacent
     * to a Latin letter (e.g. "rou7", "3aleek"). Plain English with standalone numbers ("meet at
     * 7") and pure-digit tokens ("911") do NOT trip it.
     */
    fun looksLikeArabizi(text: String): Boolean {
        for (i in text.indices) {
            if (text[i] in ARABIZI_DIGITS) {
                val prevLatin = i > 0 && isLatin(text[i - 1])
                val nextLatin = i < text.length - 1 && isLatin(text[i + 1])
                if (prevLatin || nextLatin) return true
            }
        }
        return false
    }

    private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
}
