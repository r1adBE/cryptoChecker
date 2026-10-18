package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.parity.ParityJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** Kurzer Börsentext für den Bericht; Fälle `exchange_detail.json`, wie iOS. */
    @Test
    fun exchangeDetailCases() {
        @Suppress("UNCHECKED_CAST")
        val data = ParityJson.load("stored_error_detail.json")
        @Suppress("UNCHECKED_CAST")
        for (c in data["cases"] as List<Map<String, Any?>>) {
            val out = StoredError.exchangeDetail(c["input"] as String?)
            assertEquals(c["name"].toString(), c["expected"] as String?, out)
            if (out != null) {
                assertTrue(out.length <= StoredError.MAX_DETAIL)
                assertFalse(c["name"].toString(), "://" in out || ".com" in out || ".io" in out)
            }
        }
        @Suppress("UNCHECKED_CAST")
        for (r in data["report"] as List<Map<String, Any?>>) {
            @Suppress("UNCHECKED_CAST")
            val errors = r["errors"] as List<String?>
            val reason = (r["reason"] as String?)?.let { RefreshFailure.valueOf(it) }
            assertEquals(r.toString(), r["expected"] as String?, StoredError.reportDetail(errors, reason))
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
