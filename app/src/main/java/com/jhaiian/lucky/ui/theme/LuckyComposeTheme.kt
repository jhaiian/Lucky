package com.jhaiian.lucky.ui.theme

import com.jhaiian.lucky.customtheme.CUSTOM_THEME_REVISION_PREF
import com.jhaiian.lucky.customtheme.isCustomAccent
import com.jhaiian.lucky.customtheme.resolveCustomPalette
import com.jhaiian.lucky.customtheme.toLuckyColors
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager

data class LuckyColors(
    val background: Color,
    val onSurface: Color,
    val secondaryText: Color,
    val cardBackground: Color,
    val buttonBackground: Color,
    val surfaceVariant: Color,
    val popupBackground: Color,
    val primary: Color,
    val iconTint: Color,
    val divider: Color,
    val popupText: Color,
    val surface: Color,
    val buttonIconTint: Color,
    val popupStroke: Color,
    val popupCheck: Color,
    val onPrimary: Color,
    val colorError: Color,
    val colorErrorContainer: Color,
    val colorOnErrorContainer: Color,
    val buttonTextColor: Color,
    val addressBarColor: Color,
    val isLight: Boolean
)

val LocalLuckyColors = compositionLocalOf<LuckyColors> {
    error("LuckyComposeTheme not applied")
}

private fun LuckyColors(resolved: LuckyResolvedTheme) = LuckyColors(
    background = resolved.background,
    onSurface = resolved.onSurface,
    secondaryText = resolved.secondaryText,
    cardBackground = resolved.cardBackground,
    buttonBackground = resolved.buttonBackground,
    surfaceVariant = resolved.surfaceVariant,
    popupBackground = resolved.popupBackground,
    primary = resolved.primary,
    iconTint = resolved.iconTint,
    divider = resolved.divider,
    popupText = resolved.popupText,
    surface = resolved.surface,
    buttonIconTint = resolved.buttonIconTint,
    popupStroke = resolved.popupStroke,
    popupCheck = resolved.popupCheck,
    onPrimary = resolved.onPrimary,
    colorError = resolved.error,
    colorErrorContainer = resolved.errorContainer,
    colorOnErrorContainer = resolved.onErrorContainer,
    buttonTextColor = resolved.buttonTextColor,
    addressBarColor = resolved.addressBar,
    isLight = resolved.isLight
)

fun resolveLuckyColors(context: Context, isLight: Boolean, accent: String, intensity: String): LuckyColors {
    val palette = resolveCustomPalette(context, accent, isLight)
    if (palette != null) return palette.toLuckyColors(isLight)
    val safeAccent = if (isCustomAccent(accent)) "material_you" else accent
    return LuckyColors(resolveLuckyTheme(context, if (isLight) "light" else "dark", safeAccent, intensity))
}

@Composable
fun LuckyComposeTheme(theme: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isLightTheme = rememberIsLightTheme(theme)
    val resolvedTheme = if (isLightTheme) "light" else "dark"
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val accent = prefs.getString("accent_color", "material_you") ?: "material_you"
    val intensity = prefs.getString("surface_intensity", "soft_tint") ?: "soft_tint"

    val revision = prefs.getLong(CUSTOM_THEME_REVISION_PREF, 0L)

    val luckyColors = remember(resolvedTheme, accent, intensity, revision) {
        resolveLuckyColors(context, isLightTheme, accent, intensity)
    }

    val isLight = luckyColors.isLight
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    val colorScheme = base.copy(
        primary = luckyColors.primary,
        onPrimary = luckyColors.onPrimary,
        background = luckyColors.background,
        onBackground = luckyColors.onSurface,
        surface = luckyColors.cardBackground,
        onSurface = luckyColors.onSurface,
        surfaceVariant = luckyColors.surfaceVariant,
        onSurfaceVariant = luckyColors.secondaryText,
        error = luckyColors.colorError,
        errorContainer = luckyColors.colorErrorContainer,
        onErrorContainer = luckyColors.colorOnErrorContainer
    )

    CompositionLocalProvider(LocalLuckyColors provides luckyColors) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
