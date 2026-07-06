package com.safeguard.parentalcontrol.presentation.children

import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

import com.safeguard.parentalcontrol.presentation.theme.rememberScreenWidth
import com.safeguard.parentalcontrol.presentation.theme.responsiveContentWidth
import com.safeguard.parentalcontrol.presentation.theme.responsiveScreenPadding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.data.model.FamilyLink
import com.safeguard.parentalcontrol.util.formatAsRelative

/**
 * Screen for managing linked children (parent only)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildrenScreen(
    onNavigateBack: () -> Unit,
    onNavigateToChildDevices: (childId: Int, childName: String) -> Unit = { _, _ -> },
    viewModel: ChildrenViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    // Show success snackbar
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearSuccessMessage()
        }
    }

    // Add child dialog
    if (uiState.showAddDialog) {
        AddChildDialog(
            onDismiss = { viewModel.hideAddChildDialog() },
            onConfirm = { pairingCode -> viewModel.addChild(pairingCode) },
            isLoading = uiState.isAddingChild,
            error = uiState.addChildError
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Children") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadChildren() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.showAddChildDialog() },
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text("Add Child") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.children.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.children.isEmpty() -> {
                    EmptyState(
                        modifier = Modifier.align(Alignment.Center),
                        onAddChild = { viewModel.showAddChildDialog() }
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxHeight().responsiveContentWidth(rememberScreenWidth()),
                        contentPadding = PaddingValues(responsiveScreenPadding(rememberScreenWidth())),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = "${uiState.children.size} child${if (uiState.children.size != 1) "ren" else ""} linked",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        items(
                            items = uiState.children,
                            key = { it.childId }
                        ) { child ->
                            ChildCard(
                                child = child,
                                onViewDevices = { onNavigateToChildDevices(child.childId, child.childName) },
                                onRemove = { viewModel.removeChild(child.childId, child.childName) }
                            )
                        }

                        // Space for FAB
                        item { Spacer(modifier = Modifier.height(72.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    onAddChild: () -> Unit
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.FamilyRestroom,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No children linked yet",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Add your child's account to start monitoring their device activity",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onAddChild) {
            Icon(Icons.Default.PersonAdd, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Your First Child")
        }
    }
}

@Composable
private fun ChildCard(
    child: FamilyLink,
    onViewDevices: () -> Unit,
    onRemove: () -> Unit
) {
    var showRemoveDialog by remember { mutableStateOf(false) }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            icon = { Icon(Icons.Default.PersonRemove, contentDescription = null) },
            title = { Text("Remove ${child.childName}?") },
            text = {
                Text("This will unlink ${child.childName}'s account. You will no longer be able to monitor their devices.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        onRemove()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = child.childName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = child.childName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = child.childEmail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Linked ${child.createdAt.formatAsRelative()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = { showRemoveDialog = true }) {
                    Icon(
                        Icons.Default.PersonRemove,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onViewDevices,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View Devices")
                }
            }
        }
    }
}

@Composable
private fun AddChildDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    isLoading: Boolean,
    error: String?
) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
        title = { Text("Add Child") },
        text = {
            Column {
                Text(
                    text = "On your child's device, open SafeGuard and tap \"Link a parent\" to get a pairing code. Enter that code here.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    label = { Text("Pairing Code") },
                    placeholder = { Text("ABCD2345") },
                    singleLine = true,
                    enabled = !isLoading,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (code.isNotBlank()) onConfirm(code) }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(code) },
                enabled = code.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Add Child")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ChildrenPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp)) { EmptyState(onAddChild = {}) }
    }
}
@Preview(name = "Children · Light", showBackground = true)
@Composable private fun ChildrenLightPreview() { SafeGuardTheme(darkTheme = false) { ChildrenPreviewContent() } }
@Preview(name = "Children · Dark", showBackground = true)
@Composable private fun ChildrenDarkPreview() { SafeGuardTheme(darkTheme = true) { ChildrenPreviewContent() } }
