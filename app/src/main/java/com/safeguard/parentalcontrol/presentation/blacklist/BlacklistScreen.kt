package com.safeguard.parentalcontrol.presentation.blacklist

import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

import com.safeguard.parentalcontrol.presentation.theme.rememberScreenWidth
import com.safeguard.parentalcontrol.presentation.theme.responsiveContentWidth
import com.safeguard.parentalcontrol.presentation.theme.responsiveScreenPadding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.designsystem.mirrorInRtl

/**
 * Screen for managing blocked websites (blacklist)
 * Only accessible by parents
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlacklistScreen(
    deviceId: Int,
    deviceName: String,
    onNavigateBack: () -> Unit,
    viewModel: BlacklistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var newDomain by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }

    // Load blacklist on first composition
    LaunchedEffect(deviceId) {
        viewModel.loadBlacklist(deviceId)
    }

    // Show messages
    LaunchedEffect(uiState.error, uiState.successMessage) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
            viewModel.clearMessages()
        }
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.blacklist_title))
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.common_back), modifier = Modifier.mirrorInRtl())
                    }
                },
                actions = {
                    if (uiState.domains.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, stringResource(R.string.blacklist_cd_clear_all))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true }
            ) {
                Icon(Icons.Default.Add, stringResource(R.string.blacklist_cd_add_website))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.domains.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.domains.isEmpty() -> {
                    EmptyBlacklistView(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxHeight().responsiveContentWidth(rememberScreenWidth()),
                        contentPadding = PaddingValues(responsiveScreenPadding(rememberScreenWidth())),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                text = if (uiState.domains.size == 1)
                                    stringResource(R.string.blacklist_count_one)
                                else
                                    stringResource(R.string.blacklist_count_other, uiState.domains.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        items(
                            items = uiState.domains,
                            key = { it }
                        ) { domain ->
                            BlacklistItem(
                                domain = domain,
                                onRemove = { viewModel.removeDomain(deviceId, domain) },
                                isLoading = uiState.isLoading
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(72.dp)) // FAB clearance
                        }
                    }
                }
            }

            // Show loading overlay when performing action
            if (uiState.isLoading && uiState.domains.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }

    // Add domain dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                newDomain = ""
            },
            icon = {
                Icon(Icons.Default.Block, contentDescription = null)
            },
            title = { Text(stringResource(R.string.blacklist_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.blacklist_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = newDomain,
                        onValueChange = { newDomain = it },
                        label = { Text(stringResource(R.string.blacklist_domain_label)) },
                        placeholder = { Text(stringResource(R.string.blacklist_domain_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addDomain(deviceId, newDomain)
                        newDomain = ""
                        showAddDialog = false
                    },
                    enabled = newDomain.isNotBlank()
                ) {
                    Text(stringResource(R.string.blacklist_block_button))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    newDomain = ""
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Clear confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.blacklist_clear_title)) },
            text = {
                Text(stringResource(R.string.blacklist_clear_message, uiState.domains.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearBlacklist(deviceId)
                        showClearDialog = false
                    }
                ) {
                    Text(
                        stringResource(R.string.blacklist_clear_all_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun BlacklistItem(
    domain: String,
    onRemove: () -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = domain,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onRemove,
                enabled = !isLoading
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.screentime_cd_remove),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun EmptyBlacklistView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.blacklist_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.blacklist_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun BlacklistPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BlacklistItem("example.com", {}, false)
            BlacklistItem("ads.tracker.net", {}, true)
        }
    }
}
@Preview(name = "Blacklist · Light", showBackground = true)
@Composable private fun BlacklistLightPreview() { SafeGuardTheme(darkTheme = false) { BlacklistPreviewContent() } }
@Preview(name = "Blacklist · Dark", showBackground = true)
@Composable private fun BlacklistDarkPreview() { SafeGuardTheme(darkTheme = true) { BlacklistPreviewContent() } }
