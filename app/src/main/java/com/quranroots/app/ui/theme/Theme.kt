package com.quranroots.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.quranroots.app.R

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F6E5C),
    secondary = Color(0xFF6A8E22),
    tertiary = Color(0xFFC07A1F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BD0BC),
    secondary = Color(0xFFB6D680),
    tertiary = Color(0xFFE0B380)
)

@Composable
fun QuranRootsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}

/** Mushaf font (Amiri Quran, SIL OFL 1.1 — license in assets/amiri_quran_OFL.txt).
 *  Renders every Uthmani mark in our text (ۥ ۦ ۟ ۢ ۖ …) that system fonts
 *  show as dotted circles or stray symbols. Use for Quranic text only. */
val QuranFont = FontFamily(Font(R.font.amiri_quran))

object Highlight {
    val WordBg = Color(0xFFFFE082)
    val WordBgDark = Color(0xFF7A6118)
    val RelatedBg = Color(0xFFB2DFDB)
    val RelatedBgDark = Color(0xFF1F4E48)

    /** One color per root in a study (cycles after 6). Light / dark pairs. */
    val StudyLight = listOf(
        Color(0xFFFFE082), Color(0xFFB2DFDB), Color(0xFFF8BBD0),
        Color(0xFFC5CAE9), Color(0xFFDCEDC8), Color(0xFFFFCCBC)
    )
    val StudyDark = listOf(
        Color(0xFF7A6118), Color(0xFF1F4E48), Color(0xFF6A2C42),
        Color(0xFF2E3A6B), Color(0xFF3D5226), Color(0xFF6E3B27)
    )
}
