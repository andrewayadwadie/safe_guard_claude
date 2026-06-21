package com.safeguard.parentalcontrol.presentation.imagereview

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.util.ImageBlurManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the Image Review screen.
 *
 * This screen is used by parents when physically on the child's device
 * to review blurred images and decide whether to restore or delete them.
 */
@HiltViewModel
class ImageReviewViewModel @Inject constructor(
    private val imageBlurManager: ImageBlurManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageReviewUiState())
    val uiState: StateFlow<ImageReviewUiState> = _uiState.asStateFlow()

    private val _selectedImage = MutableStateFlow<PendingImage?>(null)
    val selectedImage: StateFlow<PendingImage?> = _selectedImage.asStateFlow()

    init {
        loadPendingReviews()
    }

    /**
     * Load all pending image reviews.
     */
    fun loadPendingReviews() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val pending = withContext(Dispatchers.IO) {
                    imageBlurManager.getPendingReviews().map { metadata ->
                        PendingImage(
                            backupId = metadata.backupId,
                            originalPath = metadata.originalPath,
                            category = metadata.category,
                            confidence = metadata.confidence,
                            timestamp = metadata.timestamp,
                            originalName = metadata.originalName,
                            formattedDate = formatDate(metadata.timestamp),
                            formattedCategory = formatCategory(metadata.category),
                            confidencePercent = (metadata.confidence * 100).toInt()
                        )
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    pendingImages = pending,
                    error = null
                )

                Timber.d("Loaded ${pending.size} pending image reviews")
            } catch (e: Exception) {
                Timber.e(e, "Error loading pending reviews")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load pending reviews"
                )
            }
        }
    }

    /**
     * Select an image to view details.
     */
    fun selectImage(image: PendingImage) {
        _selectedImage.value = image
    }

    /**
     * Clear selected image.
     */
    fun clearSelection() {
        _selectedImage.value = null
    }

    /**
     * Approve image - restore the original (unblurred) version.
     */
    fun approveImage(backupId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)

            try {
                val success = withContext(Dispatchers.IO) {
                    imageBlurManager.restoreOriginal(backupId)
                }

                if (success) {
                    Timber.i("Image approved and restored: $backupId")
                    _selectedImage.value = null
                    loadPendingReviews() // Refresh list
                } else {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        error = "Failed to restore image"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Error approving image")
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Error: ${e.message}"
                )
            }
        }
    }

    /**
     * Reject image - delete both blurred and original.
     */
    fun rejectImage(backupId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)

            try {
                val success = withContext(Dispatchers.IO) {
                    imageBlurManager.deleteImage(backupId)
                }

                if (success) {
                    Timber.i("Image rejected and deleted: $backupId")
                    _selectedImage.value = null
                    loadPendingReviews() // Refresh list
                } else {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        error = "Failed to delete image"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Error rejecting image")
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Error: ${e.message}"
                )
            }
        }
    }

    /**
     * Get the backup file path for viewing original.
     */
    fun getOriginalBackupPath(backupId: String): String? {
        return try {
            val metadata = imageBlurManager.getMetadata(backupId)
            if (metadata != null) {
                // The backup is stored in app's private files directory
                File(
                    File(android.os.Environment.getDataDirectory(),
                        "data/com.safeguard.parentalcontrol/files/image_backup"),
                    "$backupId.jpg"
                ).absolutePath
            } else null
        } catch (e: Exception) {
            Timber.e(e, "Error getting backup path")
            null
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun formatCategory(category: String): String {
        return when (category.lowercase()) {
            "nsfw", "porn" -> "Adult Content"
            "sexy" -> "Suggestive Content"
            "hentai" -> "Animated Adult Content"
            "violence" -> "Violent Content"
            "nudity" -> "Nudity"
            else -> category.replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * UI state for Image Review screen.
 */
data class ImageReviewUiState(
    val isLoading: Boolean = false,
    val isProcessing: Boolean = false,
    val pendingImages: List<PendingImage> = emptyList(),
    val error: String? = null
)

/**
 * Pending image data for UI.
 */
data class PendingImage(
    val backupId: String,
    val originalPath: String,
    val category: String,
    val confidence: Float,
    val timestamp: Long,
    val originalName: String,
    val formattedDate: String,
    val formattedCategory: String,
    val confidencePercent: Int
)
