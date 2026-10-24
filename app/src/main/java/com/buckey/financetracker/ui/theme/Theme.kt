package com.buckey.financetracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Buckey's own palette (user's choice, 2026-10-24): near-neutral greys with one
// deep green accent; a softer green on charcoal in dark mode. `primary` is also
// the credit color, so it must stay readable as text on `surface`.

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2F6B4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5EBDC),
    onPrimaryContainer = Color(0xFF0E2E1D),
    secondary = Color(0xFF56635A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E6E1),
    onSecondaryContainer = Color(0xFF1A1F1B),
    tertiary = Color(0xFF56635A),
    background = Color(0xFFF8F9F7),
    onBackground = Color(0xFF1A1C1A),
    surface = Color(0xFFF8F9F7),
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFE6E9E5),
    onSurfaceVariant = Color(0xFF5C625D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F1),
    surfaceContainer = Color(0xFFEDEFEC),
    surfaceContainerHigh = Color(0xFFE7E9E6),
    surfaceContainerHighest = Color(0xFFE1E3E0),
    outline = Color(0xFF8A908B),
    outlineVariant = Color(0xFFD9DDD8),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8DCFA7),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF1E4A33),
    onPrimaryContainer = Color(0xFFB8EBCA),
    secondary = Color(0xFFBCC5BD),
    onSecondary = Color(0xFF273029),
    secondaryContainer = Color(0xFF2D332E),
    onSecondaryContainer = Color(0xFFD9E1D9),
    tertiary = Color(0xFFBCC5BD),
    background = Color(0xFF131514),
    onBackground = Color(0xFFE2E3E0),
    surface = Color(0xFF131514),
    onSurface = Color(0xFFE2E3E0),
    surfaceVariant = Color(0xFF2A2E2B),
    onSurfaceVariant = Color(0xFFA9AFA9),
    surfaceContainerLowest = Color(0xFF0E100F),
    surfaceContainerLow = Color(0xFF1A1C1B),
    surfaceContainer = Color(0xFF1E211F),
    surfaceContainerHigh = Color(0xFF262927),
    surfaceContainerHighest = Color(0xFF303331),
    outline = Color(0xFF737973),
    outlineVariant = Color(0xFF3A3F3B),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF5A1F1C),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * App theme. Dynamic color is off by default: Buckey uses its own palette so the
 * look doesn't change with the wallpaper (user's choice, 2026-10-24). Every
 * color, text style and spacing value in a screen comes from here, [Typography]
 * and [Spacing] (CLAUDE.md, "UI rules").
 */
@Composable
fun FinanceTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
