package app.halo.vpn.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.halo.vpn.R
import app.halo.vpn.core.ThemeMode

/** Цвета — из макетов mob-1, mob-2 (тёмная) и mob-1-light, mob-2-light (светлая). */
@Immutable
data class HaloColors(
    val bg: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val outline: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val nav: Color,
    val warn: Color,
    val switchOff: Color,
    val hero: Color,
    val accessFrom: Color,
    val accessTo: Color,
    val onAccess: Color,
    val dark: Boolean,
)

val DarkHalo = HaloColors(
    bg = Color(0xFF121318), card = Color(0xFF1D1F27), text = Color(0xFFE8E9F0), muted = Color(0xFF9093A0),
    outline = Color(0xFF8A8D99), divider = Color(0xFF2D303B), accent = Color(0xFF8FA3FF), onAccent = Color(0xFF0E1330),
    container = Color(0xFF2A3366), nav = Color(0xFF191B23), warn = Color(0xFFFF9A5C), switchOff = Color(0xFF272A35),
    hero = Color(0xFF26305F), accessFrom = Color(0xFF4D6BFF), accessTo = Color(0xFF8AA0FF), onAccess = Color(0xFF0E0F13),
    dark = true,
)

val LightHalo = HaloColors(
    bg = Color(0xFFFBF9FC), card = Color(0xFFFFFFFF), text = Color(0xFF1A1B20), muted = Color(0xFF5F6270),
    outline = Color(0xFF757886), divider = Color(0xFFE1E2EA), accent = Color(0xFF3A5AF9), onAccent = Color(0xFFFFFFFF),
    container = Color(0xFFDFE4FF), nav = Color(0xFFF1F2FA), warn = Color(0xFFC2541A), switchOff = Color(0xFFECEEF6),
    hero = Color(0xFFDFE5FF), accessFrom = Color(0xFF3A5AF9), accessTo = Color(0xFF6F8BFF), onAccess = Color(0xFFFFFFFF),
    dark = false,
)

@OptIn(ExperimentalTextApi::class)
/** Шрифты макета: Roboto для текста, JetBrains Mono для цифр (пинг, скорость, гигабайты). */
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Roboto = FontFamily(listOf(300, 400, 500, 600, 700).map { variable(R.font.roboto, it) })
val Mono = FontFamily(listOf(400, 500, 700).map { variable(R.font.jetbrains_mono, it) })

val LocalHalo = staticCompositionLocalOf { DarkHalo }

@Composable
fun isDark(mode: ThemeMode) = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
}

@Composable
fun HaloTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val c = if (dark) DarkHalo else LightHalo
    val scheme = if (dark) {
        darkColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.bg, surface = c.card, onSurface = c.text,
            onBackground = c.text, outline = c.outline, surfaceContainerHigh = c.card, surfaceContainer = c.card)
    } else {
        lightColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.bg, surface = c.card, onSurface = c.text,
            onBackground = c.text, outline = c.outline, surfaceContainerHigh = c.card, surfaceContainer = c.card)
    }
    val base = TextStyle(fontFamily = Roboto, color = c.text)
    val typography = MaterialTheme.typography.let { t ->
        t.copy(
            bodyLarge = t.bodyLarge.merge(base), bodyMedium = t.bodyMedium.merge(base), bodySmall = t.bodySmall.merge(base),
            labelLarge = t.labelLarge.merge(base), titleMedium = t.titleMedium.merge(base), titleLarge = t.titleLarge.merge(base),
            headlineSmall = t.headlineSmall.merge(base),
        )
    }
    CompositionLocalProvider(LocalHalo provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
