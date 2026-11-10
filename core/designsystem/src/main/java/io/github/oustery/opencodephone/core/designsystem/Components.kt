package io.github.oustery.opencodephone.core.designsystem

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph

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

/**
 * Settings-style row whose whole surface toggles [checked] with the given
 * accessibility [role] (UI audit A4). The inner control (Checkbox/Switch)
 * must pass onCheckedChange = null, so the row — label included — is one
 * merged, clickable node instead of a dead label next to a tiny control.
 */
@Composable
fun OcToggleableRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    role: Role,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .toggleable(value = checked, role = role, onValueChange = onCheckedChange)
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}

/** An action, unlike the passive status label above. */
@Composable
fun OcActionChip(text: String, actionLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = actionLabel },
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        // Real chevron icon instead of a text glyph (UI/UX stage D): keeps
        // the chip in the same stroke language and font as the rest of the UI.
        leadingIcon = {
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
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
    // A real chevron rotates between states instead of swapping +/− glyphs
    // (M3E audit E-003).
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "disclosure-chevron-rotation",
    )
    // Quiet haptic confirmation on state change (M3E audit E-010).
    val haptics = LocalHapticFeedback.current
    Column(modifier = modifier) {
        TextButton(onClick = {
            expanded = !expanded
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }, modifier = Modifier.heightIn(min = 48.dp).semantics {
            stateDescription = if (expanded) expandedDescription else collapsedDescription
        }) {
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                modifier = Modifier.size(20.dp).rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
        // Spatial expand/collapse instead of an in-place fade snap (E-012).
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(durationMillis = 220)) +
                fadeIn(animationSpec = tween(durationMillis = 220)),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 180)) +
                fadeOut(animationSpec = tween(durationMillis = 120)),
        ) { content() }
    }
}

/**
 * Expressive empty-state emblem (M3E audit E-015): the icon sits inside a
 * MaterialShapes container. With `animated = true` the container slowly
 * morphs between two shapes. Intended only for balanced empty states —
 * never for errors or dense utility screens, where decoration competes
 * with recovery actions.
 *
 * Verified against the pinned material3 1.5.0-rc01 artifact:
 * `MaterialShapes` companion properties, `RoundedPolygon.toShape()` and
 * `Morph.toPath(progress, path)` are all @ExperimentalMaterial3ExpressiveApi;
 * `androidx.graphics.shapes.Morph` comes from graphics-shapes 1.0.1.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OcShapeEmblem(
    icon: Painter,
    modifier: Modifier = Modifier,
    animated: Boolean = false,
    size: Dp = 72.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (animated) {
            val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny) }
            val progress by rememberInfiniteTransition(label = "shape-emblem-morph").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "shape-emblem-progress",
            )
            val morphPath = remember { Path() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                // MaterialShapes polygons are normalized to the unit square;
                // Morph has no toShape(), so scale the generated path here.
                val path = morph.toPath(progress = progress, path = morphPath)
                scale(scaleX = this.size.width, scaleY = this.size.height, pivot = Offset.Zero) {
                    drawPath(path, containerColor)
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = MaterialShapes.Sunny.toShape(),
                color = containerColor,
            ) {}
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}

/**
 * Pulsing placeholder row for known-shape loading content (M3E audit E-017).
 * Stack [OcSkeletonRow]s where a list is expected instead of showing a bare
 * spinner. Cleared from the semantics tree: the surrounding screen keeps an
 * accessible loading label, so TalkBack is not fed meaningless nodes.
 */
@Composable
fun OcSkeletonRow(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    widthFraction: Float = 1f,
) {
    val pulse by rememberInfiniteTransition(label = "skeleton-pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-pulse-alpha",
    )
    Surface(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .graphicsLayer { alpha = pulse }
            .clearAndSetSemantics { },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {}
}

/**
 * App-wide expressive modal sheet (M3E audit E-018 follow-up).
 *
 * The audit recommendation named `FlexibleBottomSheet`; that API does not exist
 * in material3 1.5.0-rc01, the newest published version, so the expressive
 * treatment is centralized here on top of the available `ModalBottomSheet`:
 * the theme-driven 48dp `extraLarge` sheet radius from the shape family, the
 * shared drag handle and one app-wide sheet state that opens fully expanded,
 * because every current sheet is a full-content picker. Screens keep only
 * `onDismissRequest` and content, so a future expressive sheet API upgrade
 * touches a single place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcExpressiveSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        content = content,
    )
}

/** A short stack of skeleton rows; the last row is shorter to hint text flow. */
@Composable
fun OcSkeletonRows(
    rows: Int = 4,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 14.dp,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(rows) { index ->
            OcSkeletonRow(height = rowHeight, widthFraction = if (index % 3 == 2) 0.6f else 1f)
        }
    }
}
