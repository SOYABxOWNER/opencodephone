package io.github.oustery.opencodephone.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand palette: deep teal primary with amber tertiary accents on warm-neutral
// surfaces. Light and dark schemes are designed independently (tonal families,
// not inversions) — see docs/adr/ADR-004.

// Light scheme -------------------------------------------------------------
private val LightPrimary = Color(0xFF00695E)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFF9CF1E3)
private val LightOnPrimaryContainer = Color(0xFF00201B)
private val LightSecondary = Color(0xFF48635D)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFCAE8E1)
private val LightOnSecondaryContainer = Color(0xFF06201C)
private val LightTertiary = Color(0xFF8D5106)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFFFDCBC)
private val LightOnTertiaryContainer = Color(0xFF2E1500)
private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)
private val LightSurface = Color(0xFFF4FBF8)
private val LightOnSurface = Color(0xFF151D1B)
private val LightSurfaceVariant = Color(0xFFDBE4E1)
private val LightOnSurfaceVariant = Color(0xFF3F4946)
private val LightOutline = Color(0xFF6F7976)
private val LightOutlineVariant = Color(0xFFBFC9C6)

// Derived roles (M3 tonal ladder). Without these, unset ColorScheme slots
// fall back to the baseline (neutral-purple) palette and sheet scrims /
// inverse surfaces render off-brand — see M3E audit E-001.
private val LightSurfaceDim = Color(0xFFD5E0DC)
private val LightSurfaceBright = Color(0xFFFBFEFD)
private val LightInverseSurface = Color(0xFF2A3431)
private val LightInverseOnSurface = Color(0xFFECF4F1)
private val LightInversePrimary = Color(0xFF80D5C8)

private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFEEF5F2)
private val LightSurfaceContainer = Color(0xFFE8EFEC)
private val LightSurfaceContainerHigh = Color(0xFFE2EAE6)
private val LightSurfaceContainerHighest = Color(0xFFDCE4E1)

// Dark scheme --------------------------------------------------------------
private val DarkPrimary = Color(0xFF80D5C8)
private val DarkOnPrimary = Color(0xFF003731)
private val DarkPrimaryContainer = Color(0xFF004F47)
private val DarkOnPrimaryContainer = Color(0xFF9CF1E3)
private val DarkSecondary = Color(0xFFAECCC5)
private val DarkOnSecondary = Color(0xFF16352F)
private val DarkSecondaryContainer = Color(0xFF304B45)
private val DarkOnSecondaryContainer = Color(0xFFCAE8E1)
private val DarkTertiary = Color(0xFFFFB868)
private val DarkOnTertiary = Color(0xFF4B2800)
private val DarkTertiaryContainer = Color(0xFF6B3C00)
private val DarkOnTertiaryContainer = Color(0xFFFFDCBC)
private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)
private val DarkSurface = Color(0xFF0D1513)
private val DarkOnSurface = Color(0xFFDCE4E1)
private val DarkSurfaceVariant = Color(0xFF3F4946)
private val DarkOnSurfaceVariant = Color(0xFFBFC9C5)
private val DarkOutline = Color(0xFF89938F)
private val DarkOutlineVariant = Color(0xFF3F4946)

// Derived roles — see the light-scheme comment above (M3E audit E-001).
private val DarkSurfaceDim = Color(0xFF0B1311)
private val DarkSurfaceBright = Color(0xFF3A4441)
private val DarkInverseSurface = Color(0xFFDCE4E1)
private val DarkInverseOnSurface = Color(0xFF151D1B)
private val DarkInversePrimary = Color(0xFF00695E)

private val DarkSurfaceContainerLowest = Color(0xFF08100E)
private val DarkSurfaceContainerLow = Color(0xFF151D1B)
private val DarkSurfaceContainer = Color(0xFF192220)
private val DarkSurfaceContainerHigh = Color(0xFF232C2A)
private val DarkSurfaceContainerHighest = Color(0xFF2E3735)

// Fixed roles (UI audit C6): M3 container tones that stay constant across
// light and dark, derived from the brand families — Fixed = the light-scheme
// container step, Dim = the tone-80 sibling (the dark-scheme primary),
// on…Variant = the tone-30 step (the light-scheme primary). Unset slots fall
// back to the baseline purple palette, so future M3 components using fixed
// roles would render off-brand without these.
private val PrimaryFixed = Color(0xFF9CF1E3)
private val OnPrimaryFixed = Color(0xFF00201B)
private val PrimaryFixedDim = Color(0xFF80D5C8)
private val OnPrimaryFixedVariant = Color(0xFF00695E)
private val SecondaryFixed = Color(0xFFCAE8E1)
private val OnSecondaryFixed = Color(0xFF06201C)
private val SecondaryFixedDim = Color(0xFFAECCC5)
private val OnSecondaryFixedVariant = Color(0xFF48635D)
private val TertiaryFixed = Color(0xFFFFDCBC)
private val OnTertiaryFixed = Color(0xFF2E1500)
private val TertiaryFixedDim = Color(0xFFFFB868)
private val OnTertiaryFixedVariant = Color(0xFF8D5106)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightSurface,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceTint = LightPrimary,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    primaryFixed = PrimaryFixed,
    onPrimaryFixed = OnPrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    onSecondaryFixed = OnSecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    onTertiaryFixed = OnTertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkSurface,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceTint = DarkPrimary,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    inversePrimary = DarkInversePrimary,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    // Fixed roles do not shift between light and dark — that is the point
    // of the "fixed" family (UI audit C6).
    primaryFixed = PrimaryFixed,
    onPrimaryFixed = OnPrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    onSecondaryFixed = OnSecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    onTertiaryFixed = OnTertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)
