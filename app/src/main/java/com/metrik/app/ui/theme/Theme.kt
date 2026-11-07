package com.metrik.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colores de MetriK: azul petróleo (plano) y ámbar (cinta métrica). */
object MetrikColors {
    val Petrol = Color(0xFF0F4C5C)
    val PetrolLight = Color(0xFF5FA8B8)
    val Amber = Color(0xFFFFB703)
    val Wall = Color(0xFF0F4C5C)
    val Track = Color(0xFF8E9AAF)
    val Corner = Color(0xFFE76F51)
    val Ok = Color(0xFF2A9D8F)
    val Warn = Color(0xFFE9C46A)
    val Bad = Color(0xFFE63946)
}

private val Light = lightColorScheme(
    primary = MetrikColors.Petrol,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE7EE),
    onPrimaryContainer = Color(0xFF032A33),
    secondary = MetrikColors.Amber,
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFFFFE6A8),
    background = Color(0xFFF7F9FA),
    surface = Color.White,
)

private val Dark = darkColorScheme(
    primary = MetrikColors.PetrolLight,
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF0F4C5C),
    onPrimaryContainer = Color(0xFFCDE7EE),
    secondary = MetrikColors.Amber,
    onSecondary = Color(0xFF2B1D00),
    background = Color(0xFF0E1416),
    surface = Color(0xFF151C1F),
)

@Composable
fun MetrikTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = Typography(), content = content)
}
