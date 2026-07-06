package com.safeguard.parentalcontrol.presentation.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Gates [content] behind the parent PIN. Until the PIN is entered this composition shows
 * only the PIN dialog over a blank surface — the protected content is never composed, so
 * nothing leaks.
 *
 * The unlocked flag uses [remember] (deliberately NOT rememberSaveable): it resets on every
 * fresh composition, including after process death + navigation-state restore. That closes
 * the bypass where Android could restore the back stack straight onto a review screen and
 * skip a gate that only lived at the entry tap.
 */
@Composable
fun ParentPinGate(
    onCancel: () -> Unit,
    viewModel: ParentPinViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    var unlocked by remember { mutableStateOf(false) }

    if (unlocked) {
        content()
    } else {
        Surface(modifier = Modifier.fillMaxSize()) {}
        ParentPinDialog(
            hasPin = viewModel.hasParentPin,
            onVerify = viewModel::verifyParentPin,
            onCreate = viewModel::setParentPin,
            onSuccess = { unlocked = true },
            onDismiss = onCancel
        )
    }
}
