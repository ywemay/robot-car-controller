package com.ywemay.robotcar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Fixed dark palette for the onboard dashboard.
 *
 * Deliberately not DayNight: this phone is bolted to a robot car and read
 * outdoors, so a predictable high-contrast dark surface (plus the matching dark
 * window theme in res/values/themes.xml) beats following the system setting.
 */
private val RobotCarColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00222F),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00332E),
    background = Color(0xFF0E1216),
    onBackground = Color(0xFFE3E7EA),
    surface = Color(0xFF161C22),
    onSurface = Color(0xFFE3E7EA),
    surfaceVariant = Color(0xFF232C34),
    onSurfaceVariant = Color(0xFFBAC5CD),
    outline = Color(0xFF3A464F),
)

@Composable
fun RobotCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RobotCarColors, content = content)
}
