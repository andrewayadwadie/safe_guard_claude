package com.safeguard.parentalcontrol.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import timber.log.Timber
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * TensorFlow Lite text classifier for detecting inappropriate content.
 *
 * Uses a pre-trained toxicity model that detects:
 * - General toxicity
 * - Severe toxicity (threats, violence)
 * - Obscene language
 * - Threats
 * - Insults
 * - Sexual explicit content
 *
 * The model uses word embeddings with pre-trained weights for toxic patterns.
 */
class TFLiteTextClassifier(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "TFLiteTextClassifier"
        private const val MODEL_FILE = "text_classifier.tflite"
        private const val VOCAB_FILE = "vocab.txt"
        private const val LABELS_FILE = "labels.txt"

        // Model input/output configuration
        private const val MAX_SEQUENCE_LENGTH = 128
        private const val NUM_CLASSES = 7

        // Classification thresholds
        private const val TOXICITY_THRESHOLD = 0.5f
        private const val SEVERE_THRESHOLD = 0.4f  // Lower threshold for severe content

        // Default category labels (used if labels.txt not found)
        private val DEFAULT_CATEGORIES = listOf(
            "safe",
            "toxicity",
            "severe_toxicity",
            "obscene",
            "threat",
            "insult",
            "sexual_explicit"
        )

        // Map model categories to app categories
        private val CATEGORY_MAPPING = mapOf(
            "toxicity" to "profanity",
            "severe_toxicity" to "self_harm",
            "obscene" to "profanity",
            "threat" to "violence",
            "insult" to "bullying",
            "sexual_explicit" to "sexual"
        )
    }

    private var interpreter: Interpreter? = null
    private var vocabulary: Map<String, Int> = emptyMap()
    private var categories: List<String> = DEFAULT_CATEGORIES
    private var isInitialized = false
    private var initAttempted = false

    // MEMORY OPTIMIZATION: Don't load model in init{} - defer until first use
    // This prevents loading ~10-20MB model into memory at app startup

    private fun initializeModel() {
        try {
            // Load model
            val modelBuffer = loadModelFile()
            if (modelBuffer != null) {
                val options = Interpreter.Options().apply {
                    setNumThreads(2)  // Limit threads for battery efficiency
                }
                interpreter = Interpreter(modelBuffer, options)

                // Load vocabulary
                vocabulary = loadVocabulary()

                // Load labels
                categories = loadLabels()

                isInitialized = true
                Timber.d("$TAG: TFLite text classifier initialized successfully")
                Timber.d("$TAG: Vocabulary size: ${vocabulary.size}, Categories: $categories")
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to initialize TFLite text classifier")
            isInitialized = false
        }
    }

    private fun loadModelFile(): MappedByteBuffer? {
        return try {
            FileUtil.loadMappedFile(context, MODEL_FILE)
        } catch (e: Exception) {
            Timber.d("$TAG: Text classifier model not found: $MODEL_FILE")
            null
        }
    }

    private fun loadVocabulary(): Map<String, Int> {
        return try {
            val vocabList = FileUtil.loadLabels(context, VOCAB_FILE)
            vocabList.mapIndexed { index, word -> word.lowercase() to index }.toMap()
        } catch (e: Exception) {
            Timber.d("$TAG: Vocabulary file not found, using built-in vocabulary")
            createBuiltInVocabulary()
        }
    }

    private fun loadLabels(): List<String> {
        return try {
            FileUtil.loadLabels(context, LABELS_FILE)
        } catch (e: Exception) {
            Timber.d("$TAG: Labels file not found, using defaults")
            DEFAULT_CATEGORIES
        }
    }

    /**
     * Built-in vocabulary for when vocab.txt is not available.
     * Contains common toxic words with their token IDs.
     */
    private fun createBuiltInVocabulary(): Map<String, Int> {
        return mapOf(
            "[pad]" to 0,
            "[unk]" to 1,
            // Threats/violence
            "kill" to 100, "murder" to 101, "die" to 102, "dead" to 103,
            "shoot" to 105, "stab" to 106, "attack" to 108, "suicide" to 111,
            // Profanity
            "fuck" to 200, "fucking" to 201, "shit" to 203, "bitch" to 204,
            "bastard" to 205, "ass" to 206, "asshole" to 207, "cunt" to 214,
            // Insults
            "stupid" to 300, "idiot" to 301, "dumb" to 302, "moron" to 303,
            "retard" to 304, "loser" to 306, "pathetic" to 307, "worthless" to 308,
            "ugly" to 309, "fat" to 310, "disgusting" to 311, "hate" to 317,
            // Sexual
            "porn" to 400, "xxx" to 402, "nsfw" to 403, "nude" to 404,
            "nudes" to 405, "naked" to 406, "sex" to 407, "horny" to 410,
            "hentai" to 414,
            // Slurs
            "nigger" to 500, "faggot" to 502, "fag" to 503,
            // Bullying
            "kys" to 801, "yourself" to 800,
            // Common words
            "you" to 900, "your" to 901, "i" to 902, "should" to 943,
            "don't" to 946, "dont" to 945, "tell" to 947,
            "parent" to 948, "parents" to 949
        )
    }

    /**
     * Classify text for inappropriate content.
     *
     * @param text Text to classify
     * @return TextAnalysisResult with classification details
     */
    fun classify(text: String): TextAnalysisResult {
        // MEMORY OPTIMIZATION: Lazy initialize model on first use
        if (!initAttempted) {
            initAttempted = true
            try {
                // Check available memory before loading model
                val runtime = Runtime.getRuntime()
                val freeMemory = runtime.freeMemory()
                val maxMemory = runtime.maxMemory()
                val usedMemory = runtime.totalMemory() - freeMemory
                val availableMemory = maxMemory - usedMemory

                // Only load if we have at least 50MB available
                if (availableMemory > 50 * 1024 * 1024) {
                    initializeModel()
                } else {
                    Timber.w("$TAG: Low memory (${availableMemory / 1024 / 1024}MB available), skipping TFLite model")
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: TFLite text model not available, using fallback")
            } catch (e: Error) {
                Timber.w("$TAG: TFLite text model error, using fallback: ${e.message}")
            }
        }

        if (!isInitialized || interpreter == null) {
            Timber.d("$TAG: Model not initialized, returning safe")
            return TextAnalysisResult.safe()
        }

        return try {
            // Tokenize and pad input
            val inputBuffer = tokenizeText(text)

            // Prepare output buffer - model outputs [1, NUM_CLASSES]
            val outputBuffer = Array(1) { FloatArray(NUM_CLASSES) }

            // Run inference
            interpreter?.run(inputBuffer, outputBuffer)

            // Process results
            val result = processOutput(outputBuffer[0], text)

            Timber.d("$TAG: Classification result for '${text.take(30)}...': " +
                    "flagged=${result.isFlagged}, categories=${result.categories}, " +
                    "confidence=${result.confidence}")

            result

        } catch (e: Exception) {
            Timber.e(e, "$TAG: TFLite text classification failed")
            TextAnalysisResult.safe()
        }
    }

    private fun tokenizeText(text: String): ByteBuffer {
        // Allocate buffer for int32 tokens
        val buffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * 4)
        buffer.order(ByteOrder.nativeOrder())

        // Tokenize text
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9'\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        var position = 0
        for (word in words) {
            if (position >= MAX_SEQUENCE_LENGTH) break

            // Look up word in vocabulary, use UNK (1) for unknown words
            val tokenId = vocabulary[word] ?: vocabulary["[unk]"] ?: 1
            buffer.putInt(tokenId)
            position++
        }

        // Pad remaining positions with zeros (PAD token)
        while (position < MAX_SEQUENCE_LENGTH) {
            buffer.putInt(0)
            position++
        }

        buffer.rewind()
        return buffer
    }

    private fun processOutput(probabilities: FloatArray, originalText: String): TextAnalysisResult {
        val detectedCategories = mutableListOf<String>()
        var maxConfidence = 0f
        var reason: String? = null

        // Log all probabilities for debugging
        Timber.v("$TAG: Raw probabilities: ${probabilities.mapIndexed { i, p ->
            "${categories.getOrElse(i) { "cat$i" }}=${"%.3f".format(p)}"
        }.joinToString(", ")}")

        // Check each category (skip index 0 which is "safe")
        for (i in 1 until probabilities.size) {
            val probability = probabilities[i]
            val categoryName = categories.getOrElse(i) { "unknown" }

            // Use lower threshold for severe categories
            val threshold = when (categoryName) {
                "severe_toxicity", "threat", "sexual_explicit" -> SEVERE_THRESHOLD
                else -> TOXICITY_THRESHOLD
            }

            if (probability >= threshold) {
                // Map to app's category names
                val mappedCategory = CATEGORY_MAPPING[categoryName] ?: categoryName
                if (mappedCategory !in detectedCategories) {
                    detectedCategories.add(mappedCategory)
                }

                if (probability > maxConfidence) {
                    maxConfidence = probability
                    reason = "AI detected: $categoryName (${(probability * 100).toInt()}% confidence)"
                }
            }
        }

        return if (detectedCategories.isNotEmpty()) {
            TextAnalysisResult(
                isFlagged = true,
                confidence = maxConfidence,
                categories = detectedCategories,
                reason = reason
            )
        } else {
            TextAnalysisResult.safe()
        }
    }

    /**
     * Check if the model is ready for inference.
     */
    fun isReady(): Boolean = isInitialized

    override fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
        Timber.d("$TAG: TFLite text classifier closed")
    }
}
