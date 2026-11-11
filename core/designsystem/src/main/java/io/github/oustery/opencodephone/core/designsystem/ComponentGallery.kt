package io.github.oustery.opencodephone.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Deterministic, offline catalogue: preview the same components used by the app. */
@Composable
fun ComponentGallery() {
    Surface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OcSectionHeader("OpenCodePhone")
            OcGroupCard(
                title = "Server & connection",
                summary = "opencode serve :4096",
                icon = painterResource(R.drawable.ic_chevron_down),
                onClick = {},
            )
            OcTwoPane(
                showDetail = true,
                listPane = { OcSectionHeader("List pane") },
                detailPane = { OcSectionHeader("Detail pane") },
            )
            OcActionChip("glm-5.3-flash-free", "Choose model", onClick = {})
            OcStatusChip("Connected", tone = OcChipTone.PRIMARY)
            OcInfoRow("Рабочая папка", "/data/user/0/io.github.oustery.opencodephone/files/workspace")
            OcDisclosure("Вывод инструмента", "Развёрнуто", "Свёрнуто") {
                Text("git status\nWorking tree clean", style = CodeTypography.output)
            }
            OcToolCard(
                tool = "bash",
                statusLabel = "Выполняется",
                statusTone = OcChipTone.TERTIARY,
                icon = painterResource(R.drawable.ic_copy),
                expandedDescription = "Развёрнуто",
                collapsedDescription = "Свёрнуто",
                title = "git status --short --branch",
                content = { Text("## main\n?? build/", style = CodeTypography.output) },
            )
            MarkdownText(
                markdown = "## Chat markdown\n\nText with **bold**, `inline code` and a [link](https://example.com).\n\n```kotlin\nval ready = true\n```",
                codeCopyLabel = "Copy",
            )
            Text("Редактор / Editor", style = CodeTypography.editor)
        }
    }
}

@Preview(name = "Light / 320dp", widthDp = 320, showBackground = true)
@Composable
private fun GalleryLight() = OpenCodePhoneTheme(darkTheme = false) { ComponentGallery() }

@Preview(name = "Dark / large type", widthDp = 320, fontScale = 2f, showBackground = true)
@Composable
private fun GalleryDarkLarge() = OpenCodePhoneTheme(darkTheme = true) { ComponentGallery() }

@Preview(name = "Expanded", widthDp = 840, showBackground = true)
@Composable
private fun GalleryExpanded() = OpenCodePhoneTheme(darkTheme = false) { ComponentGallery() }
