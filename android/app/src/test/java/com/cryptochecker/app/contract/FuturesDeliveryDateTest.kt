package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.FuturesContractType.BIMONTHLY
import com.cryptochecker.marketdata.model.FuturesContractType.BIQUARTERLY
import com.cryptochecker.marketdata.model.FuturesContractType.BIWEEKLY
import com.cryptochecker.marketdata.model.FuturesContractType.MONTHLY
import com.cryptochecker.marketdata.model.FuturesContractType.QUARTERLY
import com.cryptochecker.marketdata.model.FuturesContractType.WEEKLY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Verfallstag nach Kalender ([FuturesContractType.getDeliveryDate]): Abrechnung freitags 08:00 UTC, danach gilt der nächste
 * Kontrakt — auch für Monat und Quartal, wenn ihr letzter Freitag vorbei ist. Swift: `FuturesDeliveryDateTests`.
 */
class FuturesDeliveryDateTest {

    private fun delivery(type: FuturesContractType, now: String): LocalDate? =
        FuturesContractType.getDeliveryDate(type, Instant.parse(now))

    private fun date(text: String) = LocalDate.parse(text)

    @Test
    fun `mid week every contract lies ahead`() {
        val now = "2026-10-21T12:00:00Z" // Mittwoch
        assertEquals(date("2026-10-23"), delivery(WEEKLY, now))
        assertEquals(date("2026-10-30"), delivery(BIWEEKLY, now))
        assertEquals(date("2026-10-30"), delivery(MONTHLY, now))
        assertEquals(date("2026-11-27"), delivery(BIMONTHLY, now))
        assertEquals(date("2026-12-25"), delivery(QUARTERLY, now))
        assertEquals(date("2027-03-26"), delivery(BIQUARTERLY, now))
    }

    @Test
    fun `last friday of the month already passed moves to next month`() {
        // Samstag nach dem letzten Freitag im Oktober: bis Build 91 noch der 30. Oktober
        val now = "2026-10-31T00:00:00Z"
        assertEquals(date("2026-11-27"), delivery(MONTHLY, now))
        assertEquals(date("2026-12-25"), delivery(BIMONTHLY, now))
        assertEquals(date("2026-11-06"), delivery(WEEKLY, now))
        assertEquals(date("2026-12-25"), delivery(QUARTERLY, now))
    }

    @Test
    fun `on expiry friday the contract rolls at 08_00 UTC`() {
        assertEquals(date("2026-10-30"), delivery(WEEKLY, "2026-10-30T07:59:59Z"))
        assertEquals(date("2026-10-30"), delivery(MONTHLY, "2026-10-30T07:59:59Z"))
        assertEquals(date("2026-11-06"), delivery(WEEKLY, "2026-10-30T08:00:00Z"))
        assertEquals(date("2026-11-13"), delivery(BIWEEKLY, "2026-10-30T08:00:00Z"))
        assertEquals(date("2026-11-27"), delivery(MONTHLY, "2026-10-30T08:00:00Z"))
        assertEquals(date("2026-12-25"), delivery(BIMONTHLY, "2026-10-30T08:00:00Z"))
    }

    @Test
    fun `quarter rolls after its last friday`() {
        val now = "2026-12-25T08:00:00Z"
        assertEquals(date("2027-03-26"), delivery(QUARTERLY, now))
        assertEquals(date("2027-06-25"), delivery(BIQUARTERLY, now))
        assertEquals(date("2027-01-29"), delivery(MONTHLY, now))
        // Kurz davor noch das laufende Quartal
        assertEquals(date("2026-12-25"), delivery(QUARTERLY, "2026-12-25T07:00:00Z"))
    }

    @Test
    fun `spot and perpetuals have no delivery`() {
        val now = "2026-10-21T12:00:00Z"
        assertNull(delivery(FuturesContractType.NONE, now))
        assertNull(delivery(FuturesContractType.PERPETUAL, now))
        assertNull(delivery(FuturesContractType.INVERSE_PERPETUAL, now))
    }
}
