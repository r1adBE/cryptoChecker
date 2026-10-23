package com.cryptochecker.app.parity

import com.cryptochecker.app.data.local.model.GROUP_NAME_MAX
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.util.TextLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Längengrenze von Notizen in sichtbaren Zeichen (`testdata/parity/text_limit.json`): Emoji, ZWJ-Familie,
 * kombinierende Akzente und Flaggen werden nie zerrissen und zählen je als ein Zeichen. Gegenstück:
 * `Tests/TextLimitParityTests.swift`.
 */
class TextLimitParityTest {

    private val fixture = ParityJson.load("text_limit.json")
    private val cases = fixture["cases"].list()

    @Test
    fun kuerztUndZaehltWieGemeinsameTestfaelle() {
        assertTrue(cases.size >= 10)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["description"] as String
            val text = c["text"] as String
            // ZWJ-Folgen, Flaggen und Hauttöne kennt java.text.BreakIterator erst ab JDK 20 (CI baut mit JDK 17);
            // auf dem Gerät zählt ICU sie immer richtig (androidTest TextLimitDeviceTest)
            if (jdkFeature() < 20 && needsExtendedClusters(text)) continue
            val max = c["max"].num()!!.toInt()
            assertEquals(name, c["expected"] as String, TextLimit.takeGraphemes(text, max))
            assertEquals(name, c["count"].num()!!.toInt(), TextLimit.graphemeCount(text))
        }
    }

    /**
     * Gruppenname, Börse/Wallet und Notiz säubern wie Eingabe und Sicherung: Android kürzte Gruppen und
     * Börsen nach UTF-16-Einheiten, iOS nach sichtbaren Zeichen.
     */
    @Test
    fun saeubertFelderWieGemeinsameTestfaelle() {
        val fields = fixture["fields"].list()
        assertTrue(fields.size >= 8)
        for (raw in fields) {
            val c = raw.obj()
            val name = c["description"] as String
            val text = c["text"] as String
            if (jdkFeature() < 20 && needsExtendedClusters(text)) continue
            val actual = when (val field = c["field"] as String) {
                "group" -> TextLimit.clean(text, GROUP_NAME_MAX)
                "exchange" -> PortfolioJson.cleanExchange(text)
                "note" -> TextLimit.clean(text, NOTE_MAX)
                else -> error("unbekanntes Feld $field")
            }
            assertEquals(name, c["expected"] as String?, actual)
        }
    }

    /** JDK-Hauptversion der Test-JVM (Runtime.version() fehlt im android.jar, gegen das die Tests kompilieren). */
    private fun jdkFeature(): Int =
        System.getProperty("java.specification.version")?.substringAfter("1.")?.substringBefore('.')?.toIntOrNull() ?: 0

    private fun needsExtendedClusters(text: String): Boolean = text.codePoints().anyMatch { cp ->
        cp == 0x200D || cp in 0x1F1E6..0x1F1FF || cp in 0x1F3FB..0x1F3FF
    }

    /** Gekürzter Text endet nie auf einer halben UTF-16-Einheit. */
    @Test
    fun keinHalbesSurrogatpaar() {
        val cut = TextLimit.takeGraphemes("a".repeat(119) + "😀😀", 120)
        assertEquals(121, cut.length)
        assertTrue(!Character.isHighSurrogate(cut.last()))
    }
}
