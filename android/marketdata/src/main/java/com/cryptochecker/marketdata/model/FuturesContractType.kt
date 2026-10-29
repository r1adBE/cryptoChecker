package com.cryptochecker.marketdata.model

import java.time.DayOfWeek.FRIDAY
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Art eines Kontrakts; NONE = Spot. [value] ist die gespeicherte Zahl — Werte nie neu vergeben.
 */
enum class FuturesContractType(val value: Int) {
    NONE(0),
    PERPETUAL(1),
    WEEKLY(2),
    BIWEEKLY(3),
    MONTHLY(4),
    BIMONTHLY(5),
    QUARTERLY(6),
    BIQUARTERLY(7),

    /** In der Basiswährung abgerechnetes Perpetual (Binance COIN-M, OKX «-USD-SWAP», Deribit). */
    INVERSE_PERPETUAL(10);

    /** Linear oder invers: gleiche Abfragen für Funding, Kerzen usw. */
    val isPerpetual: Boolean
        get() = this == PERPETUAL || this == INVERSE_PERPETUAL

    override fun toString(): String = getShortName(this) ?: "None"

    companion object {
        /**
         * Kurzform für Anzeige und Paarnamen; null für Spot.
         */
        @Suppress("SpellCheckingInspection")
        fun getShortName(contractType: FuturesContractType): String? = when (contractType) {
            NONE -> null
            PERPETUAL -> "Perp"
            INVERSE_PERPETUAL -> "InvPerp"
            WEEKLY -> "1W"
            BIWEEKLY -> "2W"
            MONTHLY -> "1M"
            BIMONTHLY -> "2M"
            QUARTERLY -> "1Q"
            BIQUARTERLY -> "2Q"
        }

        /**
         * Erkennt ein inverses Perpetual an der Kennung der Börse: Binance COIN-M «2:BTCUSD_PERP» (ohne Datum),
         * OKX «BTC-USD-SWAP», Deribit «BTC-PERPETUAL» (ohne «_»; linear wäre «SOL_USDC-PERPETUAL») — für die
         * Paarlisten von OKX und Deribit.
         */
        fun isInversePerpetualId(exchangeKey: String, pairId: String?): Boolean {
            val id = pairId?.trim()
            if (id.isNullOrEmpty()) return false
            return when (exchangeKey) {
                "BinanceFutures" -> id.startsWith("2:") && deliveryCodeOf(id) == null
                "OkexFutures" -> id.endsWith("-USD-SWAP")
                "Deribit" -> id.endsWith("-PERPETUAL") && !id.contains('_')
                else -> false
            }
        }

        /**
         * Verfall als «yyMMdd», wenn die Kennung mit «_» und sechs Ziffern endet («BTCUSDT_251226», COIN-M
         * «2:BTCUSD_251226»); sonst null (Perpetual, Kennung ohne Datum). Abfragen richten sich nur danach, nie nach
         * dem Kalender.
         */
        fun deliveryCodeOf(pairId: String?): String? {
            val id = pairId?.trim() ?: return null
            val cut = id.lastIndexOf('_')
            if (cut < 0) return null
            val code = id.substring(cut + 1)
            return code.takeIf { c -> c.length == 6 && c.all { it in '0'..'9' } }
        }

        /**
         * Abrechnung des Kontrakts laut Kennung: Verfallstag aus [deliveryCodeOf] um 08:00 UTC (Binance, OKX), in ms.
         * null ohne Datum in der Kennung oder bei unmöglichem Datum (z. B. Monat 13). iOS: `settlementMillis(of:)`.
         */
        fun settlementMillisOf(pairId: String?): Long? {
            val day = deliveryDayOf(pairId) ?: return null
            return day.atTime(SETTLEMENT_HOUR_UTC, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        }

        /**
         * Kurzform zur Anzeige: Bei Laufzeit-Kontrakten zählt das Datum in der Kennung, nicht die beim Hinzufügen
         * gespeicherte Art — ein als «2Q» gespeichertes BTCUSDT_260327 ist nach der Quartalsabrechnung das laufende
         * Quartal («1Q»). Passt das Datum zu keinem Kontrakt derselben Familie (Woche, Monat, Quartal) oder fehlt es,
         * bleibt [stored]. [now] für Tests; iOS: `FuturesContractType.shown(stored:pairId:now:)`.
         */
        fun shownContract(stored: FuturesContractType, pairId: String?, now: Instant = Instant.now()): FuturesContractType {
            val family = when (stored) {
                WEEKLY, BIWEEKLY -> WEEKLY to BIWEEKLY
                MONTHLY, BIMONTHLY -> MONTHLY to BIMONTHLY
                QUARTERLY, BIQUARTERLY -> QUARTERLY to BIQUARTERLY
                else -> return stored
            }
            val day = deliveryDayOf(pairId) ?: return stored
            return when (day) {
                getDeliveryDate(family.first, now) -> family.first
                getDeliveryDate(family.second, now) -> family.second
                else -> stored
            }
        }

        /** Verfallstag aus der Kennung («yyMMdd» = 20yy); null ohne bzw. mit unmöglichem Datum. */
        private fun deliveryDayOf(pairId: String?): LocalDate? {
            val code = deliveryCodeOf(pairId) ?: return null
            return runCatching {
                LocalDate.of(2000 + code.substring(0, 2).toInt(), code.substring(2, 4).toInt(), code.substring(4, 6).toInt())
            }.getOrNull()
        }

        /**
         * Ungefährer Verfallstag nach Kalender (Freitage, UTC) — nur für die Anzeige und als Ersatz, wenn die
         * Kennung kein Datum trägt. null für Spot und Perpetuals. Abgerechnet wird freitags um 08:00 UTC: Ab dann gilt
         * schon der nächste Kontrakt — auch für Monat und Quartal, wenn ihr letzter Freitag vorbei ist
         * (z. B. am 31. Oktober nicht der 30. Oktober). [now] für Tests; iOS: `FuturesContractType.delivery(asOf:)`.
         */
        fun getDeliveryDate(contractType: FuturesContractType, now: Instant = Instant.now()): LocalDate? {
            val utc = now.atOffset(ZoneOffset.UTC)
            // Erster Tag, an dem ein Kontrakt noch läuft: heute, ab der Abrechnungsstunde erst morgen
            val start = if (utc.hour >= SETTLEMENT_HOUR_UTC) utc.toLocalDate().plusDays(1) else utc.toLocalDate()
            return when (contractType) {
                NONE, PERPETUAL, INVERSE_PERPETUAL -> null
                WEEKLY -> fridayOnOrAfter(start)
                BIWEEKLY -> fridayOnOrAfter(start).plusWeeks(1)
                MONTHLY -> currentMonthly(start)
                BIMONTHLY -> lastFridayOfMonth(currentMonthly(start).withDayOfMonth(1).plusMonths(1))
                QUARTERLY -> currentQuarterly(start)
                BIQUARTERLY -> lastFridayOfQuarter(currentQuarterly(start).withDayOfMonth(1).plusMonths(3))
            }
        }

        /** Stunde der Abrechnung am Verfallstag (UTC), wie Binance und OKX. */
        private const val SETTLEMENT_HOUR_UTC = 8

        /** Letzter Freitag des Monats von [start]; ist er schon vorbei, der des nächsten Monats. */
        private fun currentMonthly(start: LocalDate): LocalDate =
            lastFridayOfMonth(start).takeIf { !it.isBefore(start) } ?: lastFridayOfMonth(start.withDayOfMonth(1).plusMonths(1))

        /** Letzter Freitag des Quartals von [start]; ist er schon vorbei, der des nächsten Quartals. */
        private fun currentQuarterly(start: LocalDate): LocalDate =
            lastFridayOfQuarter(start).takeIf { !it.isBefore(start) } ?: lastFridayOfQuarter(start.withDayOfMonth(1).plusMonths(3))

        private fun fridayOnOrAfter(day: LocalDate): LocalDate = day.with(TemporalAdjusters.nextOrSame(FRIDAY))

        private fun lastFridayOfMonth(day: LocalDate): LocalDate = day.with(TemporalAdjusters.lastInMonth(FRIDAY))

        /** Letzter Freitag im dritten Monat des Quartals, in dem [day] liegt. */
        private fun lastFridayOfQuarter(day: LocalDate): LocalDate {
            val thirdMonth = day.month.firstMonthOfQuarter().plus(2)
            return lastFridayOfMonth(LocalDate.of(day.year, thirdMonth, 1))
        }
    }
}
