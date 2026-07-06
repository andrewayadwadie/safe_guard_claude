package com.safeguard.parentalcontrol.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.presentation.theme.*

// ============================================================================
// BUTTONS - Custom button styles for Haris
// ============================================================================

/**
 * Primary action button with optional icon and loading state
 */
@Composable
fun SafeGuardButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector? = null,
    iconPosition: IconPosition = IconPosition.START,
    size: ButtonSize = ButtonSize.MEDIUM
) {
    val height = when (size) {
        ButtonSize.SMALL -> SafeGuardDimens.buttonHeightSmall
        ButtonSize.MEDIUM -> SafeGuardDimens.buttonHeightMedium
        ButtonSize.LARGE -> SafeGuardDimens.buttonHeightLarge
    }

    val shape = when (size) {
        ButtonSize.SMALL -> RoundedCornerShape(8.dp)
        ButtonSize.MEDIUM -> RoundedCornerShape(12.dp)
        ButtonSize.LARGE -> RoundedCornerShape(16.dp)
    }

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.height(height),
        shape = shape,
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }

        AnimatedVisibility(
            visible = !isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null && iconPosition == IconPosition.START) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = text,
                    fontWeight = FontWeight.Medium
                )

                if (icon != null && iconPosition == IconPosition.END) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Secondary/outlined button
 */
@Composable
fun SafeGuardOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector? = null,
    iconPosition: IconPosition = IconPosition.START,
    size: ButtonSize = ButtonSize.MEDIUM,
    borderColor: Color = MaterialTheme.colorScheme.outline
) {
    val height = when (size) {
        ButtonSize.SMALL -> SafeGuardDimens.buttonHeightSmall
        ButtonSize.MEDIUM -> SafeGuardDimens.buttonHeightMedium
        ButtonSize.LARGE -> SafeGuardDimens.buttonHeightLarge
    }

    val shape = when (size) {
        ButtonSize.SMALL -> RoundedCornerShape(8.dp)
        ButtonSize.MEDIUM -> RoundedCornerShape(12.dp)
        ButtonSize.LARGE -> RoundedCornerShape(16.dp)
    }

    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.height(height),
        shape = shape,
        border = BorderStroke(1.dp, if (enabled) borderColor else borderColor.copy(alpha = 0.5f)),
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        }

        AnimatedVisibility(
            visible = !isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null && iconPosition == IconPosition.START) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = text,
                    fontWeight = FontWeight.Medium
                )

                if (icon != null && iconPosition == IconPosition.END) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Tonal/filled tonal button
 */
@Composable
fun SafeGuardTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector? = null,
    iconPosition: IconPosition = IconPosition.START,
    size: ButtonSize = ButtonSize.MEDIUM
) {
    val height = when (size) {
        ButtonSize.SMALL -> SafeGuardDimens.buttonHeightSmall
        ButtonSize.MEDIUM -> SafeGuardDimens.buttonHeightMedium
        ButtonSize.LARGE -> SafeGuardDimens.buttonHeightLarge
    }

    val shape = when (size) {
        ButtonSize.SMALL -> RoundedCornerShape(8.dp)
        ButtonSize.MEDIUM -> RoundedCornerShape(12.dp)
        ButtonSize.LARGE -> RoundedCornerShape(16.dp)
    }

    FilledTonalButton(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.height(height),
        shape = shape,
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        AnimatedVisibility(
            visible = !isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null && iconPosition == IconPosition.START) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = text,
                    fontWeight = FontWeight.Medium
                )

                if (icon != null && iconPosition == IconPosition.END) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Danger/destructive action button
 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector? = null,
    size: ButtonSize = ButtonSize.MEDIUM
) {
    val height = when (size) {
        ButtonSize.SMALL -> SafeGuardDimens.buttonHeightSmall
        ButtonSize.MEDIUM -> SafeGuardDimens.buttonHeightMedium
        ButtonSize.LARGE -> SafeGuardDimens.buttonHeightLarge
    }

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SemanticColors.error,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Success action button
 */
@Composable
fun SuccessButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector? = null,
    size: ButtonSize = ButtonSize.MEDIUM
) {
    val height = when (size) {
        ButtonSize.SMALL -> SafeGuardDimens.buttonHeightSmall
        ButtonSize.MEDIUM -> SafeGuardDimens.buttonHeightMedium
        ButtonSize.LARGE -> SafeGuardDimens.buttonHeightLarge
    }

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SemanticColors.success,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Icon button with label (vertical layout)
 */
@Composable
fun IconButtonWithLabel(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = 24.dp
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(iconSize),
                tint = tint
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Chip-style toggle button
 */
@Composable
fun ToggleChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) },
        leadingIcon = if (icon != null && isSelected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else if (icon != null) {
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else null,
        modifier = modifier
    )
}

/**
 * Floating action button with extended style
 */
@Composable
fun SafeGuardFAB(
    icon: ImageVector,
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    extended: Boolean = true
) {
    if (extended && text != null) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text)
        }
    } else {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text
            )
        }
    }
}

enum class ButtonSize {
    SMALL, MEDIUM, LARGE
}

enum class IconPosition {
    START, END
}
