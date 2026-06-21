package com.safeguard.parentalcontrol.ml

/**
 * Result of text content analysis
 */
data class TextAnalysisResult(
    val isFlagged: Boolean,
    val confidence: Float,
    val categories: List<String>,
    val reason: String?
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
