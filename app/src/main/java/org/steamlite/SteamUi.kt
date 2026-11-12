package org.steamlite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

val Bg: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.surface
val Surface1: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.surfaceContainer
val Surface2: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.surfaceVariant
val Surface3: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.surfaceContainerHighest
val Line: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.dividerLine
val LineStrong: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.outline
val Text1: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.onBackground
val Text2: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.onSurfaceSecondary
val Text3: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary
val Accent: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.primary
val AccentText: Color @Composable get() = if (isSystemInDarkTheme()) MiuixTheme.colorScheme.onPrimaryVariant else Color(0xFF185DBE)
val OnAccent: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.onPrimary
val AccentSoft: Color @Composable @ReadOnlyComposable get() = Accent.copy(alpha = 0.13f)
val AccentLine: Color @Composable @ReadOnlyComposable get() = Accent.copy(alpha = 0.30f)
val Danger: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.error
val DangerText: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF9E97) else Color(0xFFB3261E)
val DangerSoft: Color @Composable @ReadOnlyComposable get() = Danger.copy(alpha = 0.10f)
val SheetScrim: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.windowDimming

val Money: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7AD99B) else Color(0xFF216E3C)
val MoneyText: Color @Composable get() = Money
val MoneySoft: Color @Composable get() = Money.copy(alpha = 0.12f)
val MoneyLine: Color @Composable get() = Money.copy(alpha = 0.26f)
val Warn: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE5BE72) else Color(0xFF865508)
val WarnText: Color @Composable get() = Warn
val WarnSoft: Color @Composable get() = Warn.copy(alpha = 0.10f)
val WarnLine: Color @Composable get() = Warn.copy(alpha = 0.20f)

@Composable
fun SteamTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    val dark = isSystemInDarkTheme()
    MiuixTheme(controller = controller) {
        val colors = MiuixTheme.colorScheme
        val styles = MiuixTheme.textStyles
        val indication = LocalIndication.current
        // Keep the remaining Material sheets, progress indicators and dialogs on the Miuix palette.
        val materialColors = (if (dark) darkColorScheme() else lightColorScheme()).copy(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            primaryContainer = colors.primaryContainer,
            onPrimaryContainer = colors.onPrimaryContainer,
            secondary = colors.secondary,
            onSecondary = colors.onSecondary,
            secondaryContainer = colors.secondaryContainer,
            onSecondaryContainer = colors.onSecondaryContainer,
            background = colors.background,
            onBackground = colors.onBackground,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.onSurfaceVariantSummary,
            surfaceTint = colors.primary,
            surfaceContainerLowest = colors.background,
            surfaceContainerLow = colors.surface,
            surfaceContainer = colors.surfaceContainer,
            surfaceContainerHigh = colors.surfaceContainerHigh,
            surfaceContainerHighest = colors.surfaceContainerHighest,
            inverseSurface = colors.onSurface,
            inverseOnSurface = colors.surface,
            inversePrimary = colors.primaryVariant,
            error = colors.error,
            onError = colors.onError,
            errorContainer = colors.errorContainer,
            onErrorContainer = colors.onErrorContainer,
            outline = colors.outline,
            outlineVariant = colors.dividerLine,
            scrim = colors.windowDimming,
        )
        val typography = Typography(
            headlineSmall = styles.headline2,
            titleLarge = styles.title3,
            titleMedium = styles.title4,
            bodyLarge = styles.main,
            bodyMedium = styles.body1,
            bodySmall = styles.body2,
            labelLarge = styles.button,
            labelMedium = styles.footnote1,
            labelSmall = styles.footnote2,
        )
        MaterialTheme(colorScheme = materialColors, typography = typography) {
            CompositionLocalProvider(LocalIndication provides indication, content = content)
        }
    }
}

// ---- navigation -----------------------------------------------------------
fun openSteamLink(context: Context, url: String) {
    if (!ChatContent.webUrl(url)) {
        Toast.makeText(context, "无法打开此链接", Toast.LENGTH_SHORT).show(); return
    }
    val target = Uri.parse(url)
    // Try the official Steam client first. Android package visibility does not affect startActivity.
    for (pkg in listOf("com.valvesoftware.android.steam.community", "com.valvesoftware.android.steam.friendsui")) {
        if (runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target).setPackage(pkg)); true }.getOrDefault(false)) return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("steam://openurl/$url"))) }.onSuccess { return }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target)) }
        .onFailure { Toast.makeText(context, "未找到可以打开链接的应用", Toast.LENGTH_SHORT).show() }
}
