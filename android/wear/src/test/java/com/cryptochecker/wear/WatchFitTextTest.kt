package com.cryptochecker.wear

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Beträge und Kurse der Uhr-Zeilen schrumpfen bis 60 % statt Ziffern abzuschneiden (wie iOS `minimumScaleFactor(0.6)`);
 * dass Kurszeile und Portfolio-Wert [FitText] nutzen, prüft die Regel `android-wiring` in `.github/scripts/source_rules.py`. */
class WatchFitTextTest {

    @Test
    fun smallestFontSixtyPercent() {
        assertEquals(0.6f, WatchFitText.MIN_SCALE, 0f)
        assertEquals(9.0f, WatchFitText.minFontSize(15.sp).value, 0.001f)
    }
}
