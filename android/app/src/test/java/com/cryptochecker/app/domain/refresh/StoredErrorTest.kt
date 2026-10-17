package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.parity.ParityJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Gespeicherte Fehler ohne Hosts/URLs/Adressen; Fälle geteilt mit StoredErrorTests.swift. */
class StoredErrorTest {

    @Suppress("UNCHECKED_CAST")
    private val cases = ParityJson.load("stored_error.json")["cases"] as List<Map<String, Any?>>

    @Test
    fun fixtureCases() {
        for (c in cases) {
            val input = c["input"] as String?
            assertEquals(c["name"].toString(), c["expected"] as String?, StoredError.sanitize(input))
        }
    }

    @Test
    fun classificationUnchanged() {
        for (c in cases) {
            val input = c["input"] as String? ?: continue
            assertEquals(
                c["name"].toString(),
                RefreshReportLogic.classify(input),
                RefreshReportLogic.classify(StoredError.sanitize(input)),
            )
        }
    }

    @Test
    fun noUrlsOrHostsSurvive() {
        for (c in cases) {
            val out = StoredError.sanitize(c["input"] as String?) ?: continue
            assertFalse(c["name"].toString(), "http://" in out || "https://" in out || ".com" in out)
        }
    }
}
