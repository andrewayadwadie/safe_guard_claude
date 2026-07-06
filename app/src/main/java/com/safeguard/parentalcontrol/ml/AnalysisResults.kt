package com.safeguard.parentalcontrol.ml

/**
 * Result of text content analysis
 */
data class TextAnalysisResult(
    val isFlagged: Boolean,
    val confidence: Float,
    val categories: List<String>,
    val reason: String?,
    // Per-category app-score map from Stage 2 (AI). Empty for Stage-1 regex hits and the
    // safe() default. Consumed by FlagGating for app-context gating.
    val categoryScores: Map<String, Float> = emptyMap(),
    // Gating-computed alert severity label ("critical"/"high"/"medium"/"low") for Stage-2
    // hits. Null for Stage-1 regex hits (severity derived from category downstream) and safe().
    val severity: String? = null
) {
    companion object {
        fun safe() = TextAnalysisResult(
            isFlagged = false,
            confidence = 0f,
            categories = emptyList(),
            reason = null
        )
    }
}

/**
 * Result of image content analysis
 */
data class ImageAnalysisResult(
    val isFlagged: Boolean,
    val confidence: Float,
    val categories: List<String>,
    val reason: String?,
    val isPending: Boolean = false,
    val error: String? = null
) {
    companion object {
        fun safe() = ImageAnalysisResult(
            isFlagged = false,
            confidence = 0f,
            categories = emptyList(),
            reason = null
        )

        fun pending() = ImageAnalysisResult(
            isFlagged = false,
            confidence = 0f,
            categories = emptyList(),
            reason = null,
            isPending = true
        )

        fun error(message: String) = ImageAnalysisResult(
            isFlagged = false,
            confidence = 0f,
            categories = emptyList(),
            reason = null,
            error = message
        )
    }
}
