package io.github.oustery.opencodephone.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared style knobs of the chat renderer, so every caller stays in sync.
 */
object MarkdownDefaults {
    /**
     * Chat body: M3 `bodyLarge` with a tightened 22sp line height. Agent
     * answers are long, and the stock 16/24 rhythm reads airy inside a
     * bubble — 16/22 keeps the glyph size and accessibility of bodyLarge
     * while fitting roughly a ninth more lines per screen.
     */
    val chatBody: TextStyle
        @Composable get() = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp)
}

/**
 * Material 3 Expressive rendering of the [MarkdownParser] subset, tuned for
 * agent messages inside a chat bubble. Typography, colors, shapes and the
 * mono code face all come from the theme, so light/dark and dynamic color
 * follow the app for free (ADR-004).
 *
 * [codeCopyLabel] is the localized content description of the per-block copy
 * action; the renderer itself stays string-free (strings live in `app`).
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MarkdownDefaults.chatBody,
    codeCopyLabel: String,
) {
    // The parser runs on every streamed token; remembering by input keeps the
    // cost linear in document size instead of in recompositions.
    val blocks = remember(markdown) { MarkdownParser.parseDocument(markdown) }
    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MdBlocks(blocks, style, codeCopyLabel)
        }
    }
}

@Composable
private fun MdBlocks(
    blocks: List<MdBlock>,
    style: TextStyle,
    copyLabel: String,
    color: Color = Color.Unspecified,
) {
    blocks.forEach { block ->
        when (block) {
            is MdBlock.Paragraph -> MdSpansText(block.spans, style, color = color)
            is MdBlock.Heading -> MdHeading(block, color = color)
            is MdBlock.Code -> MdCodeBlock(block, copyLabel)
            is MdBlock.Quote -> MdQuote(block, style, copyLabel)
            is MdBlock.ListBlock -> MdList(block, style, color)
            is MdBlock.Table -> MdTable(block)
            MdBlock.Rule -> HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

/** Headings map onto the existing type ladder; TalkBack reads them as headers. */
@Composable
private fun MdHeading(block: MdBlock.Heading, color: Color) {
    val headingStyle = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        3 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.labelLarge
    }
    Text(
        text = annotate(block.spans, headingStyle, color),
        style = headingStyle,
        color = color,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun MdSpansText(
    spans: List<MdSpan>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(
        text = annotate(spans, style, color),
        style = style,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun annotate(spans: List<MdSpan>, style: TextStyle, color: Color): AnnotatedString {
    val linkStyle = style.toSpanStyle().copy(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    // Inline code carries its own opaque background, so it keeps readable
    // content even inside quieter contexts (quotes).
    val codeContent = if (color.isSpecified) color else MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        spans.forEach { span ->
            when (span) {
                is MdSpan.Text -> {
                    var spanStyle = style.toSpanStyle()
                    if (span.bold) spanStyle = spanStyle.copy(fontWeight = FontWeight.SemiBold)
                    if (span.italic) spanStyle = spanStyle.copy(fontStyle = FontStyle.Italic)
                    if (span.strike) spanStyle = spanStyle.copy(textDecoration = TextDecoration.LineThrough)
                    withStyle(spanStyle) { append(span.text) }
                }

                is MdSpan.CodeSpan -> withStyle(
                    SpanStyle(fontFamily = AppFonts.Code, background = codeBackground, color = codeContent),
                ) { append(span.code) }

                // Text() turns LinkAnnotation.Url into a real link: click
                // handling and TalkBack semantics come from the framework.
                is MdSpan.Link -> withLink(LinkAnnotation.Url(span.url, TextLinkStyles(linkStyle))) {
                    append(span.label)
                }
            }
        }
    }
}

/**
 * Fenced code: tonal container with the language tag and a copy action in the
 * header, mono text scrollable in both axes. A height cap keeps a large file
 * dump from swallowing the timeline; the content stays scrollable and the
 * copy button always receives the raw text.
 */
@Composable
private fun MdCodeBlock(code: MdBlock.Code, copyLabel: String) {
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (code.language != null) {
                    Text(
                        text = code.language,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = AppFonts.Code,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                // Standard control bounds keep the 48dp touch target; compact
                // look comes from the small icon, never from a shrunk button.
                IconButton(onClick = { clipboard.setText(AnnotatedString(code.code)) }) {
                    Icon(
                        painterResource(R.drawable.ic_copy),
                        contentDescription = copyLabel,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // The MarkdownText root already provides one SelectionContainer
            // for the whole message; no nested containers here.
            Box(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = code.code,
                    style = CodeTypography.output,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** Primary-tinted leading bar with the quoted content re-rendered as blocks. */
@Composable
private fun MdQuote(quote: MdBlock.Quote, style: TextStyle, copyLabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MdBlocks(
                quote.blocks,
                style,
                copyLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Hanging indent: the marker column is fixed, wrapped lines stay aligned. */
@Composable
private fun MdList(list: MdBlock.ListBlock, style: TextStyle, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val marker = if (list.ordered && item.number != null) "${item.number}." else "•"
                Text(
                    text = marker,
                    style = style,
                    color = color,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(26.dp + 14.dp * item.indent),
                )
                MdSpansText(
                    item.spans,
                    style,
                    modifier = Modifier.weight(1f, fill = false),
                    color = color,
                )
            }
        }
    }
}

/** Equal-width columns inside one tonal container; grid lines are dividers. */
@Composable
private fun MdTable(table: MdBlock.Table) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                table.header.forEach { cell ->
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            table.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    row.forEach { cell ->
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
