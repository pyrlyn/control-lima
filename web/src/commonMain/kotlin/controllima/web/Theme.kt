package controllima.web

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import controllima.web.resources.Res
import controllima.web.resources.ibm_plex_mono_bold
import controllima.web.resources.ibm_plex_mono_medium
import controllima.web.resources.ibm_plex_mono_regular
import org.jetbrains.compose.resources.Font

/**
 * Colour tokens of the Figma design (Pyrlyn UI kit, Desktop · Color). Translucent fills are kept
 * translucent so they read the same on any surface they sit on.
 */
@Immutable
class LimaColors(
    val dark: Boolean,
    val window: Color,
    val sidebar: Color,
    val card: Color,
    val elevated: Color,
    val fillSubtle: Color,
    val fill: Color,
    val fillMedium: Color,
    val fillStrong: Color,
    val hairline: Color,
    val borderStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textGhost: Color,
    val accent: Color,
    val accentInk: Color,
    val onAccent: Color,
    val selection: Color,
    val ok: Color,
    val warning: Color,
    val error: Color,
    val code: Color,
)

private val Dark = run {
    val window = Color(0xFF16171C)
    LimaColors(
        dark = true,
        window = window,
        sidebar = Color(0x0FFFFFFF).compositeOver(window),
        card = Color(0x0DFFFFFF).compositeOver(window),
        elevated = Color(0xFF262B38),
        fillSubtle = Color(0x0DFFFFFF),
        fill = Color(0x14FFFFFF),
        fillMedium = Color(0x1FFFFFFF),
        fillStrong = Color(0x24FFFFFF),
        hairline = Color(0x14FFFFFF),
        borderStrong = Color(0x29FFFFFF),
        text = Color(0xFFEEF2F8),
        textSecondary = Color(0xFFA3ADBF),
        textGhost = Color(0xFF8A8F99),
        accent = Color(0xFF0873E0),
        accentInk = Color(0xFF6CB6FF),
        onAccent = Color.White,
        selection = Color(0x3D0A84FF),
        ok = Color(0xFF4FD98F),
        warning = Color(0xFFF2C14E),
        error = Color(0xFFFF8E9A),
        code = Color(0xFFDFDFE0),
    )
}

private val Light = run {
    val window = Color.White
    LimaColors(
        dark = false,
        window = window,
        sidebar = Color(0xFFF4F5F7),
        card = Color(0x08000000).compositeOver(window),
        elevated = Color.White,
        fillSubtle = Color(0x08000000),
        fill = Color(0x0D000000),
        fillMedium = Color(0x14000000),
        fillStrong = Color(0x1C000000),
        hairline = Color(0x14000000),
        borderStrong = Color(0x2E000000),
        text = Color(0xFF1D2129),
        textSecondary = Color(0xFF5C6577),
        textGhost = Color(0xFF8F959F),
        accent = Color(0xFF0873E0),
        accentInk = Color(0xFF0A64C2),
        onAccent = Color.White,
        selection = Color(0x33007AFF),
        ok = Color(0xFF1F9D5C),
        warning = Color(0xFFB07800),
        error = Color(0xFFD92D42),
        code = Color(0xFF24292F),
    )
}

private val LocalLimaColors = staticCompositionLocalOf { Dark }

object Lima {
    val colors: LimaColors
        @Composable @ReadOnlyComposable get() = LocalLimaColors.current

    val radiusSm = 6.dp
    val radiusMd = 8.dp
    val radiusLg = 14.dp
}

@Composable
private fun plexMono(): FontFamily = FontFamily(
    Font(Res.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(Res.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(Res.font.ibm_plex_mono_bold, FontWeight.Bold),
)

/** The design's text styles mapped onto Material's slots, so Material components use them too. */
private fun typography(font: FontFamily): Typography {
    fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) =
        TextStyle(fontFamily = font, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.em)
    val body = style(13, 18)
    return Typography(
        displayLarge = style(28, 34, FontWeight.Bold),
        displayMedium = style(26, 32, FontWeight.Bold),
        displaySmall = style(24, 30, FontWeight.Bold),
        headlineLarge = style(24, 30, FontWeight.Bold, -0.02),
        headlineMedium = style(22, 28, FontWeight.Bold, -0.02),
        headlineSmall = style(22, 28, FontWeight.Bold, -0.02),
        titleLarge = style(17, 22, FontWeight.Bold),
        titleMedium = style(14, 20, FontWeight.Bold),
        titleSmall = style(13, 18, FontWeight.Bold),
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = style(12, 16),
        labelLarge = style(12, 16, FontWeight.Bold),
        labelMedium = style(11, 14, FontWeight.Bold, 0.05),
        labelSmall = style(11, 14),
    )
}

@Composable
fun LimaTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val font = plexMono()
    val type = remember(font) { typography(font) }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val errorBg = c.error.copy(alpha = 0.16f).compositeOver(c.window)
    val scheme = base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.selection.compositeOver(c.window),
        onPrimaryContainer = c.accentInk,
        secondary = c.textSecondary,
        onSecondary = c.window,
        secondaryContainer = c.fillMedium.compositeOver(c.window),
        onSecondaryContainer = c.text,
        tertiary = c.accentInk,
        background = c.window,
        onBackground = c.text,
        surface = c.window,
        onSurface = c.text,
        surfaceVariant = c.fill.compositeOver(c.window),
        onSurfaceVariant = c.textSecondary,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = c.window,
        surfaceContainerLow = c.card,
        surfaceContainer = c.card,
        surfaceContainerHigh = c.elevated,
        surfaceContainerHighest = c.card,
        inverseSurface = c.text,
        inverseOnSurface = c.window,
        inversePrimary = c.accentInk,
        outline = c.borderStrong.compositeOver(c.window),
        outlineVariant = c.hairline.compositeOver(c.window),
        error = c.error,
        onError = c.window,
        errorContainer = errorBg,
        onErrorContainer = c.error,
        scrim = Color(0x99000000),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(Lima.radiusSm),
        small = RoundedCornerShape(Lima.radiusMd),
        medium = RoundedCornerShape(Lima.radiusMd),
        large = RoundedCornerShape(Lima.radiusLg),
        extraLarge = RoundedCornerShape(Lima.radiusLg),
    )
    CompositionLocalProvider(LocalLimaColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
    }
}
