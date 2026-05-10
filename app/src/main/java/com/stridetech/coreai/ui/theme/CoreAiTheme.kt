package com.stridetech.coreai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Design tokens adapted from DESIGN.md (Cal.com professional system)
val BrandAccent = Color(0xFF3B82F6)
val BrandAccentLight = Color(0xFF60A5FA)
val PrimaryContainer = Color(0xFFDBEAFE)
val OnPrimaryContainer = Color(0xFF1E3A5F)
val InkDark = Color(0xFF111111)
val BodyText = Color(0xFF374151)
val MutedText = Color(0xFF6B7280)
val Canvas = Color(0xFFFFFFFF)
val SurfaceCard = Color(0xFFF5F5F5)
val Hairline = Color(0xFFE5E7EB)
val SurfaceDark = Color(0xFF0F0F0F)
val SurfaceDarkElevated = Color(0xFF1A1A1A)
val SurfaceDarkCard = Color(0xFF252525)
val OnDark = Color(0xFFF1F1F1)
val OnDarkSoft = Color(0xFFA1A1AA)
val ErrorRed = Color(0xFFEF4444)
val ErrorContainerLight = Color(0xFFFEE2E2)
val OnErrorContainerLight = Color(0xFF7F1D1D)
val ErrorContainerDark = Color(0xFF7F1D1D)
val OnErrorContainerDark = Color(0xFFFECACA)
val UserBubbleDark = Color(0xFF1D4ED8)
val SecondaryIndigo = Color(0xFF6366F1)
val SecondaryContainer = Color(0xFFE0E7FF)
val OnSecondaryContainer = Color(0xFF312E81)
val SecondaryIndigoDark = Color(0xFF818CF8)
val SecondaryContainerDark = Color(0xFF2D2F6B)
val OnSecondaryContainerDark = Color(0xFFE0E7FF)

private val LightColorScheme = lightColorScheme(
    primary = BrandAccent,
    onPrimary = Canvas,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = SecondaryIndigo,
    onSecondary = Canvas,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    background = Canvas,
    onBackground = InkDark,
    surface = Canvas,
    onSurface = BodyText,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = MutedText,
    outline = Hairline,
    outlineVariant = Color(0xFFF3F4F6),
    error = ErrorRed,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandAccentLight,
    onPrimary = OnPrimaryContainer,
    primaryContainer = UserBubbleDark,
    onPrimaryContainer = PrimaryContainer,
    secondary = SecondaryIndigoDark,
    onSecondary = Color(0xFF1E1E4A),
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    background = SurfaceDark,
    onBackground = OnDark,
    surface = SurfaceDarkElevated,
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = SurfaceDarkCard,
    onSurfaceVariant = OnDarkSoft,
    outline = Color(0xFF374151),
    outlineVariant = Color(0xFF1F2937),
    error = Color(0xFFF87171),
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

@Composable
fun CoreAiTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
