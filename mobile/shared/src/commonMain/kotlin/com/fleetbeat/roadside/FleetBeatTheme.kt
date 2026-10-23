package com.fleetbeat.roadside

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** FleetBeat website: brand green #28AF60, slate #2B3D4F, canvas #F6F9FB.
 * Interactive text uses a deeper green for contrast on the light surfaces.
 */
val FleetBeatLightColors = lightColorScheme(
    primary = Color(0xFF187D43), onPrimary = Color.White,
    primaryContainer = Color(0xFFDFF4E8), onPrimaryContainer = Color(0xFF104B2A),
    inversePrimary = Color(0xFF8AD9AA),
    secondary = Color(0xFF2B3D4F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EDF3), onSecondaryContainer = Color(0xFF203547),
    tertiary = Color(0xFF28AF60), onTertiary = Color(0xFF082D17),
    tertiaryContainer = Color(0xFFE2F5E9), onTertiaryContainer = Color(0xFF104B2A),
    background = Color(0xFFF6F9FB), onBackground = Color(0xFF2B3D4F),
    surface = Color.White, onSurface = Color(0xFF2B3D4F),
    surfaceVariant = Color(0xFFEDF3F5), onSurfaceVariant = Color(0xFF56646F),
    surfaceTint = Color(0xFF187D43),
    inverseSurface = Color(0xFF2B3D4F), inverseOnSurface = Color.White,
    outline = Color(0xFF788893), outlineVariant = Color(0xFFD4DFE5),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    surfaceBright = Color.White, surfaceDim = Color(0xFFE4ECEF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F9FB),
    surfaceContainer = Color(0xFFF0F5F7), surfaceContainerHigh = Color(0xFFEAF1F4),
    surfaceContainerHighest = Color(0xFFE3ECEF), scrim = Color.Black,
)
