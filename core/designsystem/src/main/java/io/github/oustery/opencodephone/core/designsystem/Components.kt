package io.github.oustery.opencodephone.core.designsystem

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Status chip used across screens (connection state, phase labels). */
enum class OcChipTone { PRIMARY, SECONDARY, TERTIARY, ERROR, NEUTRAL }

@Composable
fun OcStatusChip(
    text: String,
    tone: OcChipTone = OcChipTone.NEUTRAL,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        OcChipTone.PRIMARY -> colors.primaryContainer to colors.onPrimaryContainer
        OcChipTone.SECONDARY -> colors.secondaryContainer to colors.onSecondaryContainer
        OcChipTone.TERTIARY -> colors.tertiaryContainer to colors.onTertiaryContainer
        OcChipTone.ERROR -> colors.errorContainer to colors.onErrorContainer
        OcChipTone.NEUTRAL -> colors.surfaceContainerHigh to colors.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Quiet section header (eyebrow style). */
@Composable
fun OcSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** Empty state with optional action. */
@Composable
fun OcEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionText != null && onAction != null) {
            FilledTonalButton(onClick = onAction) { Text(actionText) }
        }
    }
}

/** Label + value row for settings-like screens. */
@Composable
fun OcInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(label, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer(modifier = Modifier.weight(0.6f)) {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, textAlign = TextAlign.End)
                }
            }
        }
    }
}

/** An action, unlike the passive status label above. */
@Composable
fun OcActionChip(text: String, actionLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = actionLabel },
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Text("▾") },
    )
}

/** State survives rotation; streaming text never resets the user's expansion choice. */
@Composable
fun OcDisclosure(
    title: String,
    expandedDescription: String,
    collapsedDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).semantics {
            stateDescription = if (expanded) expandedDescription else collapsedDescription
        }) {
            Text(if (expanded) "−" else "+", modifier = Modifier.padding(end = 8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
        AnimatedVisibility(visible = expanded) { content() }
    }
}
