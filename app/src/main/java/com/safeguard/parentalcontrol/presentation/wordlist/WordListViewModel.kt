package com.safeguard.parentalcontrol.presentation.wordlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.CustomWordResponse
import com.safeguard.parentalcontrol.data.model.WordListType
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.CustomWordRepository
import com.safeguard.parentalcontrol.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI State for word list management screen
 */
data class WordListUiState(
    val isLoading: Boolean = false,
    val whitelist: List<CustomWordResponse> = emptyList(),
    val blacklist: List<CustomWordResponse> = emptyList(),
    val selectedTab: WordListTab = WordListTab.BLACKLIST,
    val searchQuery: String = "",
    val selectedCategoryFilter: String? = null, // null means "All categories"
    val error: String? = null,
    val successMessage: String? = null
) {
    /**
     * Get filtered list based on current tab, search query, and category filter.
     */
    val filteredList: List<CustomWordResponse>
        get() {
            val baseList = if (selectedTab == WordListTab.BLACKLIST) blacklist else whitelist

            return baseList.filter { word ->
                // Search filter
                val matchesSearch = searchQuery.isBlank() ||
                    word.word.contains(searchQuery, ignoreCase = true)

                // Category filter (only for blacklist)
                val matchesCategory = selectedTab != WordListTab.BLACKLIST ||
                    selectedCategoryFilter == null ||
                    word.category == selectedCategoryFilter

                matchesSearch && matchesCategory
            }
        }

    /**
     * Get unique categories from blacklist for filter dropdown.
     */
    val availableCategories: List<String>
        get() = blacklist.mapNotNull { it.category }.distinct().sorted()
}

/**
 * Tabs for word list screen
 */
enum class WordListTab {
    BLACKLIST,
    WHITELIST
}

/**
 * Categories for blacklisted words
 */
val WORD_CATEGORIES = listOf(
    "profanity",
    "violence",
    "bullying",
    "sexual",
    "drugs",
    "self_harm",
    "predator",
    "other"
)

/**
 * ViewModel for managing custom word lists (whitelist/blacklist)
 */
@HiltViewModel
class WordListViewModel @Inject constructor(
    private val customWordRepository: CustomWordRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(WordListUiState())
    val uiState: StateFlow<WordListUiState> = _uiState.asStateFlow()

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    init {
        loadWordLists()
    }

    /**
     * Load word lists from backend
     */
    fun loadWordLists() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = customWordRepository.getWordLists()) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            whitelist = result.data.whitelist,
                            blacklist = result.data.blacklist
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Add word to whitelist
     */
    fun addToWhitelist(word: String) {
        val trimmedWord = word.trim()

        if (trimmedWord.isBlank()) {
            _uiState.update { it.copy(error = getString(R.string.wordlist_error_word_empty)) }
            return
        }

        // Check if already exists
        if (_uiState.value.whitelist.any { it.word.equals(trimmedWord, ignoreCase = true) }) {
            _uiState.update { it.copy(error = getString(R.string.wordlist_error_whitelist_dup)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = customWordRepository.addWord(
                word = trimmedWord,
                listType = WordListType.WHITELIST
            )) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            whitelist = it.whitelist + result.data,
                            successMessage = getString(R.string.wordlist_msg_added_whitelist)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    /**
     * Add word to blacklist
     */
    fun addToBlacklist(
        word: String,
        category: String = "other",
        caseSensitive: Boolean = false,
        wholeWordOnly: Boolean = true
    ) {
        val trimmedWord = word.trim()

        if (trimmedWord.isBlank()) {
            _uiState.update { it.copy(error = getString(R.string.wordlist_error_word_empty)) }
            return
        }

        // Check if already exists
        if (_uiState.value.blacklist.any { it.word.equals(trimmedWord, ignoreCase = true) }) {
            _uiState.update { it.copy(error = getString(R.string.wordlist_error_blacklist_dup)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = customWordRepository.addWord(
                word = trimmedWord,
                listType = WordListType.BLACKLIST,
                category = category,
                caseSensitive = caseSensitive,
                wholeWordOnly = wholeWordOnly
            )) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            blacklist = it.blacklist + result.data,
                            successMessage = getString(R.string.wordlist_msg_added_blacklist)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    /**
     * Remove word from list
     */
    fun removeWord(wordId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = customWordRepository.deleteWord(wordId)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            whitelist = it.whitelist.filter { w -> w.id != wordId },
                            blacklist = it.blacklist.filter { w -> w.id != wordId },
                            successMessage = getString(R.string.wordlist_msg_removed)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    /**
     * Clear all words from a list
     */
    fun clearList(listType: WordListType) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = customWordRepository.clearWordList(listType)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        if (listType == WordListType.WHITELIST) {
                            it.copy(
                                isLoading = false,
                                whitelist = emptyList(),
                                successMessage = getString(R.string.wordlist_msg_cleared_whitelist)
                            )
                        } else {
                            it.copy(
                                isLoading = false,
                                blacklist = emptyList(),
                                successMessage = getString(R.string.wordlist_msg_cleared_blacklist)
                            )
                        }
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    /**
     * Switch between tabs
     */
    fun selectTab(tab: WordListTab) {
        _uiState.update { it.copy(selectedTab = tab, searchQuery = "", selectedCategoryFilter = null) }
    }

    /**
     * Update search query
     */
    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /**
     * Set category filter (null for "All categories")
     */
    fun setCategoryFilter(category: String?) {
        _uiState.update { it.copy(selectedCategoryFilter = category) }
    }

    /**
     * Clear search and filters
     */
    fun clearFilters() {
        _uiState.update { it.copy(searchQuery = "", selectedCategoryFilter = null) }
    }

    /**
     * Clear error and success messages
     */
    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }
}
