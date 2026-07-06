package com.safeguard.parentalcontrol.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Two-backend, script-routed on-device toxicity classifier (Stage 2).
 *
 * Replaces the old single keyword model. Routes by script:
 *  - Latin / mixed  -> toxic-bert (EN), 6 sigmoid labels [toxic, severe_toxic, obscene,
 *                      threat, insult, identity_hate]
 *  - Arabic-script  -> MARBERTv2 (AR, Egyptian-locked), 2 softmax [Neutral, Hate]
 *
 * Both are INT8 TFLite with the SAME I/O shape: two int64 inputs (input_ids, attention_mask)
 * of [1,128] and a float32 [1,N] output of probabilities (sigmoid/softmax already applied in
 * the exported graph). Tokenization is real BERT WordPiece ({@link WordPieceTokenizer}),
 * parity-locked to HuggingFace.
 *
 * DELIVERY: the models are large (toxic-bert ~112 MB, MARBERT ~166 MB) and are NOT bundled in
 * the APK. They are loaded from {@code filesDir/models/} — the target of the on-demand
 * download (scope §5.6). Vocabs are small and ARE bundled in assets. For local testing before
 * the downloader exists, push the files manually:
 *   adb push toxicbert_en_int8.tflite /data/data/<pkg>/files/models/
 *   adb push marbert_ar_int8.tflite   /data/data/<pkg>/files/models/
 * If a model file is absent the backend stays unloaded and {@link #classify} returns safe()
 * (Stage-1 regex already ran upstream), so the app degrades to regex-only, never crashes.
 *
 * MEMORY: single-resident — only one model is held at a time; switching script evicts the
 * other. Emits the same {@code categoryScores} map the (unchanged) FlagGating layer consumes.
 */
class TFLiteTextClassifier(
    private val context: Context,
    // Invoked when a text needs a backend whose model file is not present yet, so the caller can
    // trigger the on-demand download (scope §5.6). Fires only on genuine absence, not on load/OOM
    // errors. No-op by default.
    private val onModelUnavailable: (Backend) -> Unit = {},
) : Closeable {

    enum class Backend { EN, AR }

    private data class ModelSpec(
        val backend: Backend,
        val modelFile: String,   // file name under filesDir/models/
        val vocabAsset: String,  // asset file name (bundled)
        val clsId: Int,
        val sepId: Int,
        val padId: Int,
        val unkId: Int,
        val numLabels: Int,
    )

    companion object {
        private const val TAG = "TFLiteTextClassifier"
        private const val MODELS_SUBDIR = "models"
        private const val MAX_SEQ_LEN = 128

        // Special-token ids are per-vocab (verified against each vocab.txt).
        private val EN_SPEC = ModelSpec(
            Backend.EN, "toxicbert_en_int8.tflite", "toxicbert_en_vocab.txt",
            clsId = 101, sepId = 102, padId = 0, unkId = 100, numLabels = 6,
        )
        private val AR_SPEC = ModelSpec(
            Backend.AR, "marbert_ar_int8.tflite", "marbert_ar_vocab.txt",
            clsId = 2, sepId = 3, padId = 0, unkId = 1, numLabels = 2,
        )

        // toxic-bert sigmoid label order -> app categories. NO sexual label exists in
        // toxic-bert (it recognizes sexual content only as generic toxic/obscene); the
        // `sexual` category is owned by Stage-1 regex (TextPatternMatcher), not this model.
        // identity_hate = targeted hate -> bullying. self_harm is regex-only (ML is blind to
        // its polite phrasing), so it is intentionally absent here.
        private const val L_TOXIC = 0
        private const val L_SEVERE = 1
        private const val L_OBSCENE = 2
        private const val L_THREAT = 3
        private const val L_INSULT = 4
        private const val L_IDENTITY_HATE = 5

        // MARBERT softmax label order.
        private const val AR_HATE = 1  // [Neutral=0, Hate=1]

        // Soft heap gate; the real protection is the try/catch around native load below.
        private const val MIN_AVAILABLE_HEAP_MB = 64L
    }

    private val modelsDir: File by lazy { File(context.filesDir, MODELS_SUBDIR) }

    // Single-resident state: at most one backend loaded at a time.
    private var loaded: Backend? = null
    private var interpreter: Interpreter? = null
    private var tokenizer: WordPieceTokenizer? = null

    private fun specFor(backend: Backend) = if (backend == Backend.EN) EN_SPEC else AR_SPEC

    /** Text to classify (possibly transliterated) + the backend to run it on. */
    private data class Route(val text: String, val backend: Backend)

    /**
     * Route by dominant script: Arabic-script-dominant -> AR as-is; Latin text that is detected
     * 3arabizi -> transliterate to Arabic script and run AR; all other Latin -> EN. Digit-less
     * arabizi is not separable from English and falls through to EN (scope §5.4 gap).
     */
    private fun route(text: String): Route {
        var arabic = 0
        var latin = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isArabicScript(cp) -> arabic++
                cp in 0x41..0x5A || cp in 0x61..0x7A -> latin++
            }
        }
        if (arabic > latin) return Route(text, Backend.AR)
        if (Arabizi.looksLikeArabizi(text)) return Route(Arabizi.normalize(text), Backend.AR)
        return Route(text, Backend.EN)
    }

    private fun isArabicScript(cp: Int): Boolean =
        cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0x08A0..0x08FF ||
            cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF

    /** Lazily (re)load the backend for [backend]; evict the other. Returns false (degrade to
     *  safe) if the model file is missing, memory is tight, or native load fails. */
    private fun ensureLoaded(backend: Backend): Boolean {
        if (loaded == backend && interpreter != null) return true

        // Evict the currently-resident model before loading the other.
        if (loaded != null && loaded != backend) {
            Timber.d("$TAG: evicting $loaded to load $backend")
            close()
        }

        val runtime = Runtime.getRuntime()
        val availableHeapMb = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())) / (1024 * 1024)
        if (availableHeapMb < MIN_AVAILABLE_HEAP_MB) {
            Timber.w("$TAG: low heap (${availableHeapMb}MB), skipping model load -> regex-only")
            return false
        }

        val spec = specFor(backend)
        val modelFile = File(modelsDir, spec.modelFile)
        if (!modelFile.exists()) {
            Timber.d("$TAG: model not present yet: ${modelFile.absolutePath} -> regex-only for $backend")
            return false
        }

        return try {
            val buffer = mapModelFile(modelFile)
            val interp = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
            val vocab = context.assets.open(spec.vocabAsset).bufferedReader(Charsets.UTF_8).useLines { it.toList() }
            val tok = WordPieceTokenizer.fromVocabLines(vocab, spec.clsId, spec.sepId, spec.padId, spec.unkId, MAX_SEQ_LEN)
            interpreter = interp
            tokenizer = tok
            loaded = backend
            Timber.d("$TAG: loaded $backend (${spec.modelFile}, vocab ${vocab.size})")
            true
        } catch (e: Exception) {
            Timber.w(e, "$TAG: failed to load $backend model -> regex-only")
            false
        } catch (e: OutOfMemoryError) {
            Timber.w("$TAG: OOM loading $backend model -> regex-only")
            false
        }
    }

    private fun mapModelFile(file: File): MappedByteBuffer =
        FileInputStream(file).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }

    /**
     * Classify [text] for inappropriate content. The returned [TextAnalysisResult.categoryScores]
     * is the authoritative output (FlagGating decides the flag); isFlagged/categories/confidence
     * are informational for logging.
     */
    fun classify(text: String): TextAnalysisResult {
        if (text.isBlank()) return TextAnalysisResult.safe()
        val (routedText, backend) = route(text)
        if (!ensureLoaded(backend)) {
            // If the model simply isn't downloaded yet, ask the caller to fetch it. Degrade to
            // safe() for now (Stage-1 regex already ran upstream).
            if (!File(modelsDir, specFor(backend).modelFile).exists()) onModelUnavailable(backend)
            return TextAnalysisResult.safe()
        }

        return try {
            val enc = tokenizer!!.encode(routedText)
            val ids = ByteBuffer.allocateDirect(MAX_SEQ_LEN * 8).order(ByteOrder.nativeOrder())
            val mask = ByteBuffer.allocateDirect(MAX_SEQ_LEN * 8).order(ByteOrder.nativeOrder())
            for (v in enc.inputIds) ids.putLong(v.toLong())
            for (v in enc.attentionMask) mask.putLong(v.toLong())
            ids.rewind(); mask.rewind()

            // Two int64 inputs in declared order: args_0=input_ids, args_1=attention_mask.
            val out = Array(1) { FloatArray(specFor(backend).numLabels) }
            interpreter!!.runForMultipleInputsOutputs(arrayOf<Any>(ids, mask), mapOf(0 to out))

            val scores = when (backend) {
                Backend.EN -> adaptEn(out[0])
                Backend.AR -> adaptAr(out[0])
            }
            buildResult(scores)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: classification failed ($backend)")
            TextAnalysisResult.safe()
        }
    }

    /** toxic-bert 6 sigmoids -> app categories (max-merge where several labels collapse). */
    private fun adaptEn(p: FloatArray): Map<String, Float> {
        val cs = HashMap<String, Float>(4)
        fun put(cat: String, v: Float) { if (v > (cs[cat] ?: 0f)) cs[cat] = v }
        put("profanity", maxOf(p[L_TOXIC], p[L_SEVERE], p[L_OBSCENE]))
        put("violence", p[L_THREAT])
        put("bullying", maxOf(p[L_INSULT], p[L_IDENTITY_HATE]))
        return cs
    }

    /** MARBERT is binary: the Hate prob maps to one coarse category (bullying, HIGH). Arabic
     *  category granularity comes from Stage-1 regex; see scope §4. */
    private fun adaptAr(p: FloatArray): Map<String, Float> = mapOf("bullying" to p[AR_HATE])

    private fun buildResult(scores: Map<String, Float>): TextAnalysisResult {
        val flagged = scores.filterValues { it >= 0.5f }
        val maxConfidence = scores.values.maxOrNull() ?: 0f
        return TextAnalysisResult(
            isFlagged = flagged.isNotEmpty(),
            confidence = if (flagged.isNotEmpty()) maxConfidence else 0f,
            categories = flagged.keys.toList(),
            reason = if (flagged.isNotEmpty()) "AI detected: ${flagged.keys.joinToString()}" else null,
            categoryScores = scores,
        )
    }

    fun isReady(): Boolean = interpreter != null

    override fun close() {
        interpreter?.close()
        interpreter = null
        tokenizer = null
        loaded = null
        Timber.d("$TAG: text classifier closed")
    }
}
