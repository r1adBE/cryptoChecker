package com.cryptochecker.app.domain.convert

import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.convert.CurrencyConversion.QuoteKind
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

/**
 * Schranken für Alarme mit Schwelle in einer anderen Währung (Kurs × Faktor, «Fiat-Alarme») und Hinweise an der
 * ≈ Umrechnung — reine Logik, gleich wie `FiatAlarmGuard.swift`.
 *
 *  - Devisenkurs zu alt: Die EZB veröffentlicht nur an Bankarbeitstagen (TARGET2). Gezählt wird das Alter des Kurses
 *    (Veröffentlichung, nicht Abruf) ohne Samstage, Sonntage und TARGET-Feiertage; über [FX_MAX_AGE_MILLIS] prüft ein
 *    Fiat-Alarm in diesem Durchlauf nicht (bleibt scharf), wie bei einem veralteten Kurs.
 *  - USDT/USD unsicher: Liegt nur der Wert über Binance USDC/USDT vor ([DepegAlarm.Reading.viaUsdc]) und weicht USDC/USDT
 *    um mehr als [USDC_MAX_DEVIATION] von 1 ab, ist offen, ob USDT oder USDC abweicht — Fiat-Alarme prüfen nicht, die
 *    Anzeige rechnet mit dem Wert weiter und zeigt «Umrechnung unsicher».
 *  - Ohne Messung gilt USDT = 1 ([UsdtState.ASSUMED]): Vermerk «USDT = 1 angenommen» an Zeile bzw. Alarmtext.
 */
object FiatAlarmGuard {

    private const val HOUR_MILLIS = 60 * 60_000L
    private const val DAY_MILLIS = 24 * HOUR_MILLIS

    /** Höchstalter des Devisenkurses in Bankarbeitszeit (ohne Wochenende und TARGET-Feiertage): 36 h. */
    const val FX_MAX_AGE_MILLIS = 36 * HOUR_MILLIS

    /** USDC/USDT weiter als 1 % von 1 entfernt: USDT/USD allein über USDC ist unsicher. */
    const val USDC_MAX_DEVIATION = 0.01

    /** Ab so vielen Kalendertagen wird nicht mehr Tag für Tag gezählt (sicher zu alt). */
    private const val MAX_COUNTED_DAYS = 60L

    /** Uhrzeit (UTC), zu der ein Tageskurs mit Datum gilt: EZB-Veröffentlichung um etwa 16:00 MEZ. */
    const val ECB_PUBLISH_HOUR_UTC = 15

    /** Zeitpunkt eines Tageskurses mit Datum [isoDate] («2026-10-16»): an dem Tag um [ECB_PUBLISH_HOUR_UTC] UTC; null bei Unsinn. */
    fun rateTimeOf(isoDate: String?): Long? {
        val day = runCatching { LocalDate.parse(isoDate?.trim().orEmpty()) }.getOrNull() ?: return null
        return day.toEpochDay() * DAY_MILLIS + ECB_PUBLISH_HOUR_UTC * HOUR_MILLIS
    }

    /** Bankarbeitstag der EZB (TARGET2): nicht Samstag/Sonntag, nicht 1.1., Karfreitag, Ostermontag, 1.5., 25./26.12. */
    fun isBusinessDay(day: LocalDate): Boolean {
        if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) return false
        val m = day.monthValue
        val d = day.dayOfMonth
        if ((m == 1 && d == 1) || (m == 5 && d == 1) || (m == 12 && (d == 25 || d == 26))) return false
        val easter = easterSunday(day.year)
        return day != easter.minusDays(2) && day != easter.plusDays(1)
    }

    /** Ostersonntag (gregorianisch, anonymer Algorithmus nach Meeus/Jones/Butcher). */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val dayOfMonth = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, dayOfMonth)
    }

    /** Zeit zwischen [rateTime] und [now], nur an Bankarbeitstagen (UTC) gezählt; 0, wenn [now] davor liegt. */
    fun businessAgeMillis(rateTime: Long, now: Long): Long {
        if (now <= rateTime) return 0L
        val first = Math.floorDiv(rateTime, DAY_MILLIS)
        val last = Math.floorDiv(now, DAY_MILLIS)
        if (last - first > MAX_COUNTED_DAYS) return now - rateTime
        var age = 0L
        for (day in first..last) {
            if (!isBusinessDay(LocalDate.ofEpochDay(day))) continue
            val start = maxOf(rateTime, day * DAY_MILLIS)
            val end = minOf(now, (day + 1) * DAY_MILLIS)
            if (end > start) age += end - start
        }
        return age
    }

    /** Devisenkurs mit Zeitpunkt [rateTime] für einen Fiat-Alarm zu alt? null = kein Devisenkurs beteiligt (nie zu alt). */
    fun fxTooOld(rateTime: Long?, now: Long): Boolean =
        rateTime != null && businessAgeMillis(rateTime, now) > FX_MAX_AGE_MILLIS

    /**
     * Hängt der Faktor [from] → [to] an USDT/USD? Ja, wenn die Quote USDT, ein anderer Stablecoin (Marktkurs in USDT)
     * oder Krypto (über USDT bewertet) ist, oder das Ziel USDT — wie [CurrencyConversion.quoteToUsd] rechnet.
     */
    fun involvesUsdt(from: String, to: String, fiat: Collection<String>): Boolean {
        if (CurrencyConversion.sameCurrency(from, to)) return false
        val source = CurrencyConversion.normalize(from)
        if (CurrencyConversion.normalize(to) == USDT) return true
        return when (CurrencyConversion.classify(source, fiat)) {
            QuoteKind.USD -> source != "USD"
            QuoteKind.CRYPTO -> true
            QuoteKind.FIAT, QuoteKind.INVALID -> false
        }
    }

    /**
     * USDT/USD messen, weil eine Fiat-Umrechnung daran hängt ([DepegAlarm.shouldMeasure])? Ja mit eingeschalteter
     * ≈ Umrechnung ([showConverted]) in [displayCurrency] auf einer der Quotes [watchQuotes], oder mit einem Alarm in
     * anderer Währung ([alarmConversions]: Quote des Paars → Alarmwährung), dessen Faktor an USDT hängt ([involvesUsdt]).
     */
    fun needsUsdtMeasurement(
        showConverted: Boolean,
        displayCurrency: String,
        watchQuotes: Collection<String>,
        alarmConversions: Collection<Pair<String, String>>,
        fiat: Collection<String>,
    ): Boolean =
        (showConverted && watchQuotes.any { involvesUsdt(it, displayCurrency, fiat) }) ||
            alarmConversions.any { (quote, currency) -> involvesUsdt(quote, currency, fiat) }

    /** Wie USDT/USD in einen Faktor eingeht ([usdtState]). */
    enum class UsdtState {
        /** USDT nicht beteiligt. */
        NOT_INVOLVED,

        /** Frische Messung (direkt oder über USDC ohne grössere Abweichung). */
        MEASURED,

        /** Keine frische Messung: USDT = 1 angenommen (Vermerk). */
        ASSUMED,

        /** Nur über USDC/USDT gemessen und USDC/USDT mehr als 1 % neben 1: «Umrechnung unsicher». */
        UNSURE,
    }

    /** Stand von USDT/USD für einen Faktor, an dem USDT [involved] ist; [reading] = letzte Messung (Depeg-Prüfung). */
    fun usdtState(involved: Boolean, reading: DepegAlarm.Reading?, now: Long): UsdtState {
        if (!involved) return UsdtState.NOT_INVOLVED
        val live = DepegAlarm.liveUsdtUsd(reading, now) ?: return UsdtState.ASSUMED
        // Gemessen wurde USDT/USD = 1 / (USDC/USDT); USDC/USDT also = 1 / live
        val unsure = reading?.viaUsdc == true && abs(1.0 / live - 1.0) > USDC_MAX_DEVIATION
        return if (unsure) UsdtState.UNSURE else UsdtState.MEASURED
    }

    /** Warum ein Fiat-Alarm in diesem Durchlauf nicht prüft. */
    enum class Skip { FX_STALE, UNSURE }

    /** Auslassen? Zuerst der zu alte Devisenkurs, dann die unsichere USDT-Umrechnung; null = prüfen. */
    fun skip(fxRateTime: Long?, usdt: UsdtState, now: Long): Skip? = when {
        fxTooOld(fxRateTime, now) -> Skip.FX_STALE
        usdt == UsdtState.UNSURE -> Skip.UNSURE
        else -> null
    }

    private const val USDT = "USDT"
}
