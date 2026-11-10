package com.cryptochecker.wear

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Eine Zeile mit Betrag oder Kurs: wird die Zeile zu eng, schrumpft die Schrift schrittweise bis [MIN_SCALE] statt
 * Ziffern abzuschneiden (eine abgeschnittene Zahl wäre eine falsche Zahl) — wie iOS `minimumScaleFactor(0.6)` und die
 * Positions-Karte am Handy. Passt es auch dann nicht: sichtbar «…».
 */
object WatchFitText {
    /** Kleinste Schrift relativ zur Stilgrösse (iOS `minimumScaleFactor(0.6)`). */
    const val MIN_SCALE = 0.6f

    fun minFontSize(size: TextUnit): TextUnit = size * MIN_SCALE
}

@Composable
internal fun FitText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = style.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = WatchFitText.minFontSize(style.fontSize),
            maxFontSize = style.fontSize,
            stepSize = 0.5.sp,
        ),
        modifier = modifier,
    )
}
