package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Haltedauer in Kalendertagen der gewählten Zeitzone ([PortfolioFifo.holdingDays], wie die Steuer-Liste). */
class PortfolioHoldingDaysTest {

    private fun at(text: String, zone: ZoneId) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone).toInstant().toEpochMilli()

    @Test
    fun utcDefaultMatchesCalendarDaysInUtc() {
        val from = at("2026-01-01T23:30:00", ZoneOffset.UTC)
        val to = at("2026-01-02T00:30:00", ZoneOffset.UTC)
        assertEquals(1L, PortfolioFifo.holdingDays(from, to))
    }

    @Test
    fun zoneDecidesTheCalendarDay() {
        val zurich = ZoneId.of("Europe/Zurich")
        // 00:30 und 23:30 Ortszeit am selben Tag — in UTC zwei Tage (23:30 UTC am Vortag, 22:30 UTC)
        val from = at("2026-01-02T00:30:00", zurich)
        val to = at("2026-01-02T23:30:00", zurich)
        assertEquals(1L, PortfolioFifo.holdingDays(from, to))
        assertEquals(0L, PortfolioFifo.holdingDays(from, to, zurich))
    }
}
