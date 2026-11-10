package com.cryptochecker.wear

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Beträge und Kurse der Uhr-Zeilen schrumpfen bis 60 % statt Ziffern abzuschneiden (wie iOS `minimumScaleFactor(0.6)`). */
class WatchFitTextTest {

    @Test
    fun kleinsteSchriftSechzigProzent() {
        assertEquals(0.6f, WatchFitText.MIN_SCALE, 0f)
        assertEquals(9.0f, WatchFitText.minFontSize(15.sp).value, 0.001f)
    }

    /** Kurszeile und Portfolio-Wert nutzen [FitText], nicht mehr `Text(..., maxLines = 1)` ohne Schrumpfen. */
    @Test
    fun zeilenNutzenFitText() {
        val app = source("WatchApp.kt")
        assertTrue(app.contains("FitText(\"\$coin  \$price\""))
        assertFalse(app.contains("Text(\"\$coin  \$price\", maxLines = 1"))
        val portfolio = source("WatchPortfolioRow.kt")
        assertTrue(portfolio.contains("FitText(WatchPortfolioText.total(p)"))
    }

    private fun source(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in listOf("src/main/java/com/cryptochecker/wear/", "wear/src/main/java/com/cryptochecker/wear/",
                "android/wear/src/main/java/com/cryptochecker/wear/")) {
                val f = File(dir, rel + name)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        throw AssertionError("$name nicht gefunden")
    }
}
