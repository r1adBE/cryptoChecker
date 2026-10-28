package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.refresh.ExchangeBackoff.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExchangeBackoffTest {

    private val now = 1_000_000L

    @Test
    fun delaysDoubleUpToFifteenMinutes() {
        assertEquals(30_000L, ExchangeBackoff.delayMillis(1))
        assertEquals(60_000L, ExchangeBackoff.delayMillis(2))
        assertEquals(120_000L, ExchangeBackoff.delayMillis(3))
        assertEquals(240_000L, ExchangeBackoff.delayMillis(4))
        assertEquals(480_000L, ExchangeBackoff.delayMillis(5))
        assertEquals(900_000L, ExchangeBackoff.delayMillis(6))
        assertEquals(900_000L, ExchangeBackoff.delayMillis(60))
    }

    @Test
    fun rateLimitPausesAndGrows() {
        val first = ExchangeBackoff.next(null, Outcome.RATE_LIMITED, now)!!
        assertEquals(now + 30_000L, first.pausedUntil)
        assertEquals(RefreshFailure.RATE_LIMIT, first.reason)
        assertTrue(ExchangeBackoff.isPaused(first, now + 29_999L))
        assertFalse(ExchangeBackoff.isPaused(first, now + 30_000L))
        val second = ExchangeBackoff.next(first, Outcome.RATE_LIMITED, now + 30_000L)!!
        assertEquals(now + 30_000L + 60_000L, second.pausedUntil)
        assertEquals(2, second.strikes)
    }

    @Test
    fun retryAfterIsHonouredAndCapped() {
        assertEquals(now + 90_000L, ExchangeBackoff.next(null, Outcome.RATE_LIMITED, now, 90_000L)!!.pausedUntil)
        // Kürzer als die eigene Stufe: die Stufe gilt
        assertEquals(now + 30_000L, ExchangeBackoff.next(null, Outcome.RATE_LIMITED, now, 5_000L)!!.pausedUntil)
        // Länger als 15 Min.: gekürzt
        assertEquals(now + 900_000L, ExchangeBackoff.next(null, Outcome.RATE_LIMITED, now, 3_600_000L)!!.pausedUntil)
    }

    @Test
    fun successResets() {
        val paused = ExchangeBackoff.next(null, Outcome.RATE_LIMITED, now)
        assertNull(ExchangeBackoff.next(paused, Outcome.SUCCESS, now + 60_000L))
    }

    @Test
    fun repeatedTimeoutsPause() {
        val once = ExchangeBackoff.next(null, Outcome.TIMEOUT, now)!!
        assertFalse(ExchangeBackoff.isPaused(once, now))
        assertEquals(1, once.timeoutRuns)
        val twice = ExchangeBackoff.next(once, Outcome.TIMEOUT, now + 60_000L)!!
        assertTrue(ExchangeBackoff.isPaused(twice, now + 60_000L))
        assertEquals(RefreshFailure.TIMEOUT, twice.reason)
        assertEquals(now + 60_000L + 30_000L, twice.pausedUntil)
        // Anderer Fehler dazwischen unterbricht die Folge
        val broken = ExchangeBackoff.next(once, Outcome.OTHER, now)
        assertNull(broken)
    }

    @Test
    fun outcomeFromFailures() {
        assertEquals(Outcome.RATE_LIMITED, ExchangeBackoff.outcome(listOf(RefreshFailure.RATE_LIMIT), updated = 3))
        assertEquals(Outcome.SUCCESS, ExchangeBackoff.outcome(listOf(RefreshFailure.TIMEOUT), updated = 1))
        assertEquals(Outcome.SUCCESS, ExchangeBackoff.outcome(emptyList(), updated = 0))
        assertEquals(Outcome.TIMEOUT, ExchangeBackoff.outcome(listOf(RefreshFailure.TIMEOUT, RefreshFailure.TIMEOUT), 0))
        assertEquals(Outcome.OTHER, ExchangeBackoff.outcome(listOf(RefreshFailure.TIMEOUT, RefreshFailure.SERVER), 0))
    }

    @Test
    fun retryAfterParsing() {
        assertEquals(30L, ExchangeBackoff.parseRetryAfterSeconds(" 30 ", now))
        assertNull(ExchangeBackoff.parseRetryAfterSeconds(null, now))
        assertNull(ExchangeBackoff.parseRetryAfterSeconds("soon", now))
        // HTTP-Datum: 2 Min. nach «jetzt»
        val base = 1_791_000_000_000L // 2026-10-03T04:00:00Z (Samstag)
        assertEquals(120L, ExchangeBackoff.parseRetryAfterSeconds("Sat, 3 Oct 2026 04:02:00 GMT", base))
        assertEquals(" (retry-after 30 s)", ExchangeBackoff.retryAfterSuffix(30L))
        assertEquals("", ExchangeBackoff.retryAfterSuffix(null))
        assertEquals(
            45_000L,
            ExchangeBackoff.retryAfterMillis(listOf(null, "HttpCode: 429 (retry-after 30 s)", "HTTP 429 (retry-after 45 s)"))
        )
        assertNull(ExchangeBackoff.retryAfterMillis(listOf("HttpCode: 500")))
        // Der Code bleibt für den Bericht lesbar
        assertEquals(RefreshFailure.RATE_LIMIT, RefreshReportLogic.classify("HttpCode: 429 (retry-after 30 s)"))
    }

    @Test
    fun hugeRetryAfterIsClampedToOneDay() {
        val max = ExchangeBackoff.MAX_RETRY_AFTER_SECONDS
        assertEquals(max, ExchangeBackoff.parseRetryAfterSeconds(Long.MAX_VALUE.toString(), now))
        assertEquals(max, ExchangeBackoff.parseRetryAfterSeconds("Fri, 31 Dec 9999 23:59:59 GMT", now))
        assertEquals(0L, ExchangeBackoff.parseRetryAfterSeconds("-5", now))
        // Gespeicherter Fehlertext mit riesiger Angabe: kein stiller Überlauf
        assertEquals(max * 1000L, ExchangeBackoff.retryAfterMillis(listOf("HTTP 429 (retry-after ${Long.MAX_VALUE} s)")))
    }

    @Test
    fun encodeRoundTrip() {
        val states = mapOf(
            "binance" to ExchangeBackoff.State(2, 123L, RefreshFailure.RATE_LIMIT, 0),
            "kraken" to ExchangeBackoff.State(0, 0L, null, 1),
        )
        assertEquals(states, ExchangeBackoff.decode(ExchangeBackoff.encode(states)))
        // Gleiches Format wie iOS
        assertEquals("binance|2|123|RATE_LIMIT|0;kraken|0|0||1", ExchangeBackoff.encode(states))
        assertEquals(emptyMap<String, ExchangeBackoff.State>(), ExchangeBackoff.decode("x|y;;"))
        assertEquals(emptyMap<String, ExchangeBackoff.State>(), ExchangeBackoff.decode(null))
    }

    @Test
    fun appStartTiming() {
        // Kalt: ab Prozessstart
        assertEquals(400L, AppStartTiming.elapsed(processStart = 1_000L, uiCreated = 1_150L, firstFrame = 1_400L))
        // Prozess lief schon (Widget): ab Aufbau der Oberfläche
        assertEquals(300L, AppStartTiming.elapsed(processStart = 1_000L, uiCreated = 60_000L, firstFrame = 60_300L))
        assertEquals(250L, AppStartTiming.elapsed(processStart = null, uiCreated = 100L, firstFrame = 350L))
        assertNull(AppStartTiming.elapsed(processStart = null, uiCreated = 100L, firstFrame = 50L))
        assertNull(AppStartTiming.elapsed(processStart = null, uiCreated = 0L, firstFrame = 120_000L))
    }

    @Test
    fun offlineResumesOnceOnReconnect() {
        assertTrue(OfflineGate.resumeOnChange(previous = false, online = true))
        assertFalse(OfflineGate.resumeOnChange(previous = null, online = true))
        assertFalse(OfflineGate.resumeOnChange(previous = true, online = true))
        assertFalse(OfflineGate.resumeOnChange(previous = true, online = false))
    }

    // ---------------- Gemeinsame Binance-Pause ----------------

    @Test
    fun binanceHostsAndExchangesShareOneGroup() {
        for (key in listOf("api.binance.com", "data-api.binance.vision", "fapi.binance.com", "dapi.binance.com", "www.binance.com", "Binance", "BinanceFutures")) {
            assertEquals(key, ExchangeBackoff.BINANCE_GROUP, ExchangeBackoff.sharedGroup(key))
        }
        // Binance.US ist eine eigene Börse; andere Hosts ohne gemeinsame Pause
        assertNull(ExchangeBackoff.sharedGroup("api.binance.us"))
        assertNull(ExchangeBackoff.sharedGroup("Binance.US"))
        assertNull(ExchangeBackoff.sharedGroup("api.bybit.com"))
        assertTrue(ExchangeBackoff.isRateLimitCode(429))
        assertTrue(ExchangeBackoff.isRateLimitCode(418))
        assertFalse(ExchangeBackoff.isRateLimitCode(451))
    }

    @Test
    fun groupPauseHonoursRetryAfterBeyondTickerCap() {
        // Ohne Retry-After: Stufe 1 = 30 s
        val plain = ExchangeBackoff.groupPause(null, now, 429, null)
        assertEquals(now + 30_000L, plain.pausedUntil)
        assertEquals(RefreshFailure.RATE_LIMIT, plain.reason)
        // Retry-After gilt (auch über 15 Min.), höchstens 6 h
        assertEquals(now + 3_600_000L, ExchangeBackoff.groupPause(null, now, 429, 3_600L).pausedUntil)
        assertEquals(now + ExchangeBackoff.GROUP_MAX_MILLIS, ExchangeBackoff.groupPause(null, now, 418, 999_999L).pausedUntil)
        // 418 ohne Retry-After: mindestens 2 Min.
        assertEquals(now + 120_000L, ExchangeBackoff.groupPause(null, now, 418, null).pausedUntil)
    }

    @Test
    fun groupPauseNeverShortensAndParallelHitsDoNotEscalate() {
        val first = ExchangeBackoff.groupPause(null, now, 429, 600L)
        // Weitere 429 parallel gestarteter Anfragen während der Pause: Stufe bleibt, Ende bleibt
        val again = ExchangeBackoff.groupPause(first, now + 1_000L, 429, null)
        assertEquals(1, again.strikes)
        assertEquals(first.pausedUntil, again.pausedUntil)
        assertTrue(ExchangeBackoff.isPaused(again, now + 599_999L))
    }

    @Test
    fun groupPauseEscalatesAfterPauseAndResetsWhenLongAgo() {
        val first = ExchangeBackoff.groupPause(null, now, 429, null)
        val afterPause = first.pausedUntil + 1_000L
        val second = ExchangeBackoff.groupPause(first, afterPause, 429, null)
        assertEquals(2, second.strikes)
        assertEquals(afterPause + 60_000L, second.pausedUntil)
        // Letzte Pause endete vor über 15 Min.: neu ab Stufe 1
        val later = second.pausedUntil + ExchangeBackoff.MAX_MILLIS + 1L
        val fresh = ExchangeBackoff.groupPause(second, later, 429, null)
        assertEquals(1, fresh.strikes)
        assertEquals(later + 30_000L, fresh.pausedUntil)
    }

    @Test
    fun tickerBanAndRetryAfterMatchCandles() {
        // 418 beim Ticker: mindestens 2 Min. wie bei Kerzen (groupPause), nicht 30 s
        assertEquals(now + 120_000L, ExchangeBackoff.next(null, Outcome.RATE_LIMITED, now, banned = true)!!.pausedUntil)
        assertEquals(ExchangeBackoff.groupPause(null, now, 418, null).pausedUntil,
            ExchangeBackoff.next(null, Outcome.RATE_LIMITED, now, banned = true, shared = true)!!.pausedUntil)
        // Gemeinsame Gruppe (Binance): Retry-After bis 6 h wie bei Kerzen
        assertEquals(now + 3_600_000L,
            ExchangeBackoff.next(null, Outcome.RATE_LIMITED, now, 3_600_000L, shared = true)!!.pausedUntil)
        assertEquals(ExchangeBackoff.groupPause(null, now, 429, 3_600L).pausedUntil,
            ExchangeBackoff.next(null, Outcome.RATE_LIMITED, now, 3_600_000L, shared = true)!!.pausedUntil)
        // Laufende längere Pause wird nie verkürzt
        val running = ExchangeBackoff.State(1, now + 600_000L, RefreshFailure.RATE_LIMIT, 0)
        assertEquals(now + 600_000L, ExchangeBackoff.next(running, Outcome.RATE_LIMITED, now)!!.pausedUntil)
        // 418 aus den Fehlertexten beider Plattformen erkannt
        assertTrue(ExchangeBackoff.banned(listOf(null, "HttpCode: 418 (retry-after 120 s)")))
        assertTrue(ExchangeBackoff.banned(listOf("HTTP 418")))
        assertFalse(ExchangeBackoff.banned(listOf("HttpCode: 429", null)))
    }
}
