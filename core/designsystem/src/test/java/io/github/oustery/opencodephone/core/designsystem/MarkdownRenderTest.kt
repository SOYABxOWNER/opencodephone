package io.github.oustery.opencodephone.core.designsystem

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Compose render probes for the chat Markdown renderer, independent of app code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blocksRenderWithHeadingSemanticsAndLinks() {
        compose.setContent {
            OpenCodePhoneTheme(darkTheme = true) {
                MarkdownText(
                    markdown = "# Plan\n\nHello **world** and `code`.\n\n- item one\n- item two\n\n" +
                        "> quoted line\n\n| A | B |\n|---|---|\n| 1 | 2 |",
                    codeCopyLabel = "Copy code",
                    modifier = Modifier.testTag("markdown"),
                )
            }
        }
        compose.onNodeWithTag("markdown").assertIsDisplayed()
        compose.onNodeWithText("Plan")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        // Emphasis and code spans render inside the paragraph's single text node.
        compose.onNodeWithText("Hello world and code.").assertIsDisplayed()
        compose.onNodeWithText("item one").assertIsDisplayed()
        compose.onNodeWithText("item two").assertIsDisplayed()
        compose.onNodeWithText("quoted line").assertIsDisplayed()
        compose.onNodeWithText("A").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        save("markdown-blocks", compose.onNodeWithTag("markdown").captureToImage().asAndroidBitmap())
    }

    @Test fun codeBlockCopyReceivesTheRawText() {
        val clipboard = FakeClipboard()
        compose.setContent {
            OpenCodePhoneTheme(darkTheme = true) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    MarkdownText(
                        markdown = "```kotlin\nval a = 1\n```",
                        codeCopyLabel = "Copy code",
                        modifier = Modifier.testTag("markdown"),
                    )
                }
            }
        }
        compose.onNodeWithText("kotlin").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.runOnIdle { assertEquals("val a = 1", clipboard.last?.text) }
    }

    @Test fun streamingUnterminatedCodeBlockRendersImmediately() {
        compose.setContent {
            OpenCodePhoneTheme {
                MarkdownText(
                    markdown = "Text before\n\n```python\nprint('hi')",
                    codeCopyLabel = "Copy code",
                )
            }
        }
        compose.onNodeWithText("Text before").assertIsDisplayed()
        compose.onNodeWithText("print('hi')").assertIsDisplayed()
    }

    private class FakeClipboard : ClipboardManager {
        var last: AnnotatedString? = null
        override fun setText(annotatedString: AnnotatedString) {
            last = annotatedString
        }

        override fun getText(): AnnotatedString? = last
    }

    private fun save(name: String, bitmap: Bitmap) {
        val output = File("build/reports/ui-renders/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
