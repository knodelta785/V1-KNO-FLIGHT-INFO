package id.ongarand.flightradarkno

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

object ThemeState {
    var mode by mutableIntStateOf(0)            // 0 sistem, 1 terang, 2 gelap
    var accent by mutableIntStateOf(Store.DEFAULT_ACCENT)
    fun load(c: Context) {
        mode = Store.p(c).getInt("theme", 0)
        accent = Store.accent(c)
    }
    fun save(c: Context) {
        Store.p(c).edit().putInt("theme", mode).putInt("accent", accent).apply()
    }
}

fun buildScheme(a: Color, dark: Boolean): ColorScheme {
    val onA = if (a.luminance() > 0.5f) Color.Black else Color.White
    return if (dark) {
        val bg = Color(0xFF121212)
        val mid = Color(0xFF1E1E1E)
        darkColorScheme(
            primary = a, onPrimary = onA,
            primaryContainer = lerp(mid, a, 0.35f), onPrimaryContainer = Color.White,
            secondaryContainer = lerp(mid, a, 0.35f), onSecondaryContainer = Color.White,
            background = bg, onBackground = Color.White,
            surface = bg, onSurface = Color.White,
            surfaceVariant = lerp(mid, a, 0.14f), onSurfaceVariant = Color(0xFFCCCCCC)
        )
    } else {
        val bg = Color(0xFFFFFBFE)
        lightColorScheme(
            primary = a, onPrimary = onA,
            primaryContainer = lerp(Color.White, a, 0.25f), onPrimaryContainer = Color.Black,
            secondaryContainer = lerp(Color.White, a, 0.25f), onSecondaryContainer = Color.Black,
            background = bg, onBackground = Color(0xFF1C1B1F),
            surface = bg, onSurface = Color(0xFF1C1B1F),
            surfaceVariant = lerp(Color.White, a, 0.10f), onSurfaceVariant = Color(0xFF49454F)
        )
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeState.mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = buildScheme(Color(ThemeState.accent), dark), content = content)
}

val AlertRed = Color(0xFFD32F2F)
