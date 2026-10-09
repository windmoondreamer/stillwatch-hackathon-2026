package dev.stillwatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class StatusTone(val ink: Color, val background: Color)
data class StatusColors(val danger: StatusTone, val success: StatusTone, val warning: StatusTone)
private val lightStatuses = StatusColors(
    StatusTone(Color(0xFFA52A32), Color(0xFFFDF3F2)),
    StatusTone(Color(0xFF20634E), Color(0xFFF0F7F3)),
    StatusTone(Color(0xFF895119), Color(0xFFFAF5EC)),
)
private val darkStatuses = StatusColors(
    StatusTone(Color(0xFFFFB3B7), Color(0xFF42262A)),
    StatusTone(Color(0xFF9ED8BF), Color(0xFF1D352C)),
    StatusTone(Color(0xFFEAC28F), Color(0xFF3B3022)),
)
val LocalStatusColors = staticCompositionLocalOf { lightStatuses }
/** Set false through StillWatchTheme when the host already consumes system and IME insets. */
val LocalHandleSystemInsets = staticCompositionLocalOf { true }
private val light = lightColorScheme(
    primary = Color(0xFF304E55), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EEEE), onPrimaryContainer = Color(0xFF304E55),
    secondary = Color(0xFF59676D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EEEE), onSecondaryContainer = Color(0xFF202A2E),
    background = Color(0xFFF4F5F5), onBackground = Color(0xFF202A2E),
    surface = Color.White, onSurface = Color(0xFF202A2E),
    surfaceVariant = Color(0xFFF5F7F7), onSurfaceVariant = Color(0xFF59676D),
    surfaceContainer = Color(0xFFF5F7F7), surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFE8EEEE),
    outline = Color(0xFF647279), outlineVariant = Color(0xFFE1E6E6),
    error = Color(0xFFA52A32), onError = Color.White,
    errorContainer = Color(0xFFFDF3F2), onErrorContainer = Color(0xFFA52A32),
)
private val dark = darkColorScheme(
    primary = Color(0xFFACCED3), onPrimary = Color(0xFF17343B),
    primaryContainer = Color(0xFF304E55), onPrimaryContainer = Color(0xFFE8EEEE),
    secondary = Color(0xFFB6C4C8), onSecondary = Color(0xFF202A2E),
    secondaryContainer = Color(0xFF334349), onSecondaryContainer = Color(0xFFE6ECEE),
    background = Color(0xFF11191C), onBackground = Color(0xFFE6ECEE),
    surface = Color(0xFF192226), onSurface = Color(0xFFE6ECEE),
    surfaceVariant = Color(0xFF202C31), onSurfaceVariant = Color(0xFFB6C4C8),
    surfaceContainer = Color(0xFF202C31), surfaceContainerLow = Color(0xFF192226),
    surfaceContainerHigh = Color(0xFF2A383E),
    outline = Color(0xFF8D9EA5), outlineVariant = Color(0xFF3D4C52),
    error = Color(0xFFFFB3B7), onError = Color(0xFF64171D),
    errorContainer = Color(0xFF42262A), onErrorContainer = Color(0xFFFFB3B7),
)

val StillWatchTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)
object Spacing {
    val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 24.dp; val xxl = 32.dp
}

/** Host chooses darkTheme; no Activity, window, permissions, or dynamic-color side effects. */
@Composable
fun StillWatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    handleSystemInsets: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) darkStatuses else lightStatuses,
        LocalHandleSystemInsets provides handleSystemInsets,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) dark else light,
            typography = StillWatchTypography,
            shapes = Shapes(small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(16.dp)),
            content = content,
        )
    }
}
