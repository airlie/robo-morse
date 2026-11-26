package com.robomorse.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.robomorse.core.MasteryTier

private val Navy = Color(0xFF1F3A5F)
private val Amber = Color(0xFFF2C14E)

private val Light = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    secondary = Color(0xFF8A6A00),
    tertiary = Color(0xFF2E7D5B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9EC3F0),
    onPrimary = Color(0xFF0B1E33),
    secondary = Amber,
    tertiary = Color(0xFF7BD3A8),
)

@Composable
fun RoboMorseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Fill colour for a mastery tier on the character map (one hue, rising strength). */
@Composable
fun tierColor(tier: MasteryTier?): Color {
    val dark = isSystemInDarkTheme()
    return when (tier) {
        null -> if (dark) Color(0xFF22262B) else Color(0xFFE6E8EB)
        MasteryTier.NEW -> if (dark) Color(0xFF3A3F46) else Color(0xFFCDD3DA)
        MasteryTier.LEARNING -> if (dark) Color(0xFF6B5A1E) else Color(0xFFF6DE9C)
        MasteryTier.SOLID -> if (dark) Color(0xFF2F6E52) else Color(0xFF9FD8BC)
        MasteryTier.INSTANT -> if (dark) Color(0xFF3FA57A) else Color(0xFF2E9E6E)
    }
}

@Composable
fun onTierColor(tier: MasteryTier?): Color {
    val dark = isSystemInDarkTheme()
    return when (tier) {
        MasteryTier.INSTANT -> Color.White
        null -> if (dark) Color(0xFF6B7178) else Color(0xFF9AA1A9)
        else -> if (dark) Color(0xFFE8EAED) else Color(0xFF1B1F24)
    }
}

val CorrectGreen = Color(0xFF2E9E6E)
val WrongRed = Color(0xFFD64545)
