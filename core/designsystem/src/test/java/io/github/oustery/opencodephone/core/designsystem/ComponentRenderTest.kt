package io.github.oustery.opencodephone.core.designsystem

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native graphics render probes plus semantics, independent of app/network/Keystore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actionAndDisclosureAreAccessibleAtLargeFont() {
        var clicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                OpenCodePhoneTheme(darkTheme = true) {
                    Column(Modifier.width(320.dp)) {
                        OcActionChip("A very long model identifier that must not push other actions offscreen", "Choose model",
                            onClick = { clicks++ }, modifier = Modifier.testTag("action").fillMaxWidth())
                        OcDisclosure("Output", "Expanded", "Collapsed") { Text("tool result") }
                        OcInfoRow("Рабочая папка", "/data/user/0/io.github.oustery.opencodephone/files/workspace")
                    }
                }
            }
        }
        compose.onNodeWithTag("action").assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithText("tool result").assertDoesNotExist()
        compose.onNodeWithText("Output").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        compose.onNodeWithText("tool result").assertIsDisplayed()
        compose.onNodeWithText("Output").performClick()
        compose.onNodeWithText("tool result").assertDoesNotExist()
        save("large-type", compose.onRoot().captureToImage().asAndroidBitmap())
    }

    @Test fun lightSurfaceRenderMatchesBrandToken() = surfaceRender(false)
    @Test fun darkSurfaceRenderMatchesBrandToken() = surfaceRender(true)

    private fun surfaceRender(dark: Boolean) {
        val expected = if (dark) DarkColorScheme.surface.toArgb() else LightColorScheme.surface.toArgb()
        compose.setContent {
            OpenCodePhoneTheme(darkTheme = dark) {
                Surface(Modifier.size(96.dp).testTag("swatch"), color = MaterialTheme.colorScheme.surface) {}
            }
        }
        val bitmap = compose.onNodeWithTag("swatch").captureToImage().asAndroidBitmap()
        assertEquals(expected, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
        save(if (dark) "dark-surface" else "light-surface", bitmap)
    }

    @Test fun emptyStateActionIsRenderedAndInvoked() {
        var clicks = 0
        compose.setContent {
            OpenCodePhoneTheme {
                OcEmptyState("Offline", "Open Settings to connect", actionText = "Settings", onAction = { clicks++ })
            }
        }
        compose.onNodeWithText("Settings").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun toggleableRowTogglesFromTheWholeLabel() {
        var toggles = 0
        compose.setContent {
            OpenCodePhoneTheme {
                Column(Modifier.width(320.dp)) {
                    var consent by remember { mutableStateOf(false) }
                    OcToggleableRow(
                        checked = consent,
                        onCheckedChange = { toggles++; consent = it },
                        role = Role.Checkbox,
                    ) {
                        Checkbox(checked = consent, onCheckedChange = null)
                        Text("Sync provider keys")
                    }
                    var dynamic by remember { mutableStateOf(true) }
                    OcToggleableRow(
                        checked = dynamic,
                        onCheckedChange = { toggles += 10; dynamic = it },
                        role = Role.Switch,
                        modifier = Modifier.testTag("switch-row"),
                    ) {
                        Text("Dynamic color")
                        Switch(checked = dynamic, onCheckedChange = null)
                    }
                }
            }
        }
        // Tapping the label — not only the control — must toggle, and the row
        // must expose the control's role and toggleable state so TalkBack
        // announces the right behaviour (UI audit A4).
        compose.onNodeWithText("Sync provider keys")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        compose.onNodeWithText("Sync provider keys")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onNodeWithTag("switch-row")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performClick()
        compose.runOnIdle { assertEquals(11, toggles) }
    }

    @Test fun shapeEmblemRendersExpressiveContainer() {
        // Static emblem only: the animated morph variant uses an infinite
        // transition and would keep the test clock from idling.
        compose.setContent {
            OpenCodePhoneTheme(darkTheme = true) {
                OcShapeEmblem(
                    painterResource(R.drawable.ic_chevron_down),
                    modifier = Modifier.testTag("emblem"),
                )
            }
        }
        val bitmap = compose.onNodeWithTag("emblem").captureToImage().asAndroidBitmap()
        // The emblem must paint a shape container AND a distinct icon tint;
        // a uniform bitmap would mean nothing expressive rendered.
        val colors = HashSet<Int>()
        for (x in bitmap.width / 6 until bitmap.width * 5 / 6 step 4) {
            for (y in bitmap.height / 6 until bitmap.height * 5 / 6 step 4) {
                colors.add(bitmap.getPixel(x, y))
                if (colors.size >= 2) break
            }
            if (colors.size >= 2) break
        }
        assertTrue("emblem rendered uniform; expected shape container + icon tint", colors.size >= 2)
        save("shape-emblem", bitmap)
    }

    private fun save(name: String, bitmap: Bitmap) {
        val output = File("build/reports/ui-renders/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
