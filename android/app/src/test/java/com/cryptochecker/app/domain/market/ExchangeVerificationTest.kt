package com.cryptochecker.app.domain.market

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Datum «zuletzt gegen echte Antworten geprüft» passt zu den Aufnahmen (iOS: `ExchangeVerificationTests.swift`). */
class ExchangeVerificationTest {

    /** `testdata/exchanges/recorded` oberhalb des Arbeitsverzeichnisses. */
    private fun recordedDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/exchanges/recorded")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw IllegalStateException("testdata/exchanges/recorded not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun lastVerifiedIsNotBeforeNewestRecording() {
        val days = recordedDir().listFiles { f -> f.name.startsWith("recorded_") && f.name.endsWith(".json") }.orEmpty()
            .map { f ->
                val at = ParityJson.parse(f.readText(Charsets.UTF_8)).obj()["recordedAt"] as String
                Instant.parse(at).atZone(ZoneOffset.UTC).toLocalDate()
            }
        assertTrue("recorded responses", days.isNotEmpty())
        val newest = days.max()
        assertFalse(
            "ExchangeVerification.LAST_VERIFIED ${ExchangeVerification.LAST_VERIFIED} is before the newest recording $newest",
            ExchangeVerification.LAST_VERIFIED.isBefore(newest),
        )
        // Nicht in der Zukunft der Aufnahmen erfunden: höchstens einen Tag nach der neuesten (Aufnahme kurz vor Mitternacht)
        assertFalse(ExchangeVerification.LAST_VERIFIED.isAfter(newest.plusDays(1)))
        assertTrue(ExchangeVerification.LAST_VERIFIED.isAfter(LocalDate.of(2026, 1, 1)))
    }
}
