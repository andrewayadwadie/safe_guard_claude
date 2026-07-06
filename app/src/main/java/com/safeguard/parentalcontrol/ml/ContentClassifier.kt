package com.safeguard.parentalcontrol.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.safeguard.parentalcontrol.data.repository.CustomWordRepository
import com.safeguard.parentalcontrol.ml.download.ModelDownloader
import com.safeguard.parentalcontrol.ml.download.ModelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-stage content classifier for battery efficiency:
 *
 * Stage 1: Fast regex/keyword matching (microseconds)
 *   - Catches obvious inappropriate content
 *   - Very low battery impact
 *   - Supports parent-defined whitelist/blacklist
 *
 * Stage 2: TensorFlow Lite AI model (only if Stage 1 passes)
 *   - Catches subtle/contextual inappropriate content
 *   - Only runs when regex finds nothing
 *   - Uses quantized model for efficiency
 */
@Singleton
class ContentClassifier @Inject constructor(
    private val context: Context,
    private val customWordRepository: CustomWordRepository,
    private val modelDownloader: ModelDownloader
) {
    companion object {
        private const val MAX_IMAGE_DIMENSION = 224

        // Don't run the NSFW model on tiny/degenerate images: real screenshots and
        // photos are far larger, and the model returns meaningless probabilities on
        // out-of-distribution inputs (e.g. an 8x8 solid color scored "hentai" 0.45,
        // ISSUE-025). Anything smaller than this on either axis is treated as safe.
        private const val MIN_IMAGE_DIMENSION = 64

        private const val MIN_ANALYSIS_INTERVAL_MS = 5000L

        // Text length thresholds
        private const val MIN_TEXT_FOR_AI = 20  // Don't run AI on very short text
        private const val MAX_TEXT_FOR_AI = 1000 // Truncate very long text
    }

    // Coroutine scope for background operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Stage 1: Fast regex matcher
    private val regexMatcher = TextPatternMatcher()

    // Stage 2: TFLite AI model (lazy loaded to save memory). When a needed model isn't
    // downloaded yet, the classifier asks us to fetch it (EN on first English Stage-2, AR on
    // first Arabic) — language-conditional download, scope §5.6.
    private val textClassifierModel: TFLiteTextClassifier by lazy {
        TFLiteTextClassifier(context) { backend -> requestModelDownload(backend) }
    }

    private val imageClassifierModel: TFLiteImageClassifier by lazy {
        TFLiteImageClassifier(context)
    }

    private var lastImageAnalysisTime = 0L
    private val isProcessing = AtomicBoolean(false)
    private var isInitialized = false

    // MEMORY OPTIMIZATION: Defer initialization until first use
    // Don't start coroutines or database queries at app startup
    private var initAttempted = false

    /**
     * Initialize custom word lists from cache and set up observers.
     * Called lazily on first analysis to avoid startup memory pressure.
     */
    private fun initializeCustomWordListsIfNeeded() {
        if (initAttempted) return
        initAttempted = true

        scope.launch {
            try {
                // Load cached word lists
                loadCachedWordLists()

                // Sync if needed (but don't block)
                if (customWordRepository.shouldSync()) {
                    syncWordLists()
                }

                // Observe changes and update regex matcher
                observeWordListChanges()

                isInitialized = true
                Timber.d("ContentClassifier initialized with custom word lists")
            } catch (e: Exception) {
                Timber.e(e, "Error initializing custom word lists")
            }
        }
    }

    /**
     * Load cached word lists into the regex matcher.
     */
    private suspend fun loadCachedWordLists() {
        val whitelist = customWordRepository.getWhitelist()
        val blacklist = customWordRepository.getBlacklist()

        if (whitelist.isNotEmpty() || blacklist.isNotEmpty()) {
            regexMatcher.updateCustomWordLists(whitelist, blacklist)
            Timber.d("Loaded cached word lists: ${whitelist.size} whitelist, ${blacklist.size} blacklist")
        }
    }

    /**
     * Sync word lists from backend.
     */
    suspend fun syncWordLists(): Boolean {
        val success = customWordRepository.syncWordLists()
        if (success) {
            loadCachedWordLists()
        }
        return success
    }

    /**
     * Observe word list changes and update regex matcher.
     */
    private fun observeWordListChanges() {
        scope.launch {
            customWordRepository.observeWhitelist()
                .combine(customWordRepository.observeBlacklist()) { whitelist, blacklist ->
                    whitelist to blacklist
                }
                .collect { (whitelist, blacklist) ->
                    regexMatcher.updateCustomWordLists(whitelist, blacklist)
                    Timber.d("Word lists updated: ${whitelist.size} whitelist, ${blacklist.size} blacklist")
                }
        }
    }

    /**
     * Analyze text using two-stage approach:
     * 1. Fast regex check first (includes custom whitelist/blacklist)
     * 2. AI model only if regex finds nothing suspicious
     */
    suspend fun analyzeText(text: String, packageName: String = ""): TextAnalysisResult = withContext(Dispatchers.Default) {
        if (text.isBlank()) {
            return@withContext TextAnalysisResult.safe()
        }

        // MEMORY OPTIMIZATION: Lazy initialize on first use
        initializeCustomWordListsIfNeeded()

        try {
            // ===== STAGE 1: Fast Regex Check (includes custom word lists) =====
            val regexResult = regexMatcher.analyze(text)

            if (regexResult.isFlagged) {
                Timber.d("Text flagged by Stage 1 (regex): ${regexResult.categories}")
                return@withContext regexResult
            }

            // ===== STAGE 2: AI Model (only if regex passed) =====
            // Skip AI for very short text (not enough context)
            if (text.length < MIN_TEXT_FOR_AI) {
                return@withContext TextAnalysisResult.safe()
            }

            // Truncate very long text to save processing time
            val truncatedText = if (text.length > MAX_TEXT_FOR_AI) {
                text.take(MAX_TEXT_FOR_AI)
            } else {
                text
            }

            val aiResult = textClassifierModel.classify(truncatedText)

            // Stage 2 gating: per-category thresholds adjusted by which app the text came
            // from. Cuts benign gaming/casual false positives without relaxing the high-cost
            // child-safety categories. Stage 1 regex above is deterministic and NOT gated.
            val gate = FlagGating.decide(aiResult.categoryScores, packageName)
            if (!gate.flagged) {
                return@withContext TextAnalysisResult.safe()
            }

            Timber.d("Text flagged by Stage 2 (AI+gating): ${gate.category} [${gate.severity}] in $packageName")
            TextAnalysisResult(
                isFlagged = true,
                confidence = gate.confidence,
                categories = listOfNotNull(gate.category),
                reason = "AI detected ${gate.category} (${gate.severity}, ${(gate.confidence * 100).toInt()}%)",
                categoryScores = aiResult.categoryScores,
                severity = gate.severity
            )

        } catch (e: Exception) {
            Timber.e(e, "Text analysis failed")
            TextAnalysisResult.safe()
        }
    }

    /**
     * Analyze image using two-stage approach:
     * 1. Fast filename/metadata check
     * 2. TFLite NSFW model only if metadata check passes
     */
    suspend fun analyzeImage(imagePath: String): ImageAnalysisResult = withContext(Dispatchers.IO) {
        // MEMORY OPTIMIZATION: Lazy initialize on first use
        initializeCustomWordListsIfNeeded()

        // Throttle to prevent battery drain
        val now = System.currentTimeMillis()
        if (now - lastImageAnalysisTime < MIN_ANALYSIS_INTERVAL_MS) {
            return@withContext ImageAnalysisResult.pending()
        }

        if (!isProcessing.compareAndSet(false, true)) {
            return@withContext ImageAnalysisResult.pending()
        }

        try {
            lastImageAnalysisTime = now

            val file = File(imagePath)
            if (!file.exists()) {
                return@withContext ImageAnalysisResult.error("File not found")
            }

            // ===== STAGE 1: Fast Filename Check =====
            val filenameResult = checkFilename(file.name)
            if (filenameResult.isFlagged) {
                Timber.d("Image flagged by Stage 1 (filename)")
                return@withContext filenameResult
            }

            // ===== STAGE 2: TFLite NSFW Model =====
            val bitmap = loadScaledBitmap(imagePath)
                ?: return@withContext ImageAnalysisResult.error("Failed to load image")

            // Skip degenerate/tiny images (ISSUE-025): the model is unreliable on
            // out-of-distribution inputs and produces false positives. loadScaledBitmap
            // never upscales, so a small result means a small source.
            if (bitmap.width < MIN_IMAGE_DIMENSION || bitmap.height < MIN_IMAGE_DIMENSION) {
                Timber.d("Image too small for reliable analysis (${bitmap.width}x${bitmap.height}) - treating as safe")
                bitmap.recycle()
                return@withContext ImageAnalysisResult.safe()
            }

            try {
                val aiResult = imageClassifierModel.classify(bitmap)
                if (aiResult.isFlagged) {
                    Timber.d("Image flagged by Stage 2 (AI): ${aiResult.categories}")
                }
                aiResult
            } finally {
                bitmap.recycle()
            }

        } catch (e: Exception) {
            Timber.e(e, "Image analysis failed")
            ImageAnalysisResult.error(e.message ?: "Unknown error")
        } finally {
            isProcessing.set(false)
        }
    }

    private fun checkFilename(filename: String): ImageAnalysisResult {
        val lowerName = filename.lowercase()
        val suspiciousTerms = listOf("nsfw", "xxx", "porn", "nude", "naked", "adult", "sex")

        for (term in suspiciousTerms) {
            if (term in lowerName) {
                return ImageAnalysisResult(
                    isFlagged = true,
                    confidence = 0.95f,
                    categories = listOf("suspicious_filename"),
                    reason = "Suspicious filename: contains '$term'"
                )
            }
        }
        return ImageAnalysisResult.safe()
    }

    private fun loadScaledBitmap(path: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)

            val sampleSize = calculateSampleSize(
                options.outWidth, options.outHeight,
                MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION
            )

            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            BitmapFactory.decodeFile(path, loadOptions)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load bitmap")
            null
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / sampleSize) >= reqHeight && (halfWidth / sampleSize) >= reqWidth) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /**
     * Fire-and-forget request to download the model a routed text needs. Idempotent: the
     * downloader no-ops when already present and dedups in-flight requests, so calling this on
     * every miss (e.g. a burst of Arabic messages) is safe. Wi-Fi-gated; on a metered network it
     * defers and retries on a later call.
     */
    private fun requestModelDownload(backend: TFLiteTextClassifier.Backend) {
        val modelId = when (backend) {
            TFLiteTextClassifier.Backend.EN -> ModelId.EN
            TFLiteTextClassifier.Backend.AR -> ModelId.AR
        }
        if (modelDownloader.isReady(modelId)) return
        scope.launch {
            modelDownloader.ensure(modelId, requireUnmetered = true)
                .onSuccess { Timber.i("Model $modelId downloaded; Stage 2 will use it next time") }
                .onFailure { Timber.d("Model $modelId download deferred/failed: ${it.message}") }
        }
    }

    /**
     * Release resources when no longer needed
     */
    fun close() {
        try {
            textClassifierModel.close()
            imageClassifierModel.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing classifiers")
        }
    }
}
