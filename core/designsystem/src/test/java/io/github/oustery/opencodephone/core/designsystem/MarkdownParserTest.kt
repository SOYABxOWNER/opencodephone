package io.github.oustery.opencodephone.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grammar coverage for the chat Markdown subset (pure JVM, no Compose). */
class MarkdownParserTest {

    // ------------------------------------------------------------ blocks

    @Test fun paragraphsJoinAdjacentLines() {
        val blocks = MarkdownParser.parseDocument("line one\nline two")
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdSpan.Text("line one\nline two")))), blocks)
    }

    @Test fun headingsCarryLevelAndStripTrailingHashes() {
        val blocks = MarkdownParser.parseDocument("# Title\n\n## Sub ##")
        assertEquals(
            listOf(
                MdBlock.Heading(1, listOf(MdSpan.Text("Title"))),
                MdBlock.Heading(2, listOf(MdSpan.Text("Sub"))),
            ),
            blocks,
        )
    }

    @Test fun fencedCodeKeepsLanguageAndRawBody() {
        val blocks = MarkdownParser.parseDocument("```kotlin\nval a = 1\n```")
        assertEquals(listOf(MdBlock.Code("kotlin", "val a = 1", true)), blocks)
    }

    @Test fun tildeFencesWork() {
        val blocks = MarkdownParser.parseDocument("~~~\ncode\n~~~")
        assertEquals(listOf(MdBlock.Code(null, "code", true)), blocks)
    }

    @Test fun unterminatedFenceDegradesToCodeForStreaming() {
        val blocks = MarkdownParser.parseDocument("```python\nprint('hi')")
        assertEquals(listOf(MdBlock.Code("python", "print('hi')", false)), blocks)
    }

    @Test fun horizontalRulesAndListItemsAreDisambiguated() {
        val blocks = MarkdownParser.parseDocument("---\n\n- item\n\n***")
        assertTrue(blocks[0] is MdBlock.Rule)
        assertTrue(blocks[1] is MdBlock.ListBlock)
        assertTrue(blocks[2] is MdBlock.Rule)
    }

    @Test fun quotesRecurseIntoBlocks() {
        val blocks = MarkdownParser.parseDocument("> line one\n> line two\n>\n> # Head")
        val quote = blocks.single() as MdBlock.Quote
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdSpan.Text("line one\nline two"))),
                MdBlock.Heading(1, listOf(MdSpan.Text("Head"))),
            ),
            quote.blocks,
        )
    }

    @Test fun listsCarryOrderNumbersAndNesting() {
        val blocks = MarkdownParser.parseDocument("- a\n  - b\n\n2. second\n3. third")
        val bullets = blocks[0] as MdBlock.ListBlock
        assertEquals(false, bullets.ordered)
        assertEquals(0, bullets.items[0].indent)
        assertEquals(1, bullets.items[1].indent)

        val ordered = blocks[1] as MdBlock.ListBlock
        assertEquals(true, ordered.ordered)
        assertEquals(2, ordered.items[0].number)
        assertEquals(3, ordered.items[1].number)
    }

    @Test fun indentedContinuationLinesJoinTheirItem() {
        val blocks = MarkdownParser.parseDocument("- first\n  wrapped\n- second")
        val list = blocks.single() as MdBlock.ListBlock
        assertEquals(
            listOf(
                MdListItem(0, null, listOf(MdSpan.Text("first\nwrapped"))),
                MdListItem(0, null, listOf(MdSpan.Text("second"))),
            ),
            list.items,
        )
    }

    @Test fun tablesSplitCellsAndRespectEscapedPipes() {
        val blocks = MarkdownParser.parseDocument(
            "| a\\|b | c |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |",
        )
        assertEquals(listOf(MdBlock.Table(listOf("a|b", "c"), listOf(listOf("1", "2"), listOf("3", "4")))), blocks)
    }

    @Test fun tablesWorkWithoutBoundaryPipes() {
        val blocks = MarkdownParser.parseDocument("A | B\n--- | ---\n1 | 2")
        assertEquals(listOf(MdBlock.Table(listOf("A", "B"), listOf(listOf("1", "2")))), blocks)
    }

    // ------------------------------------------------------------ inline

    @Test fun boldItalicStrikeAndCombinations() {
        assertEquals(listOf(MdSpan.Text("bold", bold = true)), MarkdownParser.parseInline("**bold**"))
        assertEquals(listOf(MdSpan.Text("it", italic = true)), MarkdownParser.parseInline("*it*"))
        assertEquals(listOf(MdSpan.Text("gone", strike = true)), MarkdownParser.parseInline("~~gone~~"))
        assertEquals(
            listOf(MdSpan.Text("bi", bold = true, italic = true)),
            MarkdownParser.parseInline("***bi***"),
        )
    }

    @Test fun emphasisFlankingRulesKeepPlainPunctuationLiteral() {
        assertEquals(listOf(MdSpan.Text("2 * 3 * 4")), MarkdownParser.parseInline("2 * 3 * 4"))
        assertEquals(listOf(MdSpan.Text("snake_case_here")), MarkdownParser.parseInline("snake_case_here"))
        assertEquals(listOf(MdSpan.Text("**abc")), MarkdownParser.parseInline("**abc"))
    }

    @Test fun codeSpansTakeRawContentAndStripPadding() {
        assertEquals(
            listOf(MdSpan.Text("a "), MdSpan.CodeSpan("code"), MdSpan.Text(" b")),
            MarkdownParser.parseInline("a `code` b"),
        )
        assertEquals(listOf(MdSpan.CodeSpan("x")), MarkdownParser.parseInline("` x `"))
        assertEquals(listOf(MdSpan.CodeSpan("a ` b")), MarkdownParser.parseInline("``a ` b``"))
    }

    @Test fun linksImagesAndAutolinks() {
        assertEquals(
            listOf(MdSpan.Link("label", "https://example.com")),
            MarkdownParser.parseInline("[label](https://example.com)"),
        )
        assertEquals(
            listOf(MdSpan.Link("alt", "https://example.com/i.png")),
            MarkdownParser.parseInline("![alt](https://example.com/i.png)"),
        )
        assertEquals(
            listOf(MdSpan.Link("https://example.com", "https://example.com")),
            MarkdownParser.parseInline("<https://example.com>"),
        )
    }

    @Test fun bareUrlsLinkUpToSentencePunctuation() {
        val spans = MarkdownParser.parseInline("see https://example.com/x.")
        assertEquals(
            listOf(
                MdSpan.Text("see "),
                MdSpan.Link("https://example.com/x", "https://example.com/x"),
                MdSpan.Text("."),
            ),
            spans,
        )
    }

    @Test fun escapesAndUnsafeSchemesStayLiteral() {
        assertEquals(listOf(MdSpan.Text("*not italic*")), MarkdownParser.parseInline("\\*not italic\\*"))
        assertEquals(
            listOf(MdSpan.Text("[x](javascript:alert(1))")),
            MarkdownParser.parseInline("[x](javascript:alert(1))"),
        )
    }

    @Test fun linksSurviveBoldWrappers() {
        assertEquals(
            listOf(MdSpan.Link("text", "https://e.com")),
            MarkdownParser.parseInline("**[text](https://e.com)**"),
        )
    }
}
