package com.safeguard.parentalcontrol.presentation.textreview

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.util.FlaggedTextDelivery
import com.safeguard.parentalcontrol.util.FlaggedTextEvent
import com.safeguard.parentalcontrol.util.FlaggedTextStore
import com.safeguard.parentalcontrol.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the on-device flagged-text review screen.
 *
 * Reads phrases from [FlaggedTextStore], which is local-only and never transmitted.
 * Accessed by a parent (behind the PIN gate) when physically on the child's device.
 */
@HiltViewModel
class TextReviewViewModel @Inject constructor(
    private val flaggedTextStore: FlaggedTextStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(TextReviewUiState())
    val uiState: StateFlow<TextReviewUiState> = _uiState.asStateFlow()

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val events = withContext(Dispatchers.IO) {
                flaggedTextStore.getAll().map { it.toUi() }
            }
            _uiState.value = TextReviewUiState(isLoading = false, events = events)
        }
    }

    /** Delete a single reviewed phrase. */
    fun delete(timestamp: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { flaggedTextStore.delete(timestamp) }
            load()
        }
    }

    /** Clear the whole on-device log. */
    fun clearAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { flaggedTextStore.clear() }
            load()
        }
    }

    private fun FlaggedTextEvent.toUi(): FlaggedTextUi = FlaggedTextUi(
        phrase = phrase,
        appName = appName,
        formattedCategory = formatCategory(category),
        formattedDate = formatDate(timestamp),
        timestamp = timestamp,
        delivery = delivery
    )

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", Locale.US).format(Date(timestamp))

    private fun formatCategory(category: String): String = when (category.lowercase()) {
        "profanity" -> getString(R.string.textreview_cat_profanity)
        "self_harm", "selfharm" -> getString(R.string.textreview_cat_selfharm)
        "violence" -> getString(R.string.textreview_cat_violence)
        "sexual", "sexual_content" -> getString(R.string.textreview_cat_sexual)
        "bullying", "harassment" -> getString(R.string.textreview_cat_bullying)
        "grooming" -> getString(R.string.textreview_cat_grooming)
        "unknown", "" -> getString(R.string.textreview_cat_unknown)
        else -> category.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

data class TextReviewUiState(
    val isLoading: Boolean = false,
    val events: List<FlaggedTextUi> = emptyList()
)

data class FlaggedTextUi(
    val phrase: String,
    val appName: String,
    val formattedCategory: String,
    val formattedDate: String,
    val timestamp: Long,
    /** Whether an alert for this phrase actually reached the parent; drives the review badge. */
    val delivery: FlaggedTextDelivery = FlaggedTextDelivery.UNKNOWN
)
