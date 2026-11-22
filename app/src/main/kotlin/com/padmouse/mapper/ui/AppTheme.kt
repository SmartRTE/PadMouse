package com.padmouse.mapper.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 主题模式：SYSTEM 跟随系统 / LIGHT 浅色 / DARK 深色
 */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("SYSTEM", "跟随系统"),
    LIGHT("LIGHT", "浅色"),
    DARK("DARK", "深色");

    companion object {
        fun of(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * 语义化配色。所有界面只从这里取色，不再写死深色值，
 * 这样浅色/深色两套都能保证正文对比度达标。
 */
@Immutable
data class PadColors(
    val bg: Color,
    val panel: Color,
    val panelAlt: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val accent: Color,
    val good: Color,
    val bad: Color,
    val onAccent: Color
)

/** 浅色：白底 + 深灰正文，正文对比度 > 12:1，次要文字 > 5:1 */
val LightPadColors = PadColors(
    bg = Color(0xFFF2F5F9),
    panel = Color(0xFFFFFFFF),
    panelAlt = Color(0xFFE8EDF3),
    divider = Color(0xFFD7DEE7),
    textPrimary = Color(0xFF16191D),
    textSecondary = Color(0xFF4E5A67),
    textDisabled = Color(0xFF9AA5B1),
    accent = Color(0xFF1667D6),
    good = Color(0xFF1B7F3B),
    bad = Color(0xFFC62828),
    onAccent = Color(0xFFFFFFFF)
)

/** 深色：比之前更亮一点的正文与次要文字，避免"深底深字"看不清 */
val DarkPadColors = PadColors(
    bg = Color(0xFF0E1216),
    panel = Color(0xFF1C2229),
    panelAlt = Color(0xFF2B343D),
    divider = Color(0xFF39434D),
    textPrimary = Color(0xFFF2F6FA),
    textSecondary = Color(0xFFB6C2CE),
    textDisabled = Color(0xFF6B7784),
    accent = Color(0xFF8AB4F8),
    good = Color(0xFF7BD88F),
    bad = Color(0xFFFF8A80),
    onAccent = Color(0xFF0B1014)
)

val LocalPadColors = staticCompositionLocalOf { DarkPadColors }

/** 当前主题是否为深色（供日志等需要自定义配色的地方使用） */
@Composable
@ReadOnlyComposable
fun isDarkTheme(): Boolean = LocalPadColors.current === DarkPadColors

// ---- 兼容写法：既有代码直接用这些名字取色，自动随主题变化 ----
val BG: Color
    @Composable @ReadOnlyComposable get() = LocalPadColors.current.bg

val PANEL_BG: Color
    @Composable @ReadOnlyComposable get() = LocalPadColors.current.panel

val PANEL_ALT: Color
    @Composable @ReadOnlyComposable get() = LocalPadColors.current.panelAlt

val DIVIDER: Color
    @Composable @ReadOnlyComposable get() = LocalPadColors.current.divider

val TEXT_PRIMARY: Color
    @Composable @ReadOnlyComposable get() = LocalPadColors.current.textPrimary

val MUTED: Color
    @Composable @ReadOnlyComposable get() = LocalPadColors.current.textSecondary

val TEXT_DISABLED: Color
    @Composable @ReadOnlyComposable get() = LocalPadColors.current.textDisabled

val ACCENT_BLUE: Color
    @Composable @ReadOnlyComposable get() = LocalPadColors.current.accent

val GOOD: Color
    @Composable @ReadOnlyComposable get() = LocalPadColors.current.good

val BAD: Color
    @Composable @ReadOnlyComposable get() = LocalPadColors.current.bad

@Composable
fun PadTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (ThemeMode.of(mode)) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val pad = if (dark) DarkPadColors else LightPadColors

    val scheme = if (dark) {
        darkColorScheme(
            primary = pad.accent,
            onPrimary = pad.onAccent,
            secondary = pad.accent,
            background = pad.bg,
            onBackground = pad.textPrimary,
            surface = pad.panel,
            onSurface = pad.textPrimary,
            surfaceVariant = pad.panelAlt,
            onSurfaceVariant = pad.textSecondary,
            outline = pad.divider,
            error = pad.bad
        )
    } else {
        lightColorScheme(
            primary = pad.accent,
            onPrimary = pad.onAccent,
            secondary = pad.accent,
            background = pad.bg,
            onBackground = pad.textPrimary,
            surface = pad.panel,
            onSurface = pad.textPrimary,
            surfaceVariant = pad.panelAlt,
            onSurfaceVariant = pad.textSecondary,
            outline = pad.divider,
            error = pad.bad
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalPadColors provides pad,
        // 关键：Material3 不会自动提供 LocalContentColor —— 裸 Column 里的 Text
        // 会退到 Color.Black，深色主题下就成"黑字黑底看不见"。
        LocalContentColor provides pad.textPrimary
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
