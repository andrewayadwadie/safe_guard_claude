package com.safeguard.parentalcontrol.presentation.settings

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.designsystem.mirrorInRtl
import com.safeguard.parentalcontrol.util.LocaleHelper

/**
 * In-app viewer for the bundled legal documents. Renders the self-contained,
 * app-themed HTML shipped in `assets/websites/<slug>/` inside a WebView instead
 * of opening an external browser. Content is fully offline (local CSS/JS/fonts).
 *
 * @param title    Top bar title (e.g. "Privacy Policy").
 * @param assetDir Folder under `assets/websites/` holding `index.html`
 *                 (e.g. "privacy-policy" or "terms").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalDocScreen(
    title: String,
    assetDir: String,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.common_back), modifier = Modifier.mirrorInRtl())
                    }
                }
            )
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { context ->
                WebView(context).apply {
                    @SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    // Keep in-page navigation inside the WebView.
                    webViewClient = WebViewClient()
                    val lang = LocaleHelper.getLanguage(context)
                    loadUrl("file:///android_asset/websites/$assetDir/index.html?lang=$lang")
                }
            }
        )
    }
}
