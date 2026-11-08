package io.github.oustery.opencodephone.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Consistent radius family: precise 12dp corners for dense utility surfaces,
 * 20dp for cards, 32dp for hero containers, 48dp for full-bleed sheets.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(48.dp),
)

/** Meaningful message roles, kept separate from generic component radii. */
object AppMessageShapes {
    val composer = RoundedCornerShape(24.dp)
    val incoming = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    val outgoing = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
}
