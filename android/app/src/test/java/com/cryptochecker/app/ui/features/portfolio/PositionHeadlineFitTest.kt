package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wert der Positions-Karte: zu eng → Schrift schrumpft bis 60 % (wie iOS `minimumScaleFactor(0.6)`), nie abgeschnittene
 * Ziffern. Wächter auf den Quelltext (Compose-Layout ist im Unit-Test nicht messbar).
 */
class PositionHeadlineFitTest {

    @Test
    fun wertSchrumpftStattAbzuschneiden() {
        val rel = "ui/features/portfolio/PortfolioPositionCard.kt"
        val src = SourceGuard.android(findUp(rel), rel).readText(Charsets.UTF_8)
        val headline = src.substringAfter("val headline = MaterialTheme.typography.headlineSmall").substringBefore("R.string.portfolio_holdings")
        assertTrue(headline.contains("autoSize = TextAutoSize.StepBased("))
        assertTrue(headline.contains("minFontSize = headline.fontSize * 0.6f"))
        assertTrue(headline.contains("overflow = TextOverflow.Ellipsis"))
    }

    private fun findUp(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/", "android/app/")) {
                val f = File(dir, "${prefix}src/main/java/com/cryptochecker/app/$rel")
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
