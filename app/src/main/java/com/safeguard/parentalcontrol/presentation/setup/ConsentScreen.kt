package com.safeguard.parentalcontrol.presentation.setup

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.safeguard.parentalcontrol.util.Constants
import timber.log.Timber

/**
 * Monitoring disclosure + consent screen.
 *
 * Shown once during child-device setup, BEFORE any permission is requested. It tells
 * the setting-up parent exactly what SafeGuard collects and asks for an affirmative,
 * unambiguous acknowledgement (Google Play Prominent Disclosure & Consent). Consent is
 * recorded as a flag that every monitoring service checks before it may run, so
 * monitoring can never begin until the parent has accepted this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentScreen(
    onConsentGranted: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: ConsentViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var acknowledged by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monitoring & Privacy") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "How SafeGuard protects your child",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "To keep your child safe, SafeGuard monitors this device. " +
                        "Please review what is collected before you continue. Set up by a " +
                        "parent or guardian.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            DisclosureItem(
                icon = Icons.Default.Chat,
                title = "Messages & typed text",
                detail = "Text typed and shown in apps is checked to detect bullying, " +
                        "grooming, and other harmful content."
            )
            DisclosureItem(
                icon = Icons.Default.BarChart,
                title = "App & screen-time usage",
                detail = "Which apps are used and for how long, to enforce daily limits " +
                        "and bedtime."
            )
            DisclosureItem(
                icon = Icons.Default.Image,
                title = "Photos & images",
                detail = "Images are scanned on this device to blur explicit content."
            )
            DisclosureItem(
                icon = Icons.Default.Language,
                title = "Web activity",
                detail = "Web addresses are filtered to block inappropriate sites. A " +
                        "local VPN does this on-device."
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = "Analysis happens on this device. Flagged events and alerts are " +
                            "sent to the parent's SafeGuard account so you can review them. " +
                            "Your child is shown a notice that monitoring is active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(Constants.PRIVACY_POLICY_URL))
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to open privacy policy")
                    }
                }
            ) {
                Text("Read our Privacy Policy")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = acknowledged,
                    onCheckedChange = { acknowledged = it }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "I am this child's parent or guardian, and I consent to " +
                            "SafeGuard monitoring this device as described above.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.grantConsent()
                    onConsentGranted()
                },
                enabled = acknowledged,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("I Understand & Consent")
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onNavigateBack) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun DisclosureItem(
    icon: ImageVector,
    title: String,
    detail: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
