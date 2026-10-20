package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.refresh.WidgetRefreshState.Tap
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRefreshStateTest {

    private val now = 1_000_000_000L
    private val window = RefreshDebounce.WINDOW_MILLIS

    @Test
    fun tapWhileRunningDoesNothing() {
        assertEquals(Tap.Running, WidgetRefreshState.onTap(running = true, lastFinishedAt = now - 2_000, now = now))
        assertEquals(Tap.Running, WidgetRefreshState.onTap(running = true, lastFinishedAt = 0, now = now))
    }

    @Test
    fun tapWithinWindowIsBlockedUntilWindowEnd() {
        assertEquals(Tap.Blocked(now - 4_000 + window), WidgetRefreshState.onTap(false, now - 4_000, now))
        assertEquals(Tap.Blocked(now + window), WidgetRefreshState.onTap(false, now, now))
    }

    @Test
    fun tapAfterWindowOrWithoutRefreshStarts() {
        assertEquals(Tap.Start, WidgetRefreshState.onTap(false, 0, now))
        assertEquals(Tap.Start, WidgetRefreshState.onTap(false, now - window, now))
        // Uhr zurückgestellt: letzte Aktualisierung «in der Zukunft» sperrt nicht
        assertEquals(Tap.Start, WidgetRefreshState.onTap(false, now + 5_000, now))
    }

    @Test
    fun countdownRightAfterRefreshWithoutTap() {
        // Eben fertig, nie getippt: Countdown bis zum Ende des Fensters
        assertEquals(now - 2_000 + window, WidgetRefreshState.blockedUntil(0, now - 2_000, 0, now))
        // Fenster vorbei bzw. noch nie aktualisiert: Knopf
        assertEquals(0L, WidgetRefreshState.blockedUntil(0, now - window, 0, now))
        assertEquals(0L, WidgetRefreshState.blockedUntil(0, 0, 0, now))
        // Uhr zurückgestellt (letzte «in der Zukunft»): Knopf
        assertEquals(0L, WidgetRefreshState.blockedUntil(0, now + 5_000, 0, now))
    }

    @Test
    fun laterOfStoredAndWindowEnd() {
        val last = now - 4_000
        assertEquals(last + window, WidgetRefreshState.blockedUntil(last + window - 3_000, last, 0, now))
        assertEquals(now + 13_000, WidgetRefreshState.blockedUntil(now + 13_000, last, 0, now))
        // Gespeichertes Ende allein (z. B. nach einer Aktualisierung ohne Kurs) gilt weiter
        assertEquals(now + 6_000, WidgetRefreshState.blockedUntil(now + 6_000, now - 60_000, 0, now))
        // Ungültiges gespeichertes Ende (Uhr verstellt) verdrängt das gültige nicht
        assertEquals(last + window, WidgetRefreshState.blockedUntil(now + window + 5_000, last, 0, now))
        assertEquals(0L, WidgetRefreshState.blockedUntil(now - 1, now - 60_000, 0, now))
    }

    @Test
    fun noCountdownWhileNewRefreshRuns() {
        val last = now - 4_000
        // Nach der letzten fertigen begonnen: läuft — Knopf statt Countdown
        assertEquals(0L, WidgetRefreshState.blockedUntil(now + 9_000, last, now - 1_000, now))
        assertEquals(0L, WidgetRefreshState.blockedUntil(0, 0, now - 1_000, now))
        // Zeichnen am Ende der Aktualisierung (Zeit schon gesetzt, Sperre noch gehalten): Countdown beginnt
        assertEquals(now + window, WidgetRefreshState.blockedUntil(0, now, now - 3_000, now))
    }

    @Test
    fun remainingMillisWithinWindowOnly() {
        assertEquals(0L, WidgetRefreshState.remainingMillis(0, now))
        assertEquals(10_000L, WidgetRefreshState.remainingMillis(now + 10_000, now))
        assertEquals(window, WidgetRefreshState.remainingMillis(now + window, now))
        assertEquals(1L, WidgetRefreshState.remainingMillis(now + 1, now))
        // Ende erreicht oder vorbei: Knopf wieder zeigen
        assertEquals(0L, WidgetRefreshState.remainingMillis(now, now))
        assertEquals(0L, WidgetRefreshState.remainingMillis(now - 1, now))
        // Uhr zurückgestellt: nie länger als ein Fenster
        assertEquals(0L, WidgetRefreshState.remainingMillis(now + window + 1, now))
    }

    @Test
    fun remainingSecondsRoundUp() {
        assertEquals(15, WidgetRefreshState.remainingSeconds(now + window, now))
        assertEquals(15, WidgetRefreshState.remainingSeconds(now + 14_200, now))
        assertEquals(1, WidgetRefreshState.remainingSeconds(now + 1, now))
        assertEquals(0, WidgetRefreshState.remainingSeconds(now, now))
    }

    @Test
    fun blockedStateMatchesAppCountdown() {
        val last = now - 3_300
        val until = (WidgetRefreshState.onTap(false, last, now) as Tap.Blocked).until
        for (t in listOf(now, now + 700, now + 5_000, now + 11_699, now + 11_700, now + 20_000)) {
            assertEquals(RefreshDebounce.remainingSeconds(last, t), WidgetRefreshState.remainingSeconds(until, t))
        }
    }

    @Test
    fun chronometerShowsRoundedUpSeconds() {
        val elapsed = 50_000L
        val rest = 14_200L
        val base = WidgetRefreshState.chronometerBase(elapsed, rest)
        // Chronometer (Countdown): ganze Sekunden bis zur Basis, abgeschnitten
        fun shown(at: Long) = (base - at) / 1000
        assertEquals(15L, shown(elapsed))
        assertEquals(1L, shown(elapsed + rest - 1))
        assertEquals(0L, shown(elapsed + rest))
        for (t in 0L..rest step 100) {
            assertEquals(WidgetRefreshState.remainingSeconds(now + rest, now + t).toLong(), shown(elapsed + t))
        }
    }

    @Test
    fun countdownOnlyWithScreenOn() {
        assertEquals(12_000L, WidgetRefreshState.countdownMillis(12_000L, screenOn = true))
        // Bildschirm aus: gleich das Symbol, kein Countdown, der nach dem Entsperren bei 0 stehen bliebe
        assertEquals(0L, WidgetRefreshState.countdownMillis(12_000L, screenOn = false))
        assertEquals(0L, WidgetRefreshState.countdownMillis(0L, screenOn = true))
    }

    @Test
    fun redrawAfterTheLockShowsTheButton() {
        val until = now + 15_000
        // Neuzeichnen mit Nachlauf: die Sperre ist dann vorbei (Restzeit 0 = Symbol)
        assertEquals(0L, WidgetRefreshState.remainingMillis(until, until + WidgetRefreshState.UNLOCK_SLACK_MILLIS))
    }
}
