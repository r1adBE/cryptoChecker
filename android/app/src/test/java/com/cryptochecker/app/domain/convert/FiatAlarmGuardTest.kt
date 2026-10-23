package com.cryptochecker.app.domain.convert

import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Gemeinsame Fälle mit iOS (`FiatAlarmGuardTests.swift`, `testdata/parity/fiat_alarm_guard.json`). */
class FiatAlarmGuardTest {

    private val data = ParityJson.load("fiat_alarm_guard.json")

    private fun cases(key: String) = data[key].list().also { check(it.isNotEmpty()) }.map { it.obj() }

    @Test
    fun rateTimeOfEcbDate() {
        cases("rateTimeOf").forEach { c ->
            assertEquals("rateTimeOf: ${c["name"]}", c["expected"].num()?.toLong(), FiatAlarmGuard.rateTimeOf(c["date"] as String?))
        }
    }

    @Test
    fun businessDaysSkipWeekendAndTargetHolidays() {
        cases("businessDay").forEach { c ->
            val day = LocalDate.ofEpochDay(c["epochDay"].num()!!.toLong())
            assertEquals("businessDay: ${c["name"]}", c["expected"] as Boolean, FiatAlarmGuard.isBusinessDay(day))
        }
        assertEquals(LocalDate.of(2026, 4, 5), FiatAlarmGuard.easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), FiatAlarmGuard.easterSunday(2027))
    }

    @Test
    fun fxTooOldCountsBusinessHoursOnly() {
        cases("fxTooOld").forEach { c ->
            val actual = FiatAlarmGuard.fxTooOld(c["rateTime"].num()?.toLong(), c["now"].num()!!.toLong())
            assertEquals("fxTooOld: ${c["name"]}", c["expected"] as Boolean, actual)
        }
    }

    /** Echte Fiat-Währung ohne Devisenkurs (NGN, ARS …) = FIAT_UNSUPPORTED, nie Krypto über USDT. */
    @Test
    fun quoteKindKnowsFiatWithoutFxRate() {
        cases("classify").forEach { c ->
            val actual = CurrencyConversion.classify(c["quote"] as String, FxRateSource.CURRENCIES)
            assertEquals("classify: ${c["name"]}", c["expected"] as String, actual.name)
        }
        // Keine Umrechnung, auch mit Kursen
        assertNull(CurrencyConversion.quoteToUsd(CurrencyConversion.QuoteKind.FIAT_UNSUPPORTED, 1500.0, 0.0007))
    }

    @Test
    fun involvesUsdtFollowsConversionRule() {
        cases("involvesUsdt").forEach { c ->
            val actual = FiatAlarmGuard.involvesUsdt(c["from"] as String, c["to"] as String, FxRateSource.CURRENCIES)
            assertEquals("involvesUsdt: ${c["name"]}", c["expected"] as Boolean, actual)
        }
    }

    @Test
    fun usdtStateAssumedMeasuredOrUnsure() {
        cases("usdtState").forEach { c ->
            val reading = c["reading"]?.obj()?.let {
                DepegAlarm.Reading(it["price"].num()!!, it["at"].num()!!.toLong(), it["viaUsdc"] as Boolean)
            }
            val actual = FiatAlarmGuard.usdtState(c["involved"] as Boolean, reading, c["now"].num()!!.toLong())
            assertEquals("usdtState: ${c["name"]}", c["expected"] as String, actual.name)
        }
    }

    @Test
    fun needsUsdtMeasurementForFiatConversionOverUsdt() {
        cases("needsMeasurement").forEach { c ->
            val alarms = c["alarms"].list().map { val pair = it.list(); pair[0] as String to pair[1] as String }
            val actual = FiatAlarmGuard.needsUsdtMeasurement(
                c["showConverted"] as Boolean, c["displayCurrency"] as String,
                c["watchQuotes"].list().map { it as String }, alarms, FxRateSource.CURRENCIES,
            )
            assertEquals("needsMeasurement: ${c["name"]}", c["expected"] as Boolean, actual)
        }
    }

    @Test
    fun skipFxStaleBeforeUnsure() {
        cases("skip").forEach { c ->
            val actual = FiatAlarmGuard.skip(
                c["rateTime"].num()?.toLong(), FiatAlarmGuard.UsdtState.valueOf(c["usdt"] as String), c["now"].num()!!.toLong(),
            )
            assertEquals("skip: ${c["name"]}", c["expected"] as String?, actual?.name)
        }
    }
}
