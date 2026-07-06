package com.safeguard.parentalcontrol.ml

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

/**
 * Locks the Kotlin WordPiece tokenizer to byte-exact parity with the Python HuggingFace
 * tokenizer that the converted models were validated against (§5.1 / §5.2).
 *
 * Fixtures under `src/test/resources/wordpiece/` are generated from the SAME tokenizers as the
 * shipped INT8 models (scratchpad/convert_validate.py): `<lang>_vocab.txt`, `<lang>_specials.json`,
 * and `<lang>_reference.json` (31 real samples + 12 adversarial: punctuation, emoji, mixed
 * EN+AR, diacritized Arabic, tatweel, accents, >100-char word, tabs/newlines, empty).
 *
 * If this drifts, the model receives different token ids than it was trained/validated on and
 * silently emits garbage scores. This test is the gate that must pass before the tokenizer is
 * wired into TFLiteTextClassifier.
 */
class WordPieceTokenizerTest {

    private data class Specials(val cls: Int, val sep: Int, val pad: Int, val unk: Int, val max_len: Int)
    private data class Ref(val id: String, val text: String,
                           val input_ids: List<Int>, val attention_mask: List<Int>)

    private fun resource(path: String) =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { "missing fixture: $path" }

    private fun load(lang: String): Pair<WordPieceTokenizer, List<Ref>> {
        val vocabLines = resource("wordpiece/${lang}_vocab.txt").bufferedReader().readLines()
        val sp = Gson().fromJson(InputStreamReader(resource("wordpiece/${lang}_specials.json")), Specials::class.java)
        val refs: List<Ref> = Gson().fromJson(
            InputStreamReader(resource("wordpiece/${lang}_reference.json")),
            object : TypeToken<List<Ref>>() {}.type
        )
        val tok = WordPieceTokenizer.fromVocabLines(
            vocabLines, clsId = sp.cls, sepId = sp.sep, padId = sp.pad, unkId = sp.unk, maxLen = sp.max_len
        )
        return tok to refs
    }

    private fun assertParity(lang: String) {
        val (tok, refs) = load(lang)
        assertTrue("$lang: expected fixtures", refs.size >= 30)
        for (r in refs) {
            val enc = tok.encode(r.text)
            assertEquals(
                "[$lang ${r.id}] input_ids mismatch for: \"${r.text.take(50)}\"",
                r.input_ids, enc.inputIds.toList()
            )
            assertEquals(
                "[$lang ${r.id}] attention_mask mismatch for: \"${r.text.take(50)}\"",
                r.attention_mask, enc.attentionMask.toList()
            )
        }
    }

    @Test fun englishTokenizerMatchesHuggingFace() = assertParity("en")

    @Test fun arabicTokenizerMatchesHuggingFace() = assertParity("ar")
}
