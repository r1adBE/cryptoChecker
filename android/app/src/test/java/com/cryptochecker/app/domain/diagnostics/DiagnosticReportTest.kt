package com.cryptochecker.app.domain.diagnostics

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.alarm.AlarmLogEntry
import com.cryptochecker.app.domain.alarm.AlarmLogOutcome
import com.cryptochecker.app.domain.alarm.AlarmReadiness
import com.cryptochecker.app.domain.refresh.CheckResult
import com.cryptochecker.app.domain.refresh.CheckRun
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** «Diagnose teilen»: Inhalt des Berichts und vor allem, was nie hineinkommt. Gegenstück: `DiagnosticReportTests.swift`. */
class DiagnosticReportTest {

    private val now = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()

    private val evmAddress = "0x6982508145454ce325ddbe47a25d4ec3d2311933"

    private fun entry(pair: String, outcome: AlarmLogOutcome, at: Long, until: Long = 0) = AlarmLogEntry(
        alarmId = 7,
        at = at,
        pair = pair,
        exchange = "Kraken",
        quote = "CHF",
        price = 64321.987,
        condition = AlarmCondition.PRICE_ABOVE,
        threshold = 70123.456,
        currency = "EUR",
        shownPrice = 61234.5,
        outcome = outcome,
        exchangeTime = at - 1,
        notable = true,
        until = until,
    )

    private fun input(
        events: List<AlarmLogEntry> = emptyList(),
        runs: List<CheckRun> = emptyList(),
        pauses: Map<String, ExchangeBackoff.State> = emptyMap(),
        errorLog: List<String>? = null,
    ) = DiagnosticInput(
        createdAt = now,
        zone = "Europe/Zurich",
        appVersion = "16.2.2",
        appBuild = "81",
        system = "Android 15 (API 35)",
        device = "Google Pixel 8",
        locale = "de-CH",
        settings = DiagnosticSettings(
            updateMode = "SPARSAM", backgroundIntervalMinutes = 15, exactChecks = true, priceNotifications = true,
            alarmSignal = "SYSTEM", alarmCooldownMinutes = 5, quietHoursEnabled = true, quietHoursStart = 23 * 60,
            quietHoursEnd = 7 * 60, activeAlarms = 3,
        ),
        readiness = listOf(
            AlarmReadiness.Item(AlarmReadiness.Row.NOTIFICATIONS, AlarmReadiness.State.OK, AlarmReadiness.Detail.ALLOWED),
            AlarmReadiness.Item(
                AlarmReadiness.Row.CHANNEL, AlarmReadiness.State.WARN, AlarmReadiness.Detail.CHANNEL_LOW,
                AlarmReadiness.Action.CHANNEL_SETTINGS,
            ),
        ),
        checkRuns = runs,
        alarmEvents = events,
        exchangePauses = pauses,
        errorLog = errorLog,
    )

    @Test
    fun headerSettingsAndSections() {
        val text = DiagnosticReport.build(
            input(
                events = listOf(entry("BTC/CHF", AlarmLogOutcome.FIRED, now - 60_000)),
                runs = listOf(CheckRun(now - 120_000, CheckTrigger.WORKER, CheckResult.OK, 2_140)),
                pauses = mapOf(
                    "binance" to ExchangeBackoff.State(strikes = 2, pausedUntil = now + 60_000, reason = RefreshFailure.RATE_LIMIT),
                    "kraken" to ExchangeBackoff.State(strikes = 1, pausedUntil = now - 60_000, reason = RefreshFailure.TIMEOUT),
                ),
            )
        )
        // 12:00 UTC = 14:00 in Zürich (Sommerzeit)
        assertTrue(text.contains("Created: 2026-10-18 14:00 (Europe/Zurich)"))
        assertTrue(text.contains("App: 16.2.2 (81)"))
        // Seit Build 103: ohne Angabe «local»
        assertTrue(text.contains("Source: local"))
        assertTrue(text.contains("Exact checks: on"))
        assertTrue(text.contains("Quiet hours: on 23:00-07:00"))
        assertTrue(text.contains("channel: warn (channel_low)"))
        assertTrue(text.contains("2026-10-18 13:58 | worker | ok | 2.1 s"))
        assertTrue(text.contains("2026-10-18 13:59 | BTC/CHF | Kraken | PRICE_ABOVE | fired"))
        assertTrue(text.contains("binance | paused until 2026-10-18 14:01 | RATE_LIMIT | strikes 2"))
        assertTrue(text.contains("kraken | not paused | TIMEOUT | strikes 1"))
        assertTrue(text.contains("none - the app keeps no error log"))
        // Seit Build 104: Zähler der Fehler im Hintergrund
        assertTrue(text.contains("none - the app keeps no error log\nBackground errors: none\n"))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun backgroundErrorsLine() {
        val text = DiagnosticReport.build(
            input().copy(backgroundErrors = BackgroundErrorSummary(mapOf("refresh" to 2, "logos" to 1), now))
        )
        assertTrue(text.contains("Background errors: refresh 2, logos 1 (last 2026-10-18 12:00 UTC)\n"))
    }

    @Test
    fun emptySectionsSayNone() {
        val text = DiagnosticReport.build(input(errorLog = emptyList()))
        assertEquals(4, Regex("""\(none\)""").findAll(text).count())
    }

    @Test
    fun iosHasNoExactLine() {
        val base = input()
        val text = DiagnosticReport.build(base.copy(settings = base.settings.copy(exactChecks = null)))
        assertFalse(text.contains("Exact checks"))
    }

    @Test
    fun collapsedEventShowsPeriod() {
        val text = DiagnosticReport.build(
            input(events = listOf(entry("ETH/CHF", AlarmLogOutcome.QUIET_HOURS, now - 3_600_000, until = now - 600_000)))
        )
        assertTrue(text.contains("2026-10-18 13:00 - 2026-10-18 13:50 | ETH/CHF | Kraken | PRICE_ABOVE | quiet_hours"))
    }

    @Test
    fun neverPricesThresholdsOrAddresses() {
        val text = DiagnosticReport.build(
            input(
                events = listOf(
                    entry("PEPE/$evmAddress", AlarmLogOutcome.FIRED, now - 60_000),
                    entry("BTC/CHF", AlarmLogOutcome.COOLDOWN, now - 120_000),
                ),
                pauses = mapOf("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq" to ExchangeBackoff.State(strikes = 1)),
                errorLog = listOf("lookup failed for xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"),
            )
        )
        // Kurse, Schwellen und umgerechnete Kurse des Protokolls kommen nie in den Text
        for (number in listOf("64321", "64’321", "64,321", "70123", "70’123", "70,123", "61234", "61’234")) {
            assertFalse("contains $number", text.contains(number))
        }
        assertFalse(text.contains(evmAddress))
        assertFalse(text.contains("6982508145454ce325ddbe47a25d4ec3d2311933"))
        assertFalse(text.contains("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        assertFalse(text.contains("xpub6CUGRU"))
        assertTrue(text.contains("PEPE/${DiagnosticReport.REDACTED}"))
        // Die Eingabe hat gar keine Felder für Portfolio, Beträge, Adressen oder Notizen
        val fields = DiagnosticInput::class.java.declaredFields.map { it.name.lowercase() } +
            DiagnosticSettings::class.java.declaredFields.map { it.name.lowercase() }
        for (word in listOf("portfolio", "amount", "address", "note", "holding", "balance", "wallet", "xpub")) {
            assertFalse("field with $word", fields.any { it.contains(word) })
        }
    }

    @Test
    fun redactSharedCases() {
        val data = ParityJson.load("diagnostic_redact.json")
        assertEquals(DiagnosticReport.REDACTED, data["redacted"])
        for (raw in data["redact"].list()) {
            val c = raw.obj()
            assertEquals(c["in"] as String, c["out"], DiagnosticReport.redact(c["in"] as String))
        }
    }

    /** Seit Build 103: Quell-Commit in der Zeile «Source», gleiche Fälle wie iOS. */
    @Test
    fun sourceCommitSharedCases() {
        val data = ParityJson.load("diagnostic_redact.json")
        for (raw in data["sourceCommit"].list()) {
            val c = raw.obj()
            assertEquals(c["in"] as String, c["out"], DiagnosticReport.sourceCommit(c["in"] as String))
        }
        // Ein Hash wird nie als Adresse ersetzt
        val text = DiagnosticReport.build(input().copy(sourceCommit = "3f9a1c2e7b4d5a6f8e9d0c1b2a3f4e5d6c7b8a9f"))
        assertTrue(text.contains("Source: 3f9a1c2e7b4d\n"))
    }
}
