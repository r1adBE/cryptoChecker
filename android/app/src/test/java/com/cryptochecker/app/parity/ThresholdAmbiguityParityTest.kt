package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.ThresholdParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mehrdeutige Zahl (`testdata/parity/threshold.json`, «ambiguity»): «1.234» in DE bzw. «1,234» in US wird nie still als
 * Tausendertrennung gelesen; der Fehlertext nennt beide Schreibweisen. Gegenstück: `Tests/ThresholdAmbiguityParityTests.swift`.
 */
class ThresholdAmbiguityParityTest {

    @Test
    fun ambiguity() {
        val cases = ParityJson.load("threshold.json")["ambiguity"].list()
        cases.forEach { raw ->
            val c = raw.obj()
            val text = c["text"] as String
            val separator = (c["separator"] as String).single()
            val actual = ThresholdParser.ambiguity(text, separator, c["hint"].num())
            val expected = c["expected"]?.obj()
            if (expected == null) {
                assertNull("'$text' ($separator)", actual)
            } else {
                assertEquals("'$text' ($separator)", ThresholdParser.Ambiguity(expected["grouped"] as String, expected["decimal"] as String), actual)
                // Mehrdeutig heisst immer: parse liest nichts (nie still eine Lesart)
                assertNull(ThresholdParser.parse(text, separator, c["hint"].num()))
                // Beide genannten Schreibweisen sind eindeutig
                assertNull(ThresholdParser.ambiguity(actual!!.grouped, separator))
                assertNull(ThresholdParser.ambiguity(actual.decimal, separator))
            }
        }
        assertTrue(cases.size >= 15)
    }
}
