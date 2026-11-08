package io.github.oustery.opencodephone.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/** Offline, font-scale-aware code roles inherit the application's reading rhythm. */
object CodeTypography {
    val output: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val editor: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
}
