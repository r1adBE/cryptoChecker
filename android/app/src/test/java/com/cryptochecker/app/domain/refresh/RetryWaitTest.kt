package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.util.SourceGuard
import com.cryptochecker.app.work.BackgroundWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryWaitTest {

    private val now = 1_700_000_000_000L
    private val backoff = BackgroundWorkPolicy.RETRY_BACKOFF_SECONDS * 1000

    // ── Zustand aus der WorkInfo ────────────────────────────────────────────

    @Test
    fun waitingAfterRetryGivesNextScheduleTime() {
        assertEquals(now + 27_000, RetryWait.retryAt(enqueued = true, runAttemptCount = 1, now + 27_000))
        assertEquals(now + 60_000, RetryWait.retryAt(enqueued = true, runAttemptCount = 2, now + 60_000))
    }

    @Test
    fun firstWaitIsNoRetry() {
        // Eben angestossen, noch nie gelaufen: kein Countdown (nur der Kreis)
        assertNull(RetryWait.retryAt(enqueued = true, runAttemptCount = 0, nextScheduleTimeMillis = now))
    }

    @Test
    fun runningOrFinishedIsNoRetry() {
        assertNull(RetryWait.retryAt(enqueued = false, runAttemptCount = 1, nextScheduleTimeMillis = now + 30_000))
    }

    @Test
    fun unknownScheduleTimeIsNoRetry() {
        assertNull(RetryWait.retryAt(enqueued = true, runAttemptCount = 1, nextScheduleTimeMillis = Long.MAX_VALUE))
        assertNull(RetryWait.retryAt(enqueued = true, runAttemptCount = 1, nextScheduleTimeMillis = 0))
    }

    // ── Zeitpunkt im Worker (linearer Backoff) ──────────────────────────────

    @Test
    fun linearBackoffGrowsWithAttempts() {
        assertEquals(now + 30_000, RetryWait.linearRetryAt(now, backoff, runAttemptCount = 0))
        assertEquals(now + 60_000, RetryWait.linearRetryAt(now, backoff, runAttemptCount = 1))
        assertEquals(now + 90_000, RetryWait.linearRetryAt(now, backoff, runAttemptCount = 2))
    }

    @Test
    fun linearBackoffCappedAtFiveHours() {
        val capped = RetryWait.linearRetryAt(now, backoff, runAttemptCount = 10_000)
        assertEquals(now + RetryWait.MAX_BACKOFF_MILLIS, capped)
    }

    // ── Countdown ───────────────────────────────────────────────────────────

    @Test
    fun remainingSecondsRoundUp() {
        assertEquals(30, RetryWait.remainingSeconds(now + 30_000, now))
        assertEquals(27, RetryWait.remainingSeconds(now + 26_001, now))
        assertEquals(1, RetryWait.remainingSeconds(now + 1, now))
    }

    @Test
    fun remainingSecondsZeroWhenOverOrNone() {
        assertEquals(0, RetryWait.remainingSeconds(now, now))
        assertEquals(0, RetryWait.remainingSeconds(now - 5_000, now))
        assertEquals(0, RetryWait.remainingSeconds(null, now))
    }

    @Test
    fun nextChangeOnSecondBoundary() {
        // 26,001 s → «27 s» bis 26,000 s, dann «26 s»
        assertEquals(1L, RetryWait.millisUntilNextChange(now + 26_001, now))
        assertEquals(1_000L, RetryWait.millisUntilNextChange(now + 30_000, now))
        assertEquals(400L, RetryWait.millisUntilNextChange(now + 400, now))
        assertEquals(0L, RetryWait.millisUntilNextChange(now, now))
        assertEquals(0L, RetryWait.millisUntilNextChange(null, now))
    }

    @Test
    fun countdownTicksDownToZero() {
        val retryAt = now + 2_500
        var t = now
        val shown = mutableListOf<Int>()
        while (true) {
            val s = RetryWait.remainingSeconds(retryAt, t)
            shown += s
            if (s == 0) break
            t += RetryWait.millisUntilNextChange(retryAt, t)
        }
        assertEquals(listOf(3, 2, 1, 0), shown)
        assertEquals(retryAt, t)
    }

    // ── Widget: feste Uhrzeit ───────────────────────────────────────────────

    @Test
    fun widgetShowsFixedRetryTimeWhileAhead() {
        val retryAt = now + 90_000
        // Fest: dieselbe Uhrzeit, egal wann gezeichnet wird
        assertEquals(retryAt, RetryWait.widgetRetryTime(retryAt, now))
        assertEquals(retryAt, RetryWait.widgetRetryTime(retryAt, now + 89_999))
    }

    @Test
    fun widgetHidesRetryTimeWhenOverOrNone() {
        // Beim nächsten Zeichnen nach Ablauf: nichts (nie ein negativer Wert)
        assertNull(RetryWait.widgetRetryTime(now, now))
        assertNull(RetryWait.widgetRetryTime(now - 5_000, now))
        assertNull(RetryWait.widgetRetryTime(0L, now))
    }

    @Test
    fun widgetLayoutHasNoCountdown() {
        val path = listOf("", "app/", "android/app/").map { "${it}src/main/res/layout/widget_list.xml" }
            .firstOrNull { java.io.File(it).isFile }
        val layout = java.io.File(SourceGuard.android(path, "widget_list.xml")).readText()
        // Uhrzeit des Versuchs als einfacher Text, nicht als herunterzählender Chronometer
        assertTrue(Regex("""<TextView\s+[^>]*android:id="@\+id/widget_retry_at"""").containsMatchIn(layout))
        assertFalse(layout.contains("widget_retry_countdown"))
    }

    // ── Bericht ─────────────────────────────────────────────────────────────

    @Test
    fun attemptNumberStartsAtOne() {
        assertEquals(1, RetryWait.attemptNumber(0))
        assertEquals(2, RetryWait.attemptNumber(1))
    }

    @Test
    fun attemptNoteOnlyAfterFirst() {
        assertFalse(RetryWait.showsAttemptNote(null))
        assertFalse(RetryWait.showsAttemptNote(1))
        assertTrue(RetryWait.showsAttemptNote(2))
    }
}
