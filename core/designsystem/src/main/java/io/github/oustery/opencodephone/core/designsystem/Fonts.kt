package io.github.oustery.opencodephone.core.designsystem

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Bundled brand fonts, replacing the system font stack.
 *
 * Both families ship under the SIL Open Font License 1.1 (full license texts
 * in `assets/licenses/`):
 *  - Manrope: a friendly geometric sans with rounded terminals for all UI
 *    text — distinctive but highly readable at small sizes;
 *  - JetBrains Mono: a code-first mono with tall x-height and unambiguous
 *    glyphs (0/O, 1/l/I) for terminal output, diffs and file paths.
 *
 * Static instances (400/500/600/700) are bundled instead of the variable
 * font so every renderer — including older Text APIs — gets a real weight.
 */
object AppFonts {
    val Sans: FontFamily = FontFamily(
        Font(R.font.manrope_regular, FontWeight.Normal),
        Font(R.font.manrope_medium, FontWeight.Medium),
        Font(R.font.manrope_semibold, FontWeight.SemiBold),
        Font(R.font.manrope_bold, FontWeight.Bold),
    )

    val Code: FontFamily = FontFamily(
        Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
        Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
        Font(R.font.jetbrainsmono_semibold, FontWeight.SemiBold),
        Font(R.font.jetbrainsmono_bold, FontWeight.Bold),
    )
}
