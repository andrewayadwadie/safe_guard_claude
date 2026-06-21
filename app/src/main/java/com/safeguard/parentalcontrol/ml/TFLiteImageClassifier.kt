package com.safeguard.parentalcontrol.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import timber.log.Timber
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * TensorFlow Lite image classifier for NSFW/inappropriate content detection.
 *
 * Uses MobileNetV2-based model optimized for mobile:
 * - Quantized INT8 for smaller size and faster inference
 * - GPU delegate when available for better performance
 * - Input size: 224x224 RGB
 *
 * Battery optimizations:
 * - GPU acceleration reduces CPU load
 * - Quantized model uses less memory bandwidth
 * - Limited thread count
 */
class TFLiteImageClassifier(private val context: Context) : Closeable {

    companion object {
        private const val MODEL_FILE = "nsfw_classifier.tflite"

        // Model input configuration
        private const val INPUT_SIZE = 224
        private const val PIXEL_SIZE = 3  // RGB
        private const val NUM_CLASSES = 5  // GantMan's model: drawings, hentai, neutral, porn, sexy

        // Classification threshold - lowered because NSFW probability is often distributed
        // across multiple categories (porn + sexy + hentai)
        private const val NSFW_THRESHOLD = 0.4f

        // Category labels (GantMan's MobileNet V2 NSFW model)
        // Index 0: drawings (safe - cartoons/drawings)
        // Index 1: hentai (inappropriate - animated adult content)
        // Index 2: neutral (safe - normal images)
        // Index 3: porn (inappropriate - explicit content)
        // Index 4: sexy (inappropriate - suggestive content)
        private val CATEGORIES = listOf("drawings", "hentai", "neutral", "porn", "sexy")

        // Safe category indices (not flagged)
        private val SAFE_INDICES = setOf(0, 2)  // drawings, neutral

        // Normalization: This model expects [0, 1] range, NOT [-1, 1]
        // Simply divide by 255 to normalize
        private const val IMAGE_SCALE = 255.0f
    }

    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private var initAttempted = false

    // MEMORY OPTIMIZATION: Removed GPU delegate - saves memory and avoids GPU resource errors
    // MEMORY OPTIMIZATION: Don't load model in init{} - defer until first use
    // This prevents loading ~15-25MB model into memory at app startup

    private fun initializeModel() {
        try {
            val modelBuffer = loadModelFile() ?: return

            // MEMORY OPTIMIZATION: Use CPU-only inference
            // GPU delegate removed to avoid memory issues and resource errors
            val options = Interpreter.Options().apply {
                setNumThreads(2)  // Limit threads for battery
            }

            interpreter = Interpreter(modelBuffer, options)
            isInitialized = true
            Timber.d("TFLite image classifier initialized (CPU-only)")

        } catch (e: Exception) {
            Timber.w(e, "Failed to initialize TFLite image classifier")
            isInitialized = false
        } catch (e: Error) {
            Timber.w("TFLite image classifier error: ${e.message}")
            isInitialized = false
        }
    }

    private fun loadModelFile(): MappedByteBuffer? {
        return try {
            FileUtil.loadMappedFile(context, MODEL_FILE)
        } catch (e: Exception) {
            Timber.d("Image classifier model not found: $MODEL_FILE")
            null
        }
    }

    /**
     * Classify image for NSFW/inappropriate content.
     *
     * @param bitmap Image to classify (will be resized to 224x224)
     * @return ImageAnalysisResult with classification details
     */
    fun classify(bitmap: Bitmap): ImageAnalysisResult {
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

                // Only load if we have at least 75MB available (image model is larger)
                if (availableMemory > 75 * 1024 * 1024) {
                    initializeModel()
                } else {
                    Timber.w("TFLite: Low memory (${availableMemory / 1024 / 1024}MB available), using heuristic fallback")
                }
            } catch (e: Exception) {
                Timber.w(e, "TFLite image model not available, using fallback")
            } catch (e: Error) {
                Timber.w("TFLite image model error, using fallback: ${e.message}")
            }
        }

        if (!isInitialized || interpreter == null) {
            // Model not available - use fallback heuristic
            return classifyWithHeuristic(bitmap)
        }

        return try {
            // Resize bitmap to model input size
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

            try {
                // Convert to ByteBuffer
                val inputBuffer = bitmapToByteBuffer(resizedBitmap)

                // Prepare output buffer
                val outputBuffer = Array(1) { FloatArray(NUM_CLASSES) }

                // Run inference
                interpreter?.run(inputBuffer, outputBuffer)

                // Process results
                processOutput(outputBuffer[0])

            } finally {
                if (resizedBitmap != bitmap) {
                    resizedBitmap.recycle()
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "TFLite image classification failed")
            classifyWithHeuristic(bitmap)
        }
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE)
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            // Extract RGB and normalize to [0, 1] range
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)

            byteBuffer.putFloat(r / IMAGE_SCALE)
            byteBuffer.putFloat(g / IMAGE_SCALE)
            byteBuffer.putFloat(b / IMAGE_SCALE)
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    private fun processOutput(probabilities: FloatArray): ImageAnalysisResult {
        // Log all probabilities for debugging
        val probLog = CATEGORIES.mapIndexed { i, cat -> "$cat=${(probabilities[i] * 100).toInt()}%" }
        Timber.i("NSFW Classifier: Probabilities: $probLog")

        // Find max probability and its category
        val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        val maxProb = probabilities[maxIndex]

        // Calculate total NSFW probability (hentai + porn + sexy)
        // Indices: 0=drawings, 1=hentai, 2=neutral, 3=porn, 4=sexy
        val hentaiProb = probabilities.getOrElse(1) { 0f }
        val pornProb = probabilities.getOrElse(3) { 0f }
        val sexyProb = probabilities.getOrElse(4) { 0f }
        val nsfwProb = hentaiProb + pornProb + sexyProb

        Timber.i("NSFW Classifier: Total NSFW probability: ${(nsfwProb * 100).toInt()}% (threshold: ${(NSFW_THRESHOLD * 100).toInt()}%)")

        // Check if the top category is safe AND NSFW prob is low
        if (maxIndex in SAFE_INDICES && nsfwProb < NSFW_THRESHOLD) {
            Timber.d("NSFW Classifier: Image is SAFE (top category: ${CATEGORIES[maxIndex]}, NSFW: ${(nsfwProb * 100).toInt()}%)")
            return ImageAnalysisResult.safe()
        }

        // If NSFW probability is below threshold, mark as safe
        if (nsfwProb < NSFW_THRESHOLD) {
            Timber.d("NSFW Classifier: Image is SAFE (NSFW below threshold: ${(nsfwProb * 100).toInt()}%)")
            return ImageAnalysisResult.safe()
        }

        // Find the top NSFW category
        val topNsfwIndex = listOf(1, 3, 4).maxByOrNull { probabilities.getOrElse(it) { 0f } } ?: 3
        val topNsfwCategory = CATEGORIES.getOrElse(topNsfwIndex) { "inappropriate" }

        // Map GantMan's categories to user-friendly names for alerts
        val displayCategory = when (topNsfwCategory) {
            "hentai" -> "hentai"
            "porn" -> "nsfw"
            "sexy" -> "sexy"
            else -> "inappropriate"
        }

        // Use total NSFW probability as confidence (more accurate than single class)
        val confidence = nsfwProb.coerceAtMost(1.0f)

        Timber.w("NSFW Classifier: Image FLAGGED as $displayCategory (confidence: ${(confidence * 100).toInt()}%)")

        return ImageAnalysisResult(
            isFlagged = true,
            confidence = confidence,
            categories = listOf(displayCategory),
            reason = "AI detected: $topNsfwCategory (${(confidence * 100).toInt()}% combined NSFW)"
        )
    }

    /**
     * Fallback heuristic when TFLite model is not available.
     * Uses simple skin-tone detection as a rough indicator.
     */
    private fun classifyWithHeuristic(bitmap: Bitmap): ImageAnalysisResult {
        val width = bitmap.width
        val height = bitmap.height
        var skinPixels = 0
        var totalPixels = 0

        // Sample every 8th pixel for speed
        val step = 8

        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = bitmap.getPixel(x, y)
                totalPixels++

                if (isSkinTone(pixel)) {
                    skinPixels++
                }
            }
        }

        val skinRatio = if (totalPixels > 0) skinPixels.toFloat() / totalPixels else 0f

        // High skin ratio might indicate inappropriate content
        return if (skinRatio > 0.5f) {
            ImageAnalysisResult(
                isFlagged = true,
                confidence = skinRatio * 0.6f,  // Lower confidence for heuristic
                categories = listOf("potentially_inappropriate"),
                reason = "Heuristic: high skin-tone ratio (${(skinRatio * 100).toInt()}%)"
            )
        } else {
            ImageAnalysisResult.safe()
        }
    }

    private fun isSkinTone(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF

        // Skin tone detection heuristic
        return r > 95 && g > 40 && b > 20 &&
                r > g && r > b &&
                (maxOf(r, g, b) - minOf(r, g, b)) > 15 &&
                kotlin.math.abs(r - g) > 15
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
    }
}
