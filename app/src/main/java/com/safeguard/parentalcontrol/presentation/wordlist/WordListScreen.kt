package com.safeguard.parentalcontrol.presentation.wordlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.safeguard.parentalcontrol.data.model.CustomWordResponse
import com.safeguard.parentalcontrol.data.model.WordListType

/**
 * Screen for parents to manage custom word lists (whitelist/blacklist)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordListScreen(
    onNavigateBack: () -> Unit,
    viewModel: WordListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    // Show snackbar for messages
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error, uiState.successMessage) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom Word Lists") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirmDialog = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear list")
                    }
                    IconButton(onClick = { viewModel.loadWordLists() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add word")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab row
            TabRow(
                selectedTabIndex = if (uiState.selectedTab == WordListTab.BLACKLIST) 0 else 1
            ) {
                Tab(
                    selected = uiState.selectedTab == WordListTab.BLACKLIST,
                    onClick = { viewModel.selectTab(WordListTab.BLACKLIST) },
                    text = { Text("Blacklist (${uiState.blacklist.size})") },
                    icon = { Icon(Icons.Default.Block, contentDescription = null) }
                )
                Tab(
                    selected = uiState.selectedTab == WordListTab.WHITELIST,
                    onClick = { viewModel.selectTab(WordListTab.WHITELIST) },
                    text = { Text("Whitelist (${uiState.whitelist.size})") },
                    icon = { Icon(Icons.Default.Check, contentDescription = null) }
                )
            }

            // Search and filter section
            SearchAndFilterSection(
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                selectedCategory = uiState.selectedCategoryFilter,
                availableCategories = uiState.availableCategories,
                onCategorySelected = { viewModel.setCategoryFilter(it) },
                showCategoryFilter = uiState.selectedTab == WordListTab.BLACKLIST,
                onClearFilters = { viewModel.clearFilters() },
                hasActiveFilters = uiState.searchQuery.isNotBlank() || uiState.selectedCategoryFilter != null
            )

            // Description card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (uiState.selectedTab == WordListTab.BLACKLIST)
                            "Blacklisted Words"
                        else
                            "Whitelisted Words",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (uiState.selectedTab == WordListTab.BLACKLIST)
                            "These words will always be flagged as inappropriate, even if they don't match the default patterns."
                        else
                            "These words will be allowed and won't trigger alerts, even if they match default patterns (except for safety-critical content like self-harm).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Loading indicator
            if (uiState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Word list - uses filtered results from ViewModel
            val filteredList = uiState.filteredList
            val totalCount = if (uiState.selectedTab == WordListTab.BLACKLIST)
                uiState.blacklist.size
            else
                uiState.whitelist.size
            val hasActiveFilters = uiState.searchQuery.isNotBlank() || uiState.selectedCategoryFilter != null

            if (filteredList.isEmpty() && !uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (hasActiveFilters)
                                Icons.Default.SearchOff
                            else if (uiState.selectedTab == WordListTab.BLACKLIST)
                                Icons.Default.Block
                            else
                                Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (hasActiveFilters)
                                "No matching words found"
                            else
                                "No words in ${if (uiState.selectedTab == WordListTab.BLACKLIST) "blacklist" else "whitelist"}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (hasActiveFilters)
                                "Try adjusting your search or filters"
                            else
                                "Tap + to add words",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (hasActiveFilters) {
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(onClick = { viewModel.clearFilters() }) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear Filters")
                            }
                        }
                    }
                }
            } else {
                // Show filter results count if filters are active
                if (hasActiveFilters && filteredList.isNotEmpty()) {
                    Text(
                        text = "Showing ${filteredList.size} of $totalCount words",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredList, key = { it.id }) { word ->
                        WordListItem(
                            word = word,
                            onDelete = { viewModel.removeWord(word.id) }
                        )
                    }
                }
            }
        }
    }

    // Add word dialog
    if (showAddDialog) {
        AddWordDialog(
            isBlacklist = uiState.selectedTab == WordListTab.BLACKLIST,
            onDismiss = { showAddDialog = false },
            onConfirm = { word, category, caseSensitive, wholeWordOnly ->
                if (uiState.selectedTab == WordListTab.BLACKLIST) {
                    viewModel.addToBlacklist(word, category, caseSensitive, wholeWordOnly)
                } else {
                    viewModel.addToWhitelist(word)
                }
                showAddDialog = false
            }
        )
    }

    // Clear confirmation dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear ${if (uiState.selectedTab == WordListTab.BLACKLIST) "Blacklist" else "Whitelist"}?") },
            text = {
                Text("This will remove all words from the ${if (uiState.selectedTab == WordListTab.BLACKLIST) "blacklist" else "whitelist"}. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val listType = if (uiState.selectedTab == WordListTab.BLACKLIST)
                            WordListType.BLACKLIST
                        else
                            WordListType.WHITELIST
                        viewModel.clearList(listType)
                        showClearConfirmDialog = false
                    }
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun WordListItem(
    word: CustomWordResponse,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = word.word,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (word.category != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = { },
                            label = { Text(word.category, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(24.dp)
                        )
                        if (word.caseSensitive) {
                            AssistChip(
                                onClick = { },
                                label = { Text("Case sensitive", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                        if (!word.wholeWordOnly) {
                            AssistChip(
                                onClick = { },
                                label = { Text("Partial match", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWordDialog(
    isBlacklist: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (word: String, category: String, caseSensitive: Boolean, wholeWordOnly: Boolean) -> Unit
) {
    var word by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("other") }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWordOnly by remember { mutableStateOf(true) }
    var categoryExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isBlacklist) "Add to Blacklist" else "Add to Whitelist")
        },
        text = {
            Column {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Word or phrase") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (isBlacklist) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // Category dropdown
                    ExposedDropdownMenuBox(
                        expanded = categoryExpanded,
                        onExpandedChange = { categoryExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedCategory,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("Category") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = categoryExpanded,
                            onDismissRequest = { categoryExpanded = false }
                        ) {
                            WORD_CATEGORIES.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.replace("_", " ").replaceFirstChar { it.uppercase() }) },
                                    onClick = {
                                        selectedCategory = category
                                        categoryExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Options
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = caseSensitive,
                            onCheckedChange = { caseSensitive = it }
                        )
                        Text("Case sensitive")
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = wholeWordOnly,
                            onCheckedChange = { wholeWordOnly = it }
                        )
                        Text("Match whole word only")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (word.isNotBlank()) {
                        onConfirm(word, selectedCategory, caseSensitive, wholeWordOnly)
                    }
                },
                enabled = word.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Search and filter section for word lists.
 * Includes a search field and category filter dropdown (for blacklist only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchAndFilterSection(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedCategory: String?,
    availableCategories: List<String>,
    onCategorySelected: (String?) -> Unit,
    showCategoryFilter: Boolean,
    onClearFilters: () -> Unit,
    hasActiveFilters: Boolean
) {
    var categoryDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search words...") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search")
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
        )

        // Category filter (only for blacklist and when categories exist)
        AnimatedVisibility(visible = showCategoryFilter && availableCategories.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category dropdown
                ExposedDropdownMenuBox(
                    expanded = categoryDropdownExpanded,
                    onExpandedChange = { categoryDropdownExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = selectedCategory?.let { formatCategoryName(it) } ?: "All categories",
                        onValueChange = { },
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        label = { Text("Category") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryDropdownExpanded)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    )

                    ExposedDropdownMenu(
                        expanded = categoryDropdownExpanded,
                        onDismissRequest = { categoryDropdownExpanded = false }
                    ) {
                        // "All categories" option
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "All categories",
                                    fontWeight = if (selectedCategory == null) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onCategorySelected(null)
                                categoryDropdownExpanded = false
                            },
                            leadingIcon = {
                                if (selectedCategory == null) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )

                        Divider()

                        // Category options
                        availableCategories.forEach { category ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        formatCategoryName(category),
                                        fontWeight = if (selectedCategory == category) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    onCategorySelected(category)
                                    categoryDropdownExpanded = false
                                },
                                leadingIcon = {
                                    if (selectedCategory == category) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                // Clear filters button (shown when filters are active)
                AnimatedVisibility(visible = hasActiveFilters) {
                    IconButton(
                        onClick = onClearFilters,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.FilterListOff, contentDescription = "Clear all filters")
                    }
                }
            }
        }
    }
}

/**
 * Formats a category name for display (replaces underscores with spaces, capitalizes first letter).
 */
private fun formatCategoryName(category: String): String {
    return category.replace("_", " ").replaceFirstChar { it.uppercase() }
}
