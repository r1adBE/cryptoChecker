package com.cryptochecker.app.ui.features.info

import com.cryptochecker.app.domain.market.IndicatorId
import com.cryptochecker.app.util.StringResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jede Kennzahl hat eine Zeile «hoch heisst …, tief heisst …» (`ind_read_<id>`),
 * dazu Hebel, Funding und Altcoins in den Info-Blättern — in allen 75 Sprachen, als ein kurzer Satz
 * ohne Platzhalter. Wie `testIndicatorReadings` (iOS, MarketSectionsTests).
 */
class IndicatorReadingTest {

    private val topicKeys = listOf("ind_read_leverage", "ind_read_funding", "ind_read_alt_season")

    @Test
    fun everyIndicatorAndTopicHasAReadingInEveryLanguage() {
        val keys = IndicatorId.entries.map { "ind_read_" + it.name.lowercase() } + topicKeys
        val languages = StringResources.byLanguage()
        assertEquals(75, languages.size)
        for ((folder, strings) in languages) {
            for (key in keys) {
                val text = strings[key]
                assertTrue("$folder: $key fehlt", !text.isNullOrBlank())
                assertFalse("$folder: $key mit Platzhalter", text!!.contains('%'))
                assertTrue("$folder: $key zu lang (${text.length})", text.length <= MAX_READING_CHARS)
            }
        }
    }

    private companion object {
        /** Eine Zeile unter der Erklärung: kurz genug, um auf dem Telefon in zwei bis drei Zeilen zu passen. */
        const val MAX_READING_CHARS = 160
    }
}
