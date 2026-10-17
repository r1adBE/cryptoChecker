package com.cryptochecker.app.domain.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Review 10 F7: Budget je Börse im exakten Wecker — eine langsame Börse lässt die anderen nicht leer ausgehen. */
class CheckBudgetTest {

    @Test
    fun `without deadline there is no budget`() {
        assertNull(CheckBudget.groupDeadline(1_000L, null))
        assertEquals(5_000L, CheckBudget.waitMillis(null, 0L, 5_000L, reserve = 2_000L))
    }

    @Test
    fun `group gets at most 8 s and leaves the evaluation reserve`() {
        // Receiver: 19 s Frist → 8 s je Börse
        assertEquals(8_000L, CheckBudget.groupDeadline(0L, 19_000L))
        // Spät gestartet (Sperre gewartet): nur bis Frist minus Reserve
        assertEquals(14_000L, CheckBudget.groupDeadline(10_000L, 19_000L))
        // Schon alles verbraucht: nie vor dem Start (keine Abfrage mehr)
        assertEquals(17_000L, CheckBudget.groupDeadline(17_000L, 19_000L))
    }

    @Test
    fun `wait is capped, keeps the reserve and never negative`() {
        assertEquals(5_000L, CheckBudget.waitMillis(19_000L, 8_000L, 5_000L, reserve = 5_000L))
        assertEquals(3_000L, CheckBudget.waitMillis(19_000L, 11_000L, 5_000L, reserve = 5_000L))
        assertEquals(0L, CheckBudget.waitMillis(19_000L, 18_000L, 5_000L, reserve = 5_000L))
        assertEquals(11_000L, CheckBudget.waitMillis(19_000L, 8_000L, Long.MAX_VALUE))
    }

    @Test
    fun `budget cuts alone do not touch the exchange pause`() {
        // Nur am Budget gescheitert, kein Kurs: Zustand bleibt (weder Pause noch «Erfolg»)
        assertNull(CheckBudget.backoffOutcome(emptyList(), updated = 0, cutByBudget = 3))
        // Teilweise Kurse: Erfolg
        assertEquals(ExchangeBackoff.Outcome.SUCCESS, CheckBudget.backoffOutcome(emptyList(), updated = 2, cutByBudget = 1))
        // Echte Zeitüberschreitung daneben zählt weiter
        assertEquals(
            ExchangeBackoff.Outcome.TIMEOUT,
            CheckBudget.backoffOutcome(listOf(RefreshFailure.TIMEOUT), updated = 0, cutByBudget = 1),
        )
        // Ohne Budget unverändert wie bisher
        assertEquals(ExchangeBackoff.Outcome.SUCCESS, CheckBudget.backoffOutcome(emptyList(), updated = 0, cutByBudget = 0))
        assertEquals(CheckBudget.TIMEOUT_ERROR.let(RefreshReportLogic::classify), RefreshFailure.TIMEOUT)
    }

    @Test
    fun `doze keeps the network within the idle window`() {
        // Ausserhalb von Doze: unverändert 20 s
        assertEquals(20_000L, CheckBudget.exactTimeoutMillis(false, 20_000L, 1_000L))
        // Doze: 9 s Netz + 5 s Auswerten + 1 s Reserve
        val idle = CheckBudget.exactTimeoutMillis(true, 20_000L, 1_000L)
        assertEquals(15_000L, idle)
        val deadlineAt = idle - 1_000L
        // Budget je Börse (8 s) endet im Netz-Fenster, Bezüge warten höchstens bis 9 s
        assertEquals(8_000L, CheckBudget.groupDeadline(0L, deadlineAt))
        assertEquals(CheckBudget.IDLE_NETWORK_MILLIS, CheckBudget.waitMillis(deadlineAt, 0L, Long.MAX_VALUE, reserve = CheckBudget.EVALUATE_RESERVE_MILLIS))
        // Spät gestartet: nie über das Netz-Fenster hinaus
        assertEquals(CheckBudget.IDLE_NETWORK_MILLIS, CheckBudget.groupDeadline(4_000L, deadlineAt))
        // Nie länger als die normale Grenze
        assertEquals(10_000L, CheckBudget.exactTimeoutMillis(true, 10_000L, 1_000L))
    }

    @Test
    fun `widget charts load outside the lock only with enough time left`() {
        // Ohne Frist: Gesamtgrenze
        assertEquals(CheckBudget.CHART_TIMEOUT_MILLIS, CheckBudget.chartFetchMillis(null, 5_000L))
        // Doze: Frist 14 s, Auswerten fertig nach 10 s → nur noch 4 s, keine Charts
        assertNull(CheckBudget.chartFetchMillis(14_000L, 10_000L))
        assertNull(CheckBudget.chartFetchMillis(14_000L, 14_500L))
        // Genug Zeit: bis zur Frist minus Reserve
        assertEquals(6_000L, CheckBudget.chartFetchMillis(19_000L, 11_000L))
        // Viel Zeit: höchstens die Gesamtgrenze
        assertEquals(CheckBudget.CHART_TIMEOUT_MILLIS, CheckBudget.chartFetchMillis(100_000L, 0L))
    }
}
