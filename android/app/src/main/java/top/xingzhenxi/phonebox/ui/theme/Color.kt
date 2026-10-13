package top.xingzhenxi.phonebox.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 深色一套直接沿用网页端的 #191713 / #e8e2d6 / #e08a5c，保持两端观感一致
internal val DarkColors = darkColorScheme(
    primary = Color(0xFFE08A5C),
    onPrimary = Color(0xFF2A1408),
    primaryContainer = Color(0xFF4A2A18),
    onPrimaryContainer = Color(0xFFFFDCC9),
    secondary = Color(0xFFCFC3B0),
    onSecondary = Color(0xFF2B261E),
    secondaryContainer = Color(0xFF38332B),
    onSecondaryContainer = Color(0xFFEDE4D4),
    tertiary = Color(0xFFB9C7A6),
    onTertiary = Color(0xFF1F2A14),
    background = Color(0xFF191713),
    onBackground = Color(0xFFE8E2D6),
    surface = Color(0xFF191713),
    onSurface = Color(0xFFE8E2D6),
    surfaceVariant = Color(0xFF2C2924),
    onSurfaceVariant = Color(0xFFADA597),
    surfaceDim = Color(0xFF151310),
    surfaceBright = Color(0xFF3A362F),
    surfaceContainerLowest = Color(0xFF13110E),
    surfaceContainerLow = Color(0xFF1E1C18),
    surfaceContainer = Color(0xFF23201B),
    surfaceContainerHigh = Color(0xFF2A2721),
    surfaceContainerHighest = Color(0xFF312D27),
    outline = Color(0xFF6E675B),
    outlineVariant = Color(0xFF36322B),
    error = Color(0xFFF0907A),
    onError = Color(0xFF3A120A),
    errorContainer = Color(0xFF5A2318),
    onErrorContainer = Color(0xFFFFDAD2),
    inverseSurface = Color(0xFFE8E2D6),
    inverseOnSurface = Color(0xFF2B2822),
    inversePrimary = Color(0xFFA9532A),
)

// 浅色强调色压暗一档，保证白底上按钮文字对比度
internal val LightColors = lightColorScheme(
    primary = Color(0xFFA9532A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFADCCB),
    onPrimaryContainer = Color(0xFF3D1A08),
    secondary = Color(0xFF6A6052),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE5D8),
    onSecondaryContainer = Color(0xFF241F17),
    tertiary = Color(0xFF56653F),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F4EE),
    onBackground = Color(0xFF1F1C17),
    surface = Color(0xFFF7F4EE),
    onSurface = Color(0xFF1F1C17),
    surfaceVariant = Color(0xFFEAE4DA),
    onSurfaceVariant = Color(0xFF5E574C),
    surfaceDim = Color(0xFFE2DDD3),
    surfaceBright = Color(0xFFFCFAF6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2EEE7),
    surfaceContainer = Color(0xFFEDE8E0),
    surfaceContainerHigh = Color(0xFFE7E2D9),
    surfaceContainerHighest = Color(0xFFE1DBD1),
    outline = Color(0xFF8A8275),
    outlineVariant = Color(0xFFDDD6CA),
    error = Color(0xFFB3402B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE2DB),
    onErrorContainer = Color(0xFF410E05),
    inverseSurface = Color(0xFF34302A),
    inverseOnSurface = Color(0xFFF4EFE6),
    inversePrimary = Color(0xFFE08A5C),
)

/** 风险标签配色：底色 + 文字色，深浅各一套 */
data class RiskColors(val container: Color, val content: Color)

internal data class RiskPalette(
    val danger: RiskColors,
    val marketing: RiskColors,
    val business: RiskColors,
    val unknown: RiskColors,
)

internal val LightRisk = RiskPalette(
    danger = RiskColors(Color(0xFFFBE2DB), Color(0xFFB3402B)),
    marketing = RiskColors(Color(0xFFFCEBD5), Color(0xFF9A5A00)),
    business = RiskColors(Color(0xFFE1EAF7), Color(0xFF34609F)),
    unknown = RiskColors(Color(0xFFEAE6DF), Color(0xFF666057)),
)

internal val DarkRisk = RiskPalette(
    danger = RiskColors(Color(0xFF4A2019), Color(0xFFF0907A)),
    marketing = RiskColors(Color(0xFF45301A), Color(0xFFEDB065)),
    business = RiskColors(Color(0xFF1E2C42), Color(0xFF8FB2E8)),
    unknown = RiskColors(Color(0xFF2F2C27), Color(0xFFB0A99C)),
)
